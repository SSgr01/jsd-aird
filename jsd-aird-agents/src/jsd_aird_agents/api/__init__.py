import asyncio
import json
import time
from contextlib import asynccontextmanager
from typing import Annotated
from uuid import UUID

from fastapi import Depends, FastAPI, Header, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse

from jsd_aird_agents import __version__
from jsd_aird_agents.app.platform.scheduler import CreateSchedule, Scheduler
from jsd_aird_agents.contracts import (
    TERMINAL,
    ActionDecision,
    Contract,
    CreateRun,
    Principal,
    ResumeRun,
)
from jsd_aird_agents.errors import AgentError
from jsd_aird_agents.services import Services
from jsd_aird_agents.settings import Settings

PREFIX = "/internal/v1/agents"


def create_app(settings: Settings | None = None, services: Services | None = None):
    settings = settings or Settings()
    services = services or Services(settings)

    @asynccontextmanager
    async def lifespan(app):
        await services.open()
        try:
            yield
        finally:
            await services.close()

    app = FastAPI(
        title="JSD Shared AI Platform (simulated domain)",
        version=__version__,
        lifespan=lifespan,
        docs_url=PREFIX + "/docs",
        openapi_url=PREFIX + "/openapi.json",
        redoc_url=None,
    )
    app.state.services = services

    @app.exception_handler(AgentError)
    async def agent_error(request, exc):
        return JSONResponse(
            status_code=exc.status,
            content={
                "error": {
                    "code": exc.code,
                    "message": exc.message,
                    "retryable": exc.retryable,
                    "detail": exc.detail,
                },
                "simulated": True,
            },
        )

    @app.exception_handler(RequestValidationError)
    async def validation_error(request, exc):
        return JSONResponse(
            status_code=422,
            content={
                "error": {
                    "code": "REQUEST_INVALID",
                    "message": "请求不符合接口 Schema",
                    "detail": [{"loc": list(e["loc"]), "type": e["type"]} for e in exc.errors()],
                },
                "simulated": True,
            },
        )

    def authenticate(authorization: Annotated[str | None, Header()] = None):
        return services.identity.authenticate(authorization)

    User = Annotated[Principal, Depends(authenticate)]

    @app.get(PREFIX + "/health/live")
    async def live():
        return {"status": "UP", "simulated": True, "version": __version__}

    @app.get(PREFIX + "/health/ready")
    async def ready():
        try:
            schema = await services.db.one("SELECT version FROM agent_runtime.schema_version WHERE version=5")
            workers = await services.db.one(
                """SELECT count(*) AS count FROM agent_runtime.workers
                WHERE heartbeat_at>now()-(%s*interval '1 second')""",
                (settings.worker_stale_seconds,),
            )
            ok = bool(schema and workers["count"])
            return JSONResponse(
                status_code=200 if ok else 503,
                content={
                    "ready": ok,
                    "database": bool(schema),
                    "worker": bool(workers["count"]),
                    "model_configured": settings.model_configured,
                    "monitoring_ready": ok,
                    "agent_ready": ok and settings.model_configured,
                    "simulated": True,
                },
            )
        except Exception:
            return JSONResponse(
                status_code=503, content={"ready": False, "database": False, "simulated": True}
            )

    scheduler = Scheduler(services)

    @app.get(PREFIX + "/capabilities")
    async def capabilities(user: User):
        return {
            "domain_backend": "mock",
            "identity_backend": "fixture",
            "model_backend": settings.model_backend,
            "model_configured": settings.model_configured,
            "real_java_enabled": False,
            "skills_supported": True,
            "skill_scripts_executable": False,
            "tools": [
                t
                for t in services.registry.describe()
                if services.registry.require(t["name"]).permission in user.permissions
            ],
            "vertical_packs": [
                p for p in services.packs.describe() if p["entry_permission"] in user.permissions
            ],
            "scheduler_supported": True,
            "proactive_monitoring": True,
            "execution_kinds": ["task", "graph"],
            "monitoring_requires_model": False,
            "notification_channels": ["simulated_in_app"],
            "simulated": True,
        }

    @app.get(PREFIX + "/skills")
    async def list_skills(user: User, pack_id: str = "project"):
        binding = services.packs.require(pack_id)
        if binding.manifest.entry_permission not in user.permissions:
            raise AgentError("PERMISSION_DENIED", "无模块访问权限", 403)
        return {
            "skills": [
                s
                for s in await services.skills.catalog(enabled_only=True)
                if s["pack_id"] in {pack_id, "platform"}
            ],
            "scripts_executable": False,
            "simulated": True,
        }

    @app.get(PREFIX + "/monitoring/profiles")
    async def monitoring_profiles(user: User):
        return {
            "profiles": [
                {
                    "id": p.id,
                    "name": p.name,
                    "pack_id": pid,
                    "rules": p.rules,
                    "version": p.version,
                    "defaults": p.defaults.model_dump(mode="json"),
                    "execution_kind": "task",
                    "ai_explanation_optional": True,
                }
                for pid, b in services.packs.bindings.items()
                if b.manifest.entry_permission in user.permissions
                for p in b.monitoring_profiles.values()
            ],
            "simulated": True,
        }

    @app.get(PREFIX + "/monitoring/findings")
    async def monitoring_findings(scope_id: UUID, user: User, pack_id: str = "project"):
        return {
            "findings": await services.monitoring.findings(user, pack_id, str(scope_id)),
            "simulated": True,
        }

    @app.get(PREFIX + "/notifications")
    async def notifications(user: User):
        return {"notifications": await services.monitoring.inbox(user), "simulated": True}

    @app.post(PREFIX + "/notifications/{notification_id}/read")
    async def notification_read(notification_id: UUID, user: User):
        return await services.monitoring.mark_read(user, str(notification_id))

    @app.get(PREFIX + "/runs/{run_id}/skills")
    async def run_skills(run_id: UUID, user: User):
        await services.store.run(str(run_id), user)
        return await services.skills.snapshot(str(run_id))

    @app.post(PREFIX + "/runs", status_code=202)
    async def create_run(
        command: CreateRun, user: User, idempotency_key: Annotated[str, Header(min_length=1, max_length=200)]
    ):
        run = await services.store.create_run(user, command, idempotency_key)
        return {
            "run_id": run["id"],
            "conversation_id": run["conversation_id"],
            "status": run["status"],
            "events_url": PREFIX + f"/runs/{run['id']}/events",
            "simulated": True,
        }

    @app.get(PREFIX + "/runs/{run_id}")
    async def get_run(run_id: UUID, user: User):
        return await services.store.snapshot(str(run_id), user)

    @app.post(PREFIX + "/runs/{run_id}/resume", status_code=202)
    async def resume(run_id: UUID, command: ResumeRun, user: User):
        run = await services.store.run(str(run_id), user)
        if run["status"] == "WAITING_CLARIFICATION":
            if (
                command.interrupt_id != (run["interrupt"] or {}).get("id")
                or not command.answer
                or command.retry
            ):
                raise AgentError("INVALID_RESUME", "必须提供对应中断 ID 和澄清答案", 409)
            payload = {"kind": "clarification", "answer": command.answer}
        elif run["status"] == "FAILED" and command.retry and not command.answer:
            payload = {"kind": "retry"}
        else:
            raise AgentError("INVALID_RESUME", "审批请使用动作决策接口；其他状态不能按此方式恢复", 409)
        await services.store.enqueue_resume(str(run_id), user, payload)
        return {"run_id": str(run_id), "status": "QUEUED", "simulated": True}

    @app.post(PREFIX + "/runs/{run_id}/cancel")
    async def cancel(run_id: UUID, user: User):
        return await services.store.cancel(str(run_id), user)

    @app.get(PREFIX + "/actions/{action_id}")
    async def get_action(action_id: UUID, user: User):
        return await services.actions.get(str(action_id), user)

    @app.post(PREFIX + "/actions/{action_id}/decisions")
    async def decide(action_id: UUID, command: ActionDecision, user: User):
        # Worker reconciles this durable decision; client-side approved=true is never consumed.
        return await services.actions.decide(str(action_id), user, command.decision, command.request_sha256)

    @app.get(PREFIX + "/artifacts/{artifact_id}")
    async def get_artifact(artifact_id: str, user: User):
        from jsd_aird_agents.app.platform.artifacts.store import ArtifactStore

        return await ArtifactStore(services.store).get(artifact_id, user)

    @app.get(PREFIX + "/packs")
    async def packs(user: User):
        return {
            "packs": [p for p in services.packs.describe() if p["entry_permission"] in user.permissions],
            "simulated": True,
        }

    @app.post(PREFIX + "/schedules", status_code=201)
    async def create_schedule(
        command: CreateSchedule,
        user: User,
        idempotency_key: Annotated[str, Header(min_length=1, max_length=200)],
    ):
        row = await scheduler.create(user, command, idempotency_key)
        return await scheduler.snapshot(row["id"], user)

    @app.get(PREFIX + "/schedules/{schedule_id}")
    async def get_schedule(schedule_id: UUID, user: User):
        return await scheduler.snapshot(str(schedule_id), user)

    @app.post(PREFIX + "/schedules/{schedule_id}/{operation}")
    async def change_schedule(schedule_id: UUID, operation: str, user: User):
        return await scheduler.transition(str(schedule_id), user, operation)

    class EventTrigger(Contract):
        event_type: str

    @app.post(PREFIX + "/schedules/{schedule_id}/events/trigger", status_code=202)
    async def trigger_schedule(
        schedule_id: UUID,
        command: EventTrigger,
        user: User,
        idempotency_key: Annotated[str, Header(min_length=1, max_length=200)],
    ):
        return await scheduler.trigger(str(schedule_id), user, command.event_type, idempotency_key)

    @app.get(PREFIX + "/runs/{run_id}/events")
    async def events(
        run_id: UUID, request: Request, user: User, last_event_id: Annotated[str | None, Header()] = None
    ):
        rid = str(run_id)
        await services.store.run(rid, user)
        try:
            cursor = int(last_event_id or 0)
            if cursor < 0:
                raise ValueError
        except ValueError as exc:
            raise AgentError("INVALID_EVENT_CURSOR", "Last-Event-ID 必须是非负事件序号") from exc
        if cursor:
            row = await services.db.one("SELECT run_id FROM agent_runtime.events WHERE seq=%s", (cursor,))
            if not row or str(row["run_id"]) != rid:
                raise AgentError("INVALID_EVENT_CURSOR", "游标不属于当前 Run", 409)

        async def stream():
            nonlocal cursor
            last_heartbeat = time.monotonic()
            while not await request.is_disconnected():
                batch = await services.store.events(rid, cursor)
                if batch:
                    for event in batch:
                        cursor = event["seq"]
                        payload = {**event["payload"], "seq": cursor, "event": event["kind"]}
                        yield f"id: {cursor}\nevent: {event['kind']}\ndata: {json.dumps(payload, ensure_ascii=False)}\n\n"
                    continue
                if (await services.store.run(rid, user))["status"] in TERMINAL:
                    return
                if time.monotonic() - last_heartbeat >= settings.sse_heartbeat_seconds:
                    yield ": keepalive\n\n"
                    last_heartbeat = time.monotonic()
                await asyncio.sleep(settings.sse_poll_seconds)

        return StreamingResponse(
            stream(),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )

    return app
