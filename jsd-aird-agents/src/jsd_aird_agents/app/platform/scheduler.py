"""One PostgreSQL coordinator for all packs. Dispatches the existing Run pipeline."""

from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timedelta, timezone
from uuid import UUID, uuid4

from psycopg.types.json import Jsonb
from pydantic import Field, field_validator

from jsd_aird_agents.app.platform.monitoring.contracts import MonitorOptions, effective_options, profile_for
from jsd_aird_agents.app.platform.triggers import TriggerSpec, next_fire
from jsd_aird_agents.contracts import Contract, CreateRun, digest
from jsd_aird_agents.errors import AgentError
from jsd_aird_agents.persistence.store import utcnow, wire

log = logging.getLogger(__name__)


class CreateSchedule(Contract):
    monitoring: MonitorOptions | None = None
    trigger: TriggerSpec | None = None
    pack_id: str
    job_id: str
    scope_id: UUID
    first_fire_at: datetime = Field(default_factory=utcnow)
    interval_seconds: int | None = Field(default=None, ge=1, le=31536000)
    max_runs: int = Field(default=10, ge=1, le=500)
    model_call_budget: int = Field(default=160, ge=1, le=50000)
    tool_call_budget: int = Field(default=320, ge=1, le=100000)
    max_attempts: int = Field(default=3, ge=1, le=5)

    @field_validator("first_fire_at")
    @classmethod
    def aware(cls, value):
        if value.tzinfo is None:
            raise ValueError("Timestamp must include a timezone")
        return value.astimezone(timezone.utc)


class Scheduler:
    def __init__(self, services, scheduler_id=None, *, after_dispatch=None):
        self.services, self.store, self.db = services, services.store, services.db
        self.packs, self.identity = services.packs, services.identity
        self.scheduler_id = scheduler_id or str(uuid4())
        self.after_dispatch = after_dispatch  # Crash-window fault probe, never enabled by config.

    async def audit(self, conn, job, kind, detail=None):
        await conn.execute(
            "INSERT INTO agent_runtime.schedule_audit(schedule_id,actor_id,kind,detail) VALUES(%s,%s,%s,%s)",
            (job["id"], job["actor_id"], kind, Jsonb(detail or {})),
        )

    async def create(self, principal, command: CreateSchedule, key):
        binding = self.packs.require(command.pack_id)
        definition = next((j for j in binding.manifest.scheduled_jobs if j.id == command.job_id), None)
        if not definition:
            raise AgentError("JOB_UNREGISTERED", "模块未注册此调度定义", 403)
        await self.packs.authorize(principal, [command.pack_id], {command.pack_id: str(command.scope_id)})
        trigger = command.trigger or (definition.trigger if command.interval_seconds is None else None)
        if command.trigger and command.interval_seconds:
            raise AgentError("TRIGGER_CONFLICT", "Cron/日期触发不能同时指定固定间隔")
        first = (
            command.first_fire_at
            if "first_fire_at" in command.model_fields_set
            else (next_fire(trigger, self.services.clock.now()) if trigger else self.services.clock.now())
        )
        if first is None:
            raise AgentError("TRIGGER_EXHAUSTED", "触发时间已过去")
        profile = profile_for(self.packs, definition.workflow)
        options = effective_options(profile[1], command.monitoring) if profile else None
        if command.monitoring and not profile:
            raise AgentError("MONITORING_WORKFLOW_REQUIRED", "此任务不是巡检任务")
        if options and options.notify_to == "approvers" and profile[1].kind != "approval":
            raise AgentError("NOTIFICATION_AUDIENCE_FORBIDDEN", "此巡检只允许提醒申请人", 403)
        command_data = command.model_dump(mode="json", exclude={"first_fire_at"})
        for field in ("monitoring", "trigger"):
            if command_data.get(field) is None:
                command_data.pop(field, None)
        from jsd_aird_agents.app.platform.runtime.execution import require_model_config

        require_model_config(
            self.services.settings, {"monitoring": {"options": options.model_dump()} if options else {}}
        )
        request_hash = digest(
            command_data
            | {
                "first_fire_at": command.first_fire_at.isoformat()
                if "first_fire_at" in command.model_fields_set
                else None
            }
        )
        async with self.db.pool.connection() as conn, conn.transaction():
            await conn.execute(
                "SELECT pg_advisory_xact_lock(hashtextextended(%s,5))", (f"{principal.actor_id}:{key}",)
            )
            row = await (
                await conn.execute(
                    "SELECT * FROM agent_runtime.schedules WHERE organization_id=%s AND actor_id=%s AND idempotency_key=%s",
                    (principal.organization_id, principal.actor_id, key),
                )
            ).fetchone()
            if row:
                if row["request_hash"] != request_hash:
                    raise AgentError("IDEMPOTENCY_CONFLICT", "同一调度幂等键对应不同参数", 409)
                return wire(row)
            row = await (
                await conn.execute(
                    """INSERT INTO agent_runtime.schedules
                (id,organization_id,actor_id,pack_id,job_id,workflow_id,pack_versions,scope_id,request,
                interval_seconds,event_type,next_fire_at,max_runs,model_call_budget,tool_call_budget,max_attempts,idempotency_key,request_hash,monitoring,trigger_spec)
                VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) RETURNING *""",
                    (
                        str(uuid4()),
                        principal.organization_id,
                        principal.actor_id,
                        command.pack_id,
                        definition.id,
                        definition.workflow,
                        Jsonb(self.packs.pin([command.pack_id])),
                        command.scope_id,
                        definition.request,
                        command.interval_seconds or definition.interval_seconds,
                        definition.event_type,
                        first,
                        command.max_runs,
                        command.model_call_budget,
                        command.tool_call_budget,
                        command.max_attempts,
                        key,
                        request_hash,
                        Jsonb(options.model_dump(mode="json") if options else {}),
                        Jsonb(trigger.model_dump(mode="json")) if trigger else None,
                    ),
                )
            ).fetchone()
            await self.audit(conn, row, "created", {"simulated": True})
        return wire(row)

    async def get(self, schedule_id, principal):
        job = await self.db.one(
            "SELECT * FROM agent_runtime.schedules WHERE id=%s AND organization_id=%s AND actor_id=%s",
            (schedule_id, principal.organization_id, principal.actor_id),
        )
        if not job:
            raise AgentError("NOT_FOUND", "调度任务不存在", 404)
        return wire(job)

    async def snapshot(self, schedule_id, principal):
        job = await self.get(schedule_id, principal)
        job.pop("request_hash", None)
        job.pop("idempotency_key", None)
        job["occurrences"] = wire(
            await self.db.all(
                "SELECT * FROM agent_runtime.schedule_occurrences WHERE schedule_id=%s ORDER BY created_at",
                (schedule_id,),
            )
        )
        job["audit"] = wire(
            await self.db.all(
                "SELECT seq,kind,detail,created_at FROM agent_runtime.schedule_audit WHERE schedule_id=%s ORDER BY seq",
                (schedule_id,),
            )
        )
        return {**job, "simulated": True}

    async def transition(self, schedule_id, principal, operation):
        target = {"pause": "PAUSED", "resume": "ACTIVE", "cancel": "CANCELLED"}.get(operation)
        if not target:
            raise AgentError("INVALID_SCHEDULE_OPERATION", "不支持此调度操作")
        await self.get(schedule_id, principal)
        async with self.db.pool.connection() as conn, conn.transaction():
            job = await (
                await conn.execute(
                    "SELECT * FROM agent_runtime.schedules WHERE id=%s FOR UPDATE", (schedule_id,)
                )
            ).fetchone()
            if job["status"] == target:
                return await self.snapshot(schedule_id, principal)
            if job["status"] not in {"ACTIVE", "PAUSED"}:
                raise AgentError("INVALID_SCHEDULE_STATE", "调度已终止", 409)
            if target == "ACTIVE":
                await self.packs.authorize(
                    principal, [job["pack_id"]], {job["pack_id"]: str(job["scope_id"])}
                )
            await conn.execute(
                "UPDATE agent_runtime.schedules SET status=%s,updated_at=now() WHERE id=%s",
                (target, schedule_id),
            )
            if target == "CANCELLED":
                await conn.execute(
                    "UPDATE agent_runtime.schedule_occurrences SET status='CANCELLED' WHERE schedule_id=%s AND status='PENDING'",
                    (schedule_id,),
                )
            await self.audit(conn, job, operation)
        if target == "CANCELLED":
            # Reconciliation repeats this if the process stops between these two transactions.
            for item in await self.db.all(
                "SELECT run_id FROM agent_runtime.schedule_occurrences WHERE schedule_id=%s AND status='DISPATCHED'",
                (schedule_id,),
            ):
                await self.store.cancel(str(item["run_id"]), principal)
        return await self.snapshot(schedule_id, principal)

    async def trigger(self, schedule_id, principal, event_type, key):
        await self.get(schedule_id, principal)
        async with self.db.pool.connection() as conn, conn.transaction():
            job = await (
                await conn.execute(
                    "SELECT * FROM agent_runtime.schedules WHERE id=%s FOR UPDATE", (schedule_id,)
                )
            ).fetchone()
            if job["status"] != "ACTIVE" or not job["event_type"] or event_type != job["event_type"]:
                raise AgentError("EVENT_TRIGGER_FORBIDDEN", "任务未启用或事件类型未注册", 403)
            await self.packs.authorize(principal, [job["pack_id"]], {job["pack_id"]: str(job["scope_id"])})
            await conn.execute(
                """INSERT INTO agent_runtime.schedule_occurrences(id,schedule_id,trigger_key,due_at)
                VALUES(%s,%s,%s,now()) ON CONFLICT(schedule_id,trigger_key) DO NOTHING""",
                (str(uuid4()), schedule_id, f"event:{event_type}:{key}"),
            )
            await self.audit(
                conn, job, "event_received", {"event_type": event_type, "key_sha256": digest(key)}
            )
        return await self.snapshot(schedule_id, principal)

    async def tick(self):
        await self.db.execute(
            """INSERT INTO agent_runtime.schedulers(id) VALUES(%s)
            ON CONFLICT(id) DO UPDATE SET heartbeat_at=now()""",
            (self.scheduler_id,),
        )
        jobs = await self.db.all("""SELECT id FROM agent_runtime.schedules WHERE status IN ('ACTIVE','PAUSED','CANCELLED')
            ORDER BY next_fire_at,id""")
        worked = False
        for row in jobs:
            async with self.db.pool.connection() as conn, conn.transaction():
                job = await (
                    await conn.execute(
                        "SELECT * FROM agent_runtime.schedules WHERE id=%s FOR UPDATE SKIP LOCKED",
                        (row["id"],),
                    )
                ).fetchone()
                if job:
                    worked = await self._process(conn, job) or worked
        return worked

    async def _process(self, conn, job):
        if job["status"] == "CANCELLED":
            from jsd_aird_agents.contracts import Principal

            owner = Principal(str(job["organization_id"]), str(job["actor_id"]), (), ())
            items = await (
                await conn.execute(
                    "SELECT id,run_id FROM agent_runtime.schedule_occurrences WHERE schedule_id=%s AND status='DISPATCHED'",
                    (job["id"],),
                )
            ).fetchall()
            for item in items:
                run = await self.store.run(str(item["run_id"]))
                if run["status"] not in {"SUCCEEDED", "FAILED", "CANCELLED"}:
                    await self.store.cancel(run["id"], owner)
                else:
                    await conn.execute(
                        "UPDATE agent_runtime.schedule_occurrences SET status=%s,updated_at=now() WHERE id=%s",
                        (run["status"], item["id"]),
                    )
            return bool(items)
        try:
            principal = self.identity.lookup(str(job["actor_id"]))
            if principal.organization_id != str(job["organization_id"]):
                raise AgentError("PERMISSION_DENIED", "调度主体组织发生变化", 403)
            self.packs.verify({"pack_ids": [job["pack_id"]], "pack_versions": job["pack_versions"]})
            await self.packs.authorize(principal, [job["pack_id"]], {job["pack_id"]: str(job["scope_id"])})
        except AgentError as exc:
            await conn.execute("UPDATE agent_runtime.schedules SET status='FAILED' WHERE id=%s", (job["id"],))
            await self.audit(conn, job, "authorization_failed", {"code": exc.code})
            return True
        active = await (
            await conn.execute(
                "SELECT * FROM agent_runtime.schedule_occurrences WHERE schedule_id=%s AND status='DISPATCHED' ORDER BY created_at LIMIT 1",
                (job["id"],),
            )
        ).fetchone()
        if active:
            run = await self.store.run(str(active["run_id"]))
            if job["status"] == "CANCELLED" and run["status"] not in {"SUCCEEDED", "FAILED", "CANCELLED"}:
                await self.store.cancel(run["id"], principal)
                return True
            if run["status"] not in {"SUCCEEDED", "FAILED", "CANCELLED"}:
                return False  # Includes human waits; no overlapping occurrence in this schedule.
            if (
                run["status"] == "FAILED"
                and (run["error"] or {}).get("retryable")
                and active["attempts"] < job["max_attempts"]
                and job["status"] == "ACTIVE"
            ):
                if active["due_at"] > self.services.clock.now():
                    return False
                await self.store.enqueue_resume(run["id"], principal, {"kind": "retry"}, conn=conn)
                await conn.execute(
                    "UPDATE agent_runtime.schedule_occurrences SET attempts=attempts+1,due_at=now()+(%s*interval '1 second'),updated_at=now() WHERE id=%s",
                    (min(2 ** active["attempts"], 60), active["id"]),
                )
                await self.audit(conn, job, "retry", {"run_id": run["id"], "attempt": active["attempts"] + 1})
                return True
            await conn.execute(
                "UPDATE agent_runtime.schedule_occurrences SET status=%s,error_code=%s,updated_at=now() WHERE id=%s",
                (run["status"], (run["error"] or {}).get("code"), active["id"]),
            )
            await self.audit(conn, job, "completed", {"run_id": run["id"], "status": run["status"]})
        if job["status"] != "ACTIVE":
            return bool(active)
        usage = await (
            await conn.execute(
                """SELECT count(*) AS runs,coalesce(sum(r.model_calls),0) AS model_calls,coalesce(sum(r.tool_calls),0) AS tool_calls
            FROM agent_runtime.schedule_occurrences o JOIN agent_runtime.runs r ON r.id=o.run_id WHERE o.schedule_id=%s""",
                (job["id"],),
            )
        ).fetchone()
        if (
            usage["runs"] >= job["max_runs"]
            or usage["model_calls"] >= job["model_call_budget"]
            or usage["tool_calls"] >= job["tool_call_budget"]
        ):
            await conn.execute(
                "UPDATE agent_runtime.schedules SET status='EXHAUSTED' WHERE id=%s", (job["id"],)
            )
            await conn.execute(
                "UPDATE agent_runtime.schedule_occurrences SET status='CANCELLED',error_code='SCHEDULE_BUDGET_EXHAUSTED' WHERE schedule_id=%s AND status='PENDING'",
                (job["id"],),
            )
            await self.audit(conn, job, "budget_exhausted")
            return True
        if job["next_fire_at"] and job["next_fire_at"] <= self.services.clock.now():
            key = "time:" + job["next_fire_at"].isoformat()
            await conn.execute(
                """INSERT INTO agent_runtime.schedule_occurrences(id,schedule_id,trigger_key,due_at)
                VALUES(%s,%s,%s,%s) ON CONFLICT(schedule_id,trigger_key) DO NOTHING""",
                (str(uuid4()), job["id"], key, job["next_fire_at"]),
            )
        occurrence = await (
            await conn.execute(
                """SELECT * FROM agent_runtime.schedule_occurrences
            WHERE schedule_id=%s AND status='PENDING' AND due_at<=%s ORDER BY due_at,id LIMIT 1 FOR UPDATE""",
                (job["id"], self.services.clock.now()),
            )
        ).fetchone()
        if not occurrence:
            if not job["next_fire_at"] and not job["event_type"]:
                await conn.execute(
                    "UPDATE agent_runtime.schedules SET status='EXHAUSTED' WHERE id=%s", (job["id"],)
                )
            return bool(active)
        command = CreateRun(
            request=job["request"],
            monitoring=MonitorOptions.model_validate(job["monitoring"]) if job["monitoring"] else None,
            pack_ids=[job["pack_id"]],
            resource_scopes={job["pack_id"]: job["scope_id"]},
        )
        limits = {
            "model_calls": min(
                self.services.settings.max_model_calls, job["model_call_budget"] - usage["model_calls"]
            ),
            "tool_calls": min(
                self.services.settings.max_tool_calls, job["tool_call_budget"] - usage["tool_calls"]
            ),
        }
        try:
            run = await self.store.create_run(
                principal,
                command,
                f"schedule:{job['id']}:{occurrence['trigger_key']}",
                workflow_id=job["workflow_id"],
                budget_limits=limits,
            )
        except AgentError as exc:
            if exc.code != "MODEL_NOT_CONFIGURED":
                raise
            await conn.execute("UPDATE agent_runtime.schedules SET status='FAILED' WHERE id=%s", (job["id"],))
            await conn.execute(
                "UPDATE agent_runtime.schedule_occurrences SET status='FAILED',error_code=%s WHERE id=%s",
                (exc.code, occurrence["id"]),
            )
            await self.audit(conn, job, "dispatch_failed", {"code": exc.code})
            return True
        if self.after_dispatch:
            await self.after_dispatch(run)
        await conn.execute(
            "UPDATE agent_runtime.schedule_occurrences SET status='DISPATCHED',run_id=%s,attempts=1,updated_at=now() WHERE id=%s",
            (run["id"], occurrence["id"]),
        )
        if occurrence["trigger_key"].startswith("time:"):
            # Coalesce missed intervals after downtime. Event occurrences keep their independent keys.
            next_at = (
                next_fire(TriggerSpec.model_validate(job["trigger_spec"]), self.services.clock.now())
                if job["trigger_spec"]
                else self.services.clock.now() + timedelta(seconds=job["interval_seconds"])
            )
            await conn.execute(
                "UPDATE agent_runtime.schedules SET next_fire_at=%s,updated_at=now() WHERE id=%s",
                (next_at, job["id"]),
            )
        await self.audit(
            conn, job, "dispatched", {"run_id": run["id"], "occurrence_id": str(occurrence["id"])}
        )
        return True

    async def run_forever(self):
        try:
            while True:
                try:
                    await self.tick()
                except Exception as exc:
                    log.error("scheduler_tick_failed exception_type=%s", type(exc).__name__)
                await asyncio.sleep(self.services.settings.scheduler_poll_seconds)
        finally:
            await self.db.execute("DELETE FROM agent_runtime.schedulers WHERE id=%s", (self.scheduler_id,))
