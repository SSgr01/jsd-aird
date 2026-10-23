from __future__ import annotations

from datetime import datetime, timezone
from uuid import uuid4

from psycopg.types.json import Jsonb

from jsd_aird_agents.contracts import ACTIVE, GRAPH_VERSION, CreateRun, Principal, RunContext, digest
from jsd_aird_agents.errors import AgentError
from jsd_aird_agents.persistence.database import Database


def wire(value):
    if isinstance(value, dict):
        return {k: wire(v) for k, v in value.items()}
    if isinstance(value, (tuple, list)):
        return [wire(v) for v in value]
    if isinstance(value, (str, int, float, bool)) or value is None:
        return value
    return value.isoformat() if hasattr(value, "isoformat") else str(value)


class Store:
    def __init__(self, db: Database, packs=None):
        self.db = db
        self.packs = packs
        self.action_gateways = []
        self.clock = None

    async def event(self, run_id: str, kind: str, payload: dict, *, key: str | None = None, conn=None):
        if conn is None:
            async with self.db.pool.connection() as owned, owned.transaction():
                await self.event(run_id, kind, payload, key=key, conn=owned)
            return
        # Sequence allocation must follow commit order within a run. Otherwise a later
        # transaction can commit first and make an SSE cursor skip an earlier event.
        await conn.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s,2))", (str(run_id),))
        data = {**wire(payload), "run_id": str(run_id), "simulated": True}
        sql = """INSERT INTO agent_runtime.events(run_id,event_key,kind,payload) VALUES(%s,%s,%s,%s)
                 ON CONFLICT(run_id,event_key) DO NOTHING"""
        args = (run_id, key or f"{kind}:{digest(data)}", kind, Jsonb(data))
        await conn.execute(sql, args)

    async def create_run(
        self, principal: Principal, command: CreateRun, key: str, *, workflow_id=None, budget_limits=None
    ):
        scopes = {k: str(v) for k, v in command.resource_scopes.items()}
        if command.project_id:
            if "project" not in command.pack_ids or scopes.get("project", str(command.project_id)) != str(
                command.project_id
            ):
                raise AgentError("SCOPE_MISMATCH", "项目参数与模块范围不一致", 403)
            scopes["project"] = str(command.project_id)
        if set(scopes) - set(command.pack_ids):
            raise AgentError("SCOPE_MISMATCH", "资源范围包含未选择的模块", 403)
        versions = self.packs.pin(command.pack_ids) if self.packs else {}
        if self.packs:
            await self.packs.authorize(principal, command.pack_ids, scopes)
        workflow_id = workflow_id or command.workflow_id
        if workflow_id and self.packs:
            matches = [
                w
                for pid in command.pack_ids
                for w in self.packs.require(pid).manifest.workflows
                if w.id == workflow_id
            ]
            if len(matches) != 1:
                raise AgentError("WORKFLOW_UNREGISTERED", "工作流未注册或归属不明确", 403)
        from jsd_aird_agents.app.platform.monitoring.contracts import effective_options, profile_for

        profile = profile_for(self.packs, workflow_id) if self.packs else None
        if command.monitoring is not None and not profile:
            raise AgentError("MONITORING_WORKFLOW_REQUIRED", "巡检参数只能用于已注册巡检工作流")
        monitoring = {}
        if profile:
            pid, definition = profile
            if command.pack_ids != [pid] or not scopes.get(pid):
                raise AgentError("SCOPE_REQUIRED", "巡检需要明确的单模块资源范围", 403)
            options = effective_options(definition, command.monitoring)
            if options.notify_to == "approvers" and definition.kind != "approval":
                raise AgentError("NOTIFICATION_AUDIENCE_FORBIDDEN", "此巡检只允许提醒申请人", 403)
            monitoring = {
                "options": options.model_dump(mode="json"),
                "as_of": (self.clock.now() if self.clock else utcnow()).isoformat(),
                "profile": definition.id,
                "rule_version": definition.version,
            }
        tasks = (
            [
                self.packs.require(pid).task_factories[workflow_id]
                for pid in command.pack_ids
                if workflow_id in self.packs.require(pid).task_factories
            ]
            if self.packs
            else []
        )
        execution_kind = "task" if len(tasks) == 1 else "graph"
        execution_version = tasks[0].version if execution_kind == "task" else None
        request_payload = command.model_dump(mode="json")
        # Preserve the original project-only API's hash when new optional fields are omitted.
        for field, default in (
            ("pack_ids", ["project"]),
            ("resource_scopes", {}),
            ("workflow_id", None),
            ("monitoring", None),
        ):
            if request_payload.get(field) == default:
                request_payload.pop(field, None)
        if workflow_id:
            request_payload["workflow_id"] = workflow_id
        request_hash = digest(request_payload)
        async with self.db.pool.connection() as conn, conn.transaction():
            # Serializes creation using the same caller key, even before a conversation exists.
            await conn.execute(
                "SELECT pg_advisory_xact_lock(hashtextextended(%s,0))",
                (f"create:{principal.organization_id}:{principal.actor_id}:{key}",),
            )
            existing = await (
                await conn.execute(
                    "SELECT * FROM agent_runtime.runs WHERE organization_id=%s AND actor_id=%s AND idempotency_key=%s",
                    (principal.organization_id, principal.actor_id, key),
                )
            ).fetchone()
            if existing:
                if existing["request_hash"] != request_hash:
                    raise AgentError("IDEMPOTENCY_CONFLICT", "同一幂等键对应不同请求", 409)
                return wire(existing)
            from jsd_aird_agents.app.platform.runtime.execution import require_model_config

            require_model_config(self.db.settings, {"monitoring": monitoring})
            cid = str(command.conversation_id or uuid4())
            if command.conversation_id:
                convo = await (
                    await conn.execute(
                        "SELECT * FROM agent_runtime.conversations WHERE id=%s AND organization_id=%s AND actor_id=%s FOR UPDATE",
                        (cid, principal.organization_id, principal.actor_id),
                    )
                ).fetchone()
                if not convo:
                    raise AgentError("NOT_FOUND", "会话不存在", 404)
                busy = await (
                    await conn.execute(
                        "SELECT id FROM agent_runtime.runs WHERE conversation_id=%s AND status=ANY(%s)",
                        (cid, list(ACTIVE)),
                    )
                ).fetchone()
                if busy:
                    raise AgentError("CONVERSATION_BUSY", "会话存在活动或等待中的 Run", 409)
            else:
                await conn.execute(
                    """INSERT INTO agent_runtime.conversations(id,organization_id,actor_id,thread_id)
                    VALUES(%s,%s,%s,%s)""",
                    (cid, principal.organization_id, principal.actor_id, cid),
                )
            rid = str(uuid4())
            row = await (
                await conn.execute(
                    """INSERT INTO agent_runtime.runs
                (id,conversation_id,organization_id,actor_id,project_id,request,request_hash,idempotency_key,status,graph_version,pack_ids,pack_versions,resource_scopes,workflow_id,budget_limits,monitoring,execution_kind,execution_version)
                VALUES(%s,%s,%s,%s,%s,%s,%s,%s,'QUEUED',%s,%s,%s,%s,%s,%s,%s,%s,%s) RETURNING *""",
                    (
                        rid,
                        cid,
                        principal.organization_id,
                        principal.actor_id,
                        command.project_id,
                        command.request,
                        request_hash,
                        key,
                        GRAPH_VERSION,
                        Jsonb(command.pack_ids),
                        Jsonb(versions),
                        Jsonb(scopes),
                        workflow_id,
                        Jsonb(budget_limits or {}),
                        Jsonb(monitoring),
                        execution_kind,
                        execution_version,
                    ),
                )
            ).fetchone()
            await conn.execute(
                "UPDATE agent_runtime.conversations SET last_run_id=%s WHERE id=%s", (rid, cid)
            )
            await self.event(rid, "run.queued", {"conversation_id": cid}, conn=conn)
            return wire(row)

    async def run(self, run_id: str, principal: Principal | None = None):
        row = await self.db.one("SELECT * FROM agent_runtime.runs WHERE id=%s", (run_id,))
        if not row or (
            principal
            and (
                str(row["organization_id"]) != principal.organization_id
                or str(row["actor_id"]) != principal.actor_id
            )
        ):
            raise AgentError("NOT_FOUND", "Run 不存在", 404)
        return wire(row)

    async def snapshot(self, run_id: str, principal: Principal):
        row = await self.run(run_id, principal)
        row["steps"] = wire(
            await self.db.all(
                "SELECT step_id,status,result,error FROM agent_runtime.steps WHERE run_id=%s ORDER BY step_id",
                (run_id,),
            )
        )
        row["evidence"] = [
            r["payload"]
            for r in await self.db.all(
                "SELECT payload FROM agent_runtime.evidence WHERE run_id=%s ORDER BY id", (run_id,)
            )
        ]
        row["simulated"] = True
        for name in ("request_hash", "idempotency_key", "resume", "worker_id", "lease_until"):
            row.pop(name, None)
        return row

    async def check_active(self, ctx: RunContext):
        row = await self.run(ctx.run_id)
        if self.packs:
            self.packs.verify(row)
        if (
            row["organization_id"] != ctx.principal.organization_id
            or row["actor_id"] != ctx.principal.actor_id
            or row["conversation_id"] != ctx.conversation_id
        ):
            raise AgentError("RUN_CONTEXT_MISMATCH", "可信上下文与 Run 绑定不一致", 403)
        if row["cancel_requested"]:
            raise AgentError("CANCELLED", "执行已取消", 409)
        if ctx.worker_id and (row["worker_id"] != ctx.worker_id or row["lease_epoch"] != ctx.lease_epoch):
            raise AgentError("LEASE_LOST", "Worker 已失去执行权", 409, retryable=True)

    async def step(self, run_id, step_id, status, result=None, error=None):
        async with self.db.pool.connection() as conn, conn.transaction():
            await conn.execute(
                """INSERT INTO agent_runtime.steps(run_id,step_id,status,result,error)
                VALUES(%s,%s,%s,%s,%s) ON CONFLICT(run_id,step_id) DO UPDATE SET
                status=excluded.status,result=excluded.result,error=excluded.error,updated_at=now()""",
                (run_id, step_id, status, Jsonb(wire(result)), Jsonb(error)),
            )
            await self.event(
                run_id, f"step.{status.lower()}", {"step_id": step_id, "status": status}, conn=conn
            )

    async def save_evidence(self, items):
        for item in items:
            await self.db.execute(
                """INSERT INTO agent_runtime.evidence(id,run_id,payload) VALUES(%s,%s,%s)
                ON CONFLICT(id) DO NOTHING""",
                (item.id, item.run_id, Jsonb(item.model_dump(mode="json"))),
            )

    async def complete_task_step(self, ctx, step_id, result):
        """Commit resumable task output only while this Worker owns the Run."""
        async with self.db.pool.connection() as conn, conn.transaction():
            run = await (
                await conn.execute(
                    "SELECT worker_id,lease_epoch,cancel_requested,status FROM agent_runtime.runs WHERE id=%s FOR UPDATE",
                    (ctx.run_id,),
                )
            ).fetchone()
            if (
                not run
                or run["worker_id"] != ctx.worker_id
                or run["lease_epoch"] != ctx.lease_epoch
                or run["status"] != "RUNNING"
            ):
                raise AgentError("LEASE_LOST", "Worker 已失去固定任务执行权", 409, retryable=True)
            if run["cancel_requested"]:
                raise AgentError("CANCELLED", "执行已取消", 409)
            await conn.execute(
                """INSERT INTO agent_runtime.steps(run_id,step_id,status,result)
                VALUES(%s,%s,'SUCCEEDED',%s) ON CONFLICT(run_id,step_id) DO UPDATE
                SET status='SUCCEEDED',result=excluded.result,error=NULL,updated_at=now()""",
                (ctx.run_id, step_id, Jsonb(wire(result))),
            )
            await self.event(
                ctx.run_id, "step.succeeded", {"step_id": step_id, "status": "SUCCEEDED"}, conn=conn
            )

    async def budget(self, run_id, kind, limit):
        if kind not in {"model_calls", "tool_calls"}:
            raise ValueError(kind)
        row = await self.db.one(
            f"""UPDATE agent_runtime.runs SET {kind}={kind}+1
            WHERE id=%s AND {kind}<LEAST(%s,COALESCE((budget_limits->>'{kind}')::int,%s)) AND NOT cancel_requested RETURNING {kind}""",
            (run_id, limit, limit),
        )
        if not row:
            raise AgentError("BUDGET_EXCEEDED", f"{kind} 预算耗尽或执行已取消", 409)

    async def claim(self, worker_id):
        async with self.db.pool.connection() as conn, conn.transaction():
            row = await (
                await conn.execute("""SELECT * FROM agent_runtime.runs
                WHERE status='QUEUED' OR (status='RUNNING' AND lease_until<now())
                ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1""")
            ).fetchone()
            if not row:
                return None
            row = await (
                await conn.execute(
                    """UPDATE agent_runtime.runs SET status='RUNNING',worker_id=%s,
                lease_epoch=lease_epoch+1,lease_until=now()+(%s * interval '1 second'),updated_at=now()
                WHERE id=%s RETURNING *""",
                    (worker_id, self.db.settings.worker_stale_seconds, row["id"]),
                )
            ).fetchone()
            return wire(row)

    async def heartbeat(self, run_id, worker_id, epoch):
        await self.db.execute(
            """INSERT INTO agent_runtime.workers(id) VALUES(%s)
            ON CONFLICT(id) DO UPDATE SET heartbeat_at=now()""",
            (worker_id,),
        )
        row = await self.db.one(
            """UPDATE agent_runtime.runs SET lease_until=now()+(%s*interval '1 second')
            WHERE id=%s AND worker_id=%s AND lease_epoch=%s RETURNING cancel_requested""",
            (self.db.settings.worker_stale_seconds, run_id, worker_id, epoch),
        )
        if not row:
            raise AgentError("LEASE_LOST", "执行租约已转移", 409, retryable=True)
        if row["cancel_requested"]:
            raise AgentError("CANCELLED", "执行已取消", 409)

    async def finish(self, ctx, status, *, result=None, error=None, interrupt=None, elapsed=0):
        async with self.db.pool.connection() as conn, conn.transaction():
            current = await (
                await conn.execute(
                    "SELECT cancel_requested FROM agent_runtime.runs WHERE id=%s AND worker_id=%s AND lease_epoch=%s FOR UPDATE",
                    (ctx.run_id, ctx.worker_id, ctx.lease_epoch),
                )
            ).fetchone()
            if not current:
                return
            if current["cancel_requested"]:
                status, result, interrupt = "CANCELLED", None, None
                error = {"code": "CANCELLED", "message": "执行已取消", "retryable": False}
            row = await (
                await conn.execute(
                    """UPDATE agent_runtime.runs SET status=%s,result=%s,error=%s,
                interrupt=%s,resume=NULL,active_seconds=active_seconds+%s,worker_id=NULL,lease_until=NULL,updated_at=now()
                WHERE id=%s AND worker_id=%s AND lease_epoch=%s RETURNING id""",
                    (
                        status,
                        Jsonb(result),
                        Jsonb(error),
                        Jsonb(interrupt),
                        elapsed,
                        ctx.run_id,
                        ctx.worker_id,
                        ctx.lease_epoch,
                    ),
                )
            ).fetchone()
            if row:
                if status == "SUCCEEDED":
                    await self.event(
                        ctx.run_id,
                        "answer.delta",
                        {"delta": result["answer"], "validated": True},
                        key="answer.final",
                        conn=conn,
                    )
                await self.event(
                    ctx.run_id,
                    f"run.{status.lower()}",
                    {"status": status, "error": error, "interrupt": interrupt},
                    key=f"run.{status}:{digest([result, error, interrupt])}",
                    conn=conn,
                )

    async def enqueue_resume(self, run_id, principal, payload, *, conn=None):
        if conn is None:
            async with self.db.pool.connection() as owned, owned.transaction():
                return await self.enqueue_resume(run_id, principal, payload, conn=owned)
        row = await (
            await conn.execute(
                """SELECT r.*,c.last_run_id FROM agent_runtime.runs r
            JOIN agent_runtime.conversations c ON c.id=r.conversation_id
            WHERE r.id=%s AND r.organization_id=%s AND r.actor_id=%s FOR UPDATE OF r,c""",
                (run_id, principal.organization_id, principal.actor_id),
            )
        ).fetchone()
        if not row:
            raise AgentError("NOT_FOUND", "Run 不存在", 404)
        if str(row["last_run_id"]) != str(run_id):
            raise AgentError("STALE_RUN", "旧 Run 已被新轮次取代", 409)
        if row["status"] == "QUEUED" and row["resume"] == payload:
            return
        if row["status"] not in {"WAITING_CLARIFICATION", "WAITING_APPROVAL", "FAILED"}:
            raise AgentError("INVALID_RUN_STATE", "当前状态不可恢复", 409)
        if row["status"] == "FAILED" and not (row["error"] or {}).get("retryable"):
            raise AgentError("NOT_RETRYABLE", "该错误不能沿用原 Run 重试", 409)
        await conn.execute(
            """UPDATE agent_runtime.runs SET status='QUEUED',resume=%s,error=NULL,
            cancel_requested=false,updated_at=now() WHERE id=%s""",
            (Jsonb(payload), run_id),
        )
        await self.event(run_id, "run.resumed", payload, conn=conn)

    async def cancel(self, run_id, principal):
        await self.run(run_id, principal)
        async with self.db.pool.connection() as conn, conn.transaction():
            row = await (
                await conn.execute("SELECT * FROM agent_runtime.runs WHERE id=%s FOR UPDATE", (run_id,))
            ).fetchone()
            if row["status"] in ACTIVE:
                await conn.execute(
                    """UPDATE agent_runtime.runs SET cancel_requested=true,
                    status=CASE WHEN status='RUNNING' THEN status ELSE 'CANCELLED' END,updated_at=now() WHERE id=%s""",
                    (run_id,),
                )
                for gateway in self.action_gateways:
                    await gateway.cancel_actions(run_id, conn)
                await self.event(run_id, "run.cancel_requested", {}, conn=conn)
                if row["status"] != "RUNNING":
                    await self.event(run_id, "run.cancelled", {"status": "CANCELLED"}, conn=conn)
        return await self.snapshot(run_id, principal)

    async def events(self, run_id, after=0):
        return wire(
            await self.db.all(
                """SELECT seq,kind,payload,created_at FROM agent_runtime.events
            WHERE run_id=%s AND seq>%s ORDER BY seq LIMIT 200""",
                (run_id, after),
            )
        )

    async def call(self, run_id, step_id, kind, request_hash):
        return await self.db.one(
            """SELECT * FROM agent_runtime.calls
            WHERE run_id=%s AND step_id=%s AND call_kind=%s AND request_hash=%s""",
            (run_id, step_id, kind, request_hash),
        )

    async def save_call(
        self, run_id, step_id, kind, request_hash, status, result=None, usage=None, error=None
    ):
        await self.db.execute(
            """INSERT INTO agent_runtime.calls
            (run_id,step_id,call_kind,request_hash,status,result,usage,error_code,attempts)
            VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s)
            ON CONFLICT(run_id,step_id,call_kind,request_hash) DO UPDATE SET status=excluded.status,
            result=excluded.result,usage=excluded.usage,error_code=excluded.error_code,
            attempts=agent_runtime.calls.attempts+excluded.attempts,updated_at=now()""",
            (
                run_id,
                step_id,
                kind,
                request_hash,
                status,
                Jsonb(wire(result)),
                Jsonb(usage or {}),
                error,
                1 if status == "RUNNING" else 0,
            ),
        )


def utcnow():
    return datetime.now(timezone.utc)
