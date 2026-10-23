from __future__ import annotations

from uuid import NAMESPACE_URL, uuid5

from psycopg.types.json import Jsonb

from jsd_aird_agents.contracts import DeadlineArgs, RunContext, ToolResult, digest
from jsd_aird_agents.errors import AgentError
from jsd_aird_agents.evidence import build_evidence
from jsd_aird_agents.persistence.store import Store, utcnow, wire


class MockJavaGateway:
    """Persistent test authority. All tables are in the isolated mock_domain schema."""

    def __init__(self, store: Store, identity=None):
        self.store, self.db = store, store.db
        self.identity = identity

    async def require(self, ctx, permission, project_id):
        p = self.identity.lookup(ctx.principal.actor_id) if self.identity else ctx.principal
        if p.organization_id != ctx.principal.organization_id:
            raise AgentError("PERMISSION_DENIED", "模拟身份组织发生变化", 403)
        if permission not in p.permissions or project_id not in p.projects:
            raise AgentError("PERMISSION_DENIED", "模拟身份无对应权限或项目范围", 403)
        row = await self.db.one(
            "SELECT id FROM mock_domain.projects WHERE id=%s AND organization_id=%s",
            (project_id, p.organization_id),
        )
        if not row:
            raise AgentError("NOT_FOUND", "项目不可访问", 404)

    async def projects(self, principal, reference=None):
        if self.identity:
            current = self.identity.lookup(principal.actor_id)
            if current.organization_id != principal.organization_id:
                raise AgentError("PERMISSION_DENIED", "模拟身份组织发生变化", 403)
            principal = current
        if "project.view" not in principal.permissions:
            raise AgentError("PERMISSION_DENIED", "缺少 project.view", 403)
        rows = await self.db.all(
            """SELECT * FROM mock_domain.projects
            WHERE organization_id=%s AND id=ANY(%s::uuid[]) ORDER BY id""",
            (principal.organization_id, list(principal.projects)),
        )
        return [wire(r) for r in rows if not reference or reference in str(r["id"]) or reference in r["name"]]

    async def resolve_task(self, context, arguments):
        await self.require(context, "project.view", context.project_id)
        rows = await self.db.all(
            "SELECT * FROM mock_domain.tasks WHERE project_id=%s ORDER BY id", (context.project_id,)
        )
        task_id, ref = arguments.get("task_id"), arguments.get("task_ref")
        rows = [r for r in rows if (str(r["id"]) == str(task_id) if task_id else not ref or ref in r["name"])]
        if len(rows) != 1:
            raise AgentError(
                "CLARIFICATION_REQUIRED",
                "请明确需要操作的任务",
                409,
                detail={"candidates": [{"id": str(r["id"]), "name": r["name"]} for r in rows]},
            )
        return wire(rows[0])

    def evidence(self, ctx, source_type, resource_id, version, title, data, locator=None):
        return build_evidence(ctx, source_type, resource_id, version, title, wire(data), locator)

    async def read(self, name, arguments, ctx):
        project_id = str(arguments.get("project_id") or ctx.project_id)
        if ctx.project_id and project_id != ctx.project_id:
            raise AgentError("SCOPE_MISMATCH", "工具不能切换当前 Run 的项目范围", 403)
        ctx = ctx.with_project(project_id)
        await self.require(ctx, "project.view", project_id)
        if name in {"get_project_monitoring_snapshot", "get_project_approval_snapshot"}:
            from jsd_aird_agents.app.verticals.project.monitoring import read_snapshot

            return await read_snapshot(self, name, ctx)
        if name == "get_project_snapshot":
            row = wire(await self.db.one("SELECT * FROM mock_domain.projects WHERE id=%s", (project_id,)))
            return ToolResult(
                data=row,
                evidence=[self.evidence(ctx, "PROJECT", project_id, row["version"], row["name"], row)],
            )
        if name == "list_project_stages":
            rows = wire(
                await self.db.all(
                    "SELECT * FROM mock_domain.stages WHERE project_id=%s ORDER BY id", (project_id,)
                )
            )
            return ToolResult(
                data=rows, evidence=[self.evidence(ctx, "STAGE", r["id"], 0, r["name"], r) for r in rows]
            )
        if name == "list_project_tasks":
            rows = wire(
                await self.db.all(
                    "SELECT * FROM mock_domain.tasks WHERE project_id=%s ORDER BY id", (project_id,)
                )
            )
            return ToolResult(
                data=rows,
                evidence=[self.evidence(ctx, "TASK", r["id"], r["version"], r["name"], r) for r in rows],
            )
        if name == "get_task_detail":
            row = await self.resolve_task(ctx, arguments)
            return ToolResult(
                data=row, evidence=[self.evidence(ctx, "TASK", row["id"], row["version"], row["name"], row)]
            )
        if name == "search_project_knowledge":
            await self.require(ctx, "ai.use", project_id)
            # A fixture search, not a duplicate production retrieval implementation.
            rows = wire(
                await self.db.all(
                    "SELECT * FROM mock_domain.documents WHERE project_id=%s ORDER BY id", (project_id,)
                )
            )
            return ToolResult(
                data=rows,
                evidence=[
                    self.evidence(
                        ctx,
                        "KNOWLEDGE_DOCUMENT",
                        r["id"],
                        r["version"],
                        r["title"],
                        r["content"],
                        {"kind": "mock_document", "paragraph": 1},
                    )
                    for r in rows
                ],
            )
        raise AgentError("UNKNOWN_TOOL", "未注册的领域工具")

    @staticmethod
    def binding(a):
        return {
            k: wire(a[k])
            for k in (
                "organization_id",
                "actor_id",
                "run_id",
                "conversation_id",
                "tool",
                "target_id",
                "arguments",
                "expected_version",
                "risk",
            )
        }

    async def preview(self, ctx, step_id, tool, arguments):
        await self.store.check_active(ctx)
        await self.require_run_tool(ctx, tool)
        if tool != "update_project_task":
            raise AgentError("ACTION_NOT_EXPOSED", "不支持该业务变更", 403)
        parsed = DeadlineArgs.model_validate(arguments)
        await self.require(ctx, "project.update", ctx.project_id)
        if parsed.project_id and str(parsed.project_id) != ctx.project_id:
            raise AgentError("SCOPE_MISMATCH", "动作不能切换项目", 403)
        task = await self.resolve_task(ctx, parsed.model_dump(mode="json", exclude_none=True))
        expected = task["version"] if parsed.expected_version is None else parsed.expected_version
        if expected != task["version"]:
            raise AgentError("VERSION_CONFLICT", "任务版本已变化，需要重新预览", 409)
        args = {
            "task_id": task["id"],
            "planned_date": parsed.planned_date.isoformat(),
            "reason": parsed.reason,
        }
        action = dict(
            id=str(uuid5(NAMESPACE_URL, f"{ctx.run_id}:{step_id}")),
            organization_id=ctx.principal.organization_id,
            project_id=ctx.project_id,
            run_id=ctx.run_id,
            conversation_id=ctx.conversation_id,
            actor_id=ctx.principal.actor_id,
            step_id=step_id,
            tool=tool,
            target_id=task["id"],
            arguments=args,
            expected_version=expected,
            risk="MEDIUM",
        )
        request_hash = digest(self.binding(action))
        async with self.db.pool.connection() as conn, conn.transaction():
            await conn.execute(
                """INSERT INTO mock_domain.actions
                (id,organization_id,project_id,run_id,conversation_id,actor_id,step_id,tool,target_id,arguments,
                 request_hash,expected_version,risk,before_state,idempotency_key,expires_at)
                VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,now()+(%s*interval '1 second'))
                ON CONFLICT(run_id,step_id) DO NOTHING""",
                (
                    action["id"],
                    action["organization_id"],
                    ctx.project_id,
                    ctx.run_id,
                    ctx.conversation_id,
                    action["actor_id"],
                    step_id,
                    tool,
                    task["id"],
                    Jsonb(args),
                    request_hash,
                    expected,
                    "MEDIUM",
                    Jsonb(task),
                    digest([action["organization_id"], action["id"]]),
                    self.db.settings.approval_ttl_seconds,
                ),
            )
            row = await (
                await conn.execute(
                    "SELECT * FROM mock_domain.actions WHERE run_id=%s AND step_id=%s", (ctx.run_id, step_id)
                )
            ).fetchone()
            if row["request_hash"] != request_hash:
                raise AgentError("ACTION_CHANGED", "原动作参数已绑定；需创建新提议和审批", 409)
            await self.store.event(
                ctx.run_id,
                "action.proposed",
                {"action_id": action["id"], "request_sha256": request_hash, "before": task, "after": args},
                conn=conn,
            )
        return {**wire(row), "simulated": True}

    async def get(self, action_id, principal):
        row = await self.db.one(
            "SELECT * FROM mock_domain.actions WHERE id=%s AND organization_id=%s",
            (action_id, principal.organization_id),
        )
        if not row or (str(row["actor_id"]) != principal.actor_id and not principal.can_approve):
            raise AgentError("NOT_FOUND", "动作不存在", 404)
        ctx = RunContext(principal, str(row["conversation_id"]), str(row["run_id"]), str(row["project_id"]))
        await self.require(ctx, "project.view", ctx.project_id)
        return {**wire(row), "simulated": True}

    async def decide(self, action_id, principal, decision, request_hash):
        action = await self.get(action_id, principal)
        ctx = RunContext(principal, action["conversation_id"], action["run_id"], action["project_id"])
        await self.require(ctx, "project.update", ctx.project_id)
        if not principal.can_approve or principal.actor_id == action["actor_id"]:
            raise AgentError("APPROVER_REQUIRED", "该模拟身份没有审批资格", 403)
        error = None
        async with self.db.pool.connection() as conn, conn.transaction():
            row = await (
                await conn.execute("SELECT * FROM mock_domain.actions WHERE id=%s FOR UPDATE", (action_id,))
            ).fetchone()
            if row["request_hash"] != request_hash or digest(self.binding(row)) != row["request_hash"]:
                raise AgentError("ACTION_CHANGED", "审批内容摘要不一致", 409)
            if row["expires_at"] <= utcnow():
                await conn.execute(
                    "UPDATE mock_domain.actions SET approval_status='EXPIRED' WHERE id=%s AND execution_status='PENDING'",
                    (action_id,),
                )
                error = AgentError("APPROVAL_EXPIRED", "审批已过期", 409)
            elif row["approval_status"] == decision and str(row["approver_id"]) == principal.actor_id:
                pass
            elif row["execution_status"] != "PENDING" or row["approval_status"] not in (
                {"PENDING", "APPROVED"} if decision == "REVOKED" else {"PENDING"}
            ):
                error = AgentError("INVALID_APPROVAL_STATE", "该审批不能再次变更", 409)
            else:
                await conn.execute(
                    """UPDATE mock_domain.actions SET approval_status=%s,approver_id=%s,
                    decided_at=now() WHERE id=%s""",
                    (decision, principal.actor_id, action_id),
                )
                await self.store.event(
                    action["run_id"],
                    f"action.{decision.lower()}",
                    {"action_id": action_id, "approver_id": principal.actor_id},
                    conn=conn,
                )
        if error:
            raise error
        return await self.get(action_id, principal)

    def verify(self, actual, arguments, expected_version):
        return (
            actual["planned_date"] == arguments["planned_date"] and actual["version"] == expected_version + 1
        )

    async def execute(self, ctx, action_id):
        await self.require_run_tool(ctx, "update_project_task")
        await self.require(ctx, "project.update", ctx.project_id)
        error, receipt = None, None
        async with self.db.pool.connection() as conn, conn.transaction():
            run = await (
                await conn.execute("SELECT * FROM agent_runtime.runs WHERE id=%s FOR UPDATE", (ctx.run_id,))
            ).fetchone()
            row = await (
                await conn.execute("SELECT * FROM mock_domain.actions WHERE id=%s FOR UPDATE", (action_id,))
            ).fetchone()
            if not row or any(
                str(row[k]) != str(v)
                for k, v in {
                    "run_id": ctx.run_id,
                    "conversation_id": ctx.conversation_id,
                    "organization_id": ctx.principal.organization_id,
                    "actor_id": ctx.principal.actor_id,
                    "project_id": ctx.project_id,
                }.items()
            ):
                raise AgentError("ACTION_BINDING_MISMATCH", "动作身份或目标绑定不一致", 403)
            if row["execution_status"] == "SUCCEEDED":
                return row["receipt"]
            if not run or run["cancel_requested"]:
                raise AgentError("CANCELLED", "Run 已取消", 409)
            if ctx.worker_id and (run["worker_id"] != ctx.worker_id or run["lease_epoch"] != ctx.lease_epoch):
                raise AgentError("LEASE_LOST", "执行权已转移", 409, retryable=True)
            if row["approval_status"] != "APPROVED" or not row["approver_id"] or row["consumed_at"]:
                raise AgentError("APPROVAL_REQUIRED", "缺少有效、未消费的人工审批", 409)
            if self.identity:
                approver = self.identity.lookup(str(row["approver_id"]))
                if not approver.can_approve:
                    raise AgentError("APPROVER_REQUIRED", "原审批人当前已无审批资格", 403)
                await self.require(
                    RunContext(approver, ctx.conversation_id, ctx.run_id, ctx.project_id),
                    "project.update",
                    ctx.project_id,
                )
            if row["expires_at"] <= utcnow():
                error = AgentError("APPROVAL_EXPIRED", "审批已过期", 409)
            elif (
                row["tool"] != "update_project_task"
                or row["risk"] != "MEDIUM"
                or digest(self.binding(row)) != row["request_hash"]
            ):
                error = AgentError("ACTION_CHANGED", "获批动作、参数或风险已变化", 409)
            else:
                actual = await (
                    await conn.execute(
                        """UPDATE mock_domain.tasks SET planned_date=%s,version=version+1
                    WHERE id=%s AND project_id=%s AND version=%s RETURNING *""",
                        (
                            row["arguments"]["planned_date"],
                            row["target_id"],
                            ctx.project_id,
                            row["expected_version"],
                        ),
                    )
                ).fetchone()
                if not actual:
                    error = AgentError("VERSION_CONFLICT", "任务版本变化，请重新规划、预览和审批", 409)
                else:
                    verified = wire(
                        await (
                            await conn.execute(
                                "SELECT * FROM mock_domain.tasks WHERE id=%s", (row["target_id"],)
                            )
                        ).fetchone()
                    )
                    if not self.verify(verified, row["arguments"], row["expected_version"]):
                        raise AgentError("WRITE_VERIFICATION_FAILED", "写后验证失败，事务已回滚", 500)
                    receipt = {
                        "action_id": action_id,
                        "run_id": ctx.run_id,
                        "target_id": str(row["target_id"]),
                        "actor_id": ctx.principal.actor_id,
                        "approver_id": str(row["approver_id"]),
                        "request_sha256": row["request_hash"],
                        "before_version": row["expected_version"],
                        "after_version": verified["version"],
                        "verified": True,
                        "actual": verified,
                        "simulated": True,
                    }
                    await conn.execute(
                        """UPDATE mock_domain.actions SET execution_status='SUCCEEDED',approval_status='CONSUMED',
                        consumed_at=now(),receipt=%s WHERE id=%s""",
                        (Jsonb(receipt), action_id),
                    )
                    await self.store.event(
                        ctx.run_id, "action.executed", receipt, key=f"action.executed:{action_id}", conn=conn
                    )
            if error:
                await conn.execute(
                    """UPDATE mock_domain.actions SET approval_status=%s,execution_status='CONFLICTED'
                    WHERE id=%s""",
                    ("EXPIRED" if error.code == "APPROVAL_EXPIRED" else "INVALIDATED", action_id),
                )
        if error:
            raise error
        return receipt

    async def require_entry(self, ctx, permission):
        principal = self.identity.lookup(ctx.principal.actor_id) if self.identity else ctx.principal
        if (
            principal.organization_id != ctx.principal.organization_id
            or permission not in principal.permissions
        ):
            raise AgentError("PERMISSION_DENIED", "缺少项目权限", 403)
        if not await self.projects(principal):
            raise AgentError("PERMISSION_DENIED", "没有可访问项目", 403)

    async def require_run_tool(self, ctx, tool):
        run = await self.store.run(ctx.run_id)
        if "project" not in run["pack_ids"] or run["resource_scopes"].get("project") != ctx.project_id:
            raise AgentError("PACK_TOOL_FORBIDDEN", "动作不属于本轮模块或资源范围", 403)
        if self.store.packs:
            self.store.packs.verify(run)
            if run.get("workflow_id"):
                workflows = self.store.packs.require("project").manifest.workflows
                if not any(w.id == run["workflow_id"] and tool in w.tools for w in workflows):
                    raise AgentError("PACK_TOOL_FORBIDDEN", "工作流未声明该动作", 403)

    async def cancel_actions(self, run_id, conn):
        await conn.execute(
            """UPDATE mock_domain.actions SET approval_status='REVOKED'
            WHERE run_id=%s AND execution_status='PENDING' AND approval_status IN ('PENDING','APPROVED')""",
            (run_id,),
        )

    async def reconcile(self):
        await self.db.execute("""UPDATE mock_domain.actions SET approval_status='EXPIRED'
            WHERE execution_status='PENDING' AND approval_status IN ('PENDING','APPROVED') AND expires_at<=now()""")
        await self.db.execute("""UPDATE agent_runtime.runs r SET status='QUEUED',
            resume=jsonb_build_object('kind','approval','action_id',a.id::text),updated_at=now()
            FROM mock_domain.actions a WHERE a.run_id=r.id AND r.status='WAITING_APPROVAL'
            AND r.interrupt->'value'->>'action_id'=a.id::text
            AND a.approval_status IN ('APPROVED','REJECTED','REVOKED','CONSUMED','EXPIRED','INVALIDATED')""")
