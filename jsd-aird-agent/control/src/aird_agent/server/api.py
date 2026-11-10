import asyncio
import contextlib
import hmac
import json
from contextlib import asynccontextmanager
from uuid import UUID

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import StreamingResponse

from aird_agent.capabilities.literature.private_sources import (
    OaRequest,
    Upload,
    acquire_source,
    create_conversation,
    detach,
    list_sources,
    upload,
)
from aird_agent.core.stage_timing import StageTimingMiddleware, fields
from aird_agent.core.store import Store
from aird_agent.evidence.citations import normalize_citations
from aird_agent.hosts.jsd.composition import Service
from aird_agent.hosts.jsd.operations import list_operations, monitor, reconcile, reconcile_cancelled_execution
from aird_agent.server.contracts import (
    HarnessSettings,
    MessageRequest,
    QueueRequest,
    RunRequest,
    retryable_failure,
)
from aird_agent.server.event_stream import EventNotifications, stream_events
from aird_agent.server.security import verify
from aird_agent.server.task_entry import TaskRequest, create_task

PREFIX = "/internal/v1/harness"


def create_app(settings=None, store=None, service=None):
    settings = settings or HarnessSettings()
    store = store or Store(settings.database_url.get_secret_value())
    service = service or Service(settings, store)
    notifications = EventNotifications()
    cancellations = {}

    @asynccontextmanager
    async def lifespan(app):
        await store.pool.open(wait=True, timeout=10)
        await store.migrate()
        admission = asyncio.create_task(service.admission.run())
        listener = asyncio.create_task(notifications.listen(settings.database_url.get_secret_value()))
        try:
            yield
        finally:
            admission.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await admission
            listener.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await listener
            for task in cancellations.values():
                task.cancel()
            await asyncio.gather(*list(cancellations.values()), return_exceptions=True)
            await service.http.aclose()
            await store.pool.close()

    app = FastAPI(title="JSD DeepSeek business service", lifespan=lifespan)
    app.add_middleware(StageTimingMiddleware, enabled=settings.stage_timing)

    async def principal(request: Request, x_jsd_agent_ticket: str = Header(default="")):
        user = verify(settings.shared_secret.get_secret_value(), x_jsd_agent_ticket, "control")[0]
        if user.automation_grant_id:
            if not request.url.path.startswith(PREFIX + "/dsh/"):
                raise HTTPException(403, "BACKGROUND_SCOPE_DENIED")
            await service.briefings.check(user)
        return user

    @app.get(PREFIX + "/capabilities")
    async def capabilities(user=Depends(principal)):
        available = False
        try:
            models = await service.dsh_command(user, "models", {})
            available = models.get("available") is True
        except Exception:
            pass
        sandbox = False
        if settings.sandbox_enabled:
            try:
                response = await service.http.get(
                    settings.sandbox_url + "/health",
                    headers={"Authorization": "Bearer " + settings.sandbox_secret.get_secret_value()},
                    timeout=3,
                )
                sandbox = response.status_code == 200 and response.json().get("available") is True
            except Exception:
                pass
        return {
            "engine": "dsh-v1",
            "available": available,
            "sandbox": sandbox,
            "rcs": available,
            "protocol_version": 1,
            "profiles": ["agent"],
            "source_kinds": ["KB_PUBLICATION", "BUSINESS_RECORD", "DATA_SOURCE_FILE", "WEB_RESULT"],
            "subagents": False,
            "simulated": False,
        }

    @app.get(PREFIX + "/conversations")
    async def conversations(user=Depends(principal)):
        # Listing metadata must not expose raw model context or source material.
        return [
            {
                k: row.get(k)
                for k in (
                    "id",
                    "revision",
                    "topic_id",
                    "profile",
                    "title",
                    "active_run",
                    "last_run",
                    "updated_at",
                )
            }
            for row in await store.list(user, "conversation")
            if row.get("visibility") != "HOST_MANAGED"
        ]

    @app.post(PREFIX + "/tasks", status_code=202)
    async def task(body: TaskRequest, user=Depends(principal), idempotency_key: str = Header()):
        return public_run(await create_task(service, user, body, idempotency_key))

    @app.post(PREFIX + "/conversations", status_code=201)
    async def new_conversation(user=Depends(principal)):
        return await create_conversation(service, user)

    @app.get(PREFIX + "/conversations/{cid}/sources")
    async def private_sources(cid: UUID, user=Depends(principal)):
        return await list_sources(service, user, str(cid))

    @app.post(PREFIX + "/conversations/{cid}/sources", status_code=202)
    async def private_upload(
        cid: UUID, body: Upload, user=Depends(principal), idempotency_key: str = Header()
    ):
        return await upload(service, user, str(cid), body, idempotency_key)

    @app.post(PREFIX + "/conversations/{cid}/sources/oa", status_code=202)
    async def private_oa(
        cid: UUID, body: OaRequest, user=Depends(principal), idempotency_key: str = Header()
    ):
        return await acquire_source(service, user, str(cid), body, idempotency_key)

    @app.delete(PREFIX + "/conversations/{cid}/sources/{sid}")
    async def detach_source(cid: UUID, sid: UUID, user=Depends(principal)):
        return await detach(service, user, str(cid), str(sid))

    @app.get(PREFIX + "/conversations/{cid}")
    async def conversation(cid: UUID, user=Depends(principal)):
        result = await store.get(user, "conversation", str(cid))
        snapshots = await service.authorize_history(user, result)
        result["messages"] = [
            {
                **message,
                "text": normalize_citations(
                    message["text"], snapshots.get(message.get("run_id"), {}).get("evidence", {})
                ),
            }
            for message in result.get("messages", [])
        ]
        # File registration can precede creation of a research topic. Preserve
        # those downloads across later turns using already authorized history;
        # this is a response projection, not inherited work or completion.
        outputs = {}
        for rid in dict.fromkeys(message.get("run_id") for message in result["messages"]):
            for output in snapshots.get(rid, {}).get("registered_outputs", []):
                outputs[(output["kind"], output["id"])] = {
                    key: output[key] for key in ("id", "kind", "title", "path") if key in output
                }
        result["registered_outputs"] = list(outputs.values())
        return {k: v for k, v in result.items() if k != "evidence"}

    @app.post(PREFIX + "/conversations/{cid}/messages", status_code=202)
    async def message(
        cid: UUID, body: MessageRequest, user=Depends(principal), idempotency_key: str = Header()
    ):
        return await service.accept_message(user, str(cid), body, idempotency_key)

    @app.post(PREFIX + "/runs", status_code=202)
    async def create(body: RunRequest, user=Depends(principal), idempotency_key: str = Header()):
        run = await service.create_run(user, body, idempotency_key)
        return public_run(run)

    @app.post(PREFIX + "/conversations/{cid}/queue")
    async def change_queue(
        cid: UUID, body: QueueRequest, user=Depends(principal), idempotency_key: str = Header()
    ):
        return await service.change_queue(user, str(cid), body, idempotency_key)

    @app.get(PREFIX + "/runs/{rid}")
    async def get_run(rid: UUID, user=Depends(principal)):
        run = await store.get(user, "run", str(rid))
        if run.get("session_deleted"):
            raise HTTPException(410, "SESSION_DELETED")
        await service.authorized_topic(user, run, execution=False)
        return public_run(run)

    @app.get(PREFIX + "/monitor")
    async def runtime_monitor(user=Depends(principal)):
        return await monitor(service, user)

    @app.get(PREFIX + "/runs/{rid}/operations")
    async def operations(rid: UUID, user=Depends(principal)):
        return await list_operations(service, user, str(rid))

    @app.post(PREFIX + "/runs/{rid}/operations/{call_id}/reconcile")
    async def reconcile_operation(rid: UUID, call_id: str, user=Depends(principal)):
        if len(call_id) > 200:
            raise HTTPException(422, "INVALID_CALL_ID")
        return await reconcile(service, user, str(rid), call_id)

    @app.get(PREFIX + "/packs")
    async def packs(user=Depends(principal)):
        context = await service.java(user, "runtime_context", {"profile": "agent", "context_refs": []})
        return [
            item
            for item in service.packs.describe()
            if item["id"] in context.get("pack_ids", []) and item["id"] != "core"
        ]

    @app.post(PREFIX + "/runs/{rid}/cancel")
    async def cancel(rid: UUID, user=Depends(principal)):
        return await service.cancel(user, str(rid))

    @app.post(PREFIX + "/runs/{rid}/resume", status_code=202)
    async def resume(rid: UUID, user=Depends(principal)):
        return await service.resume(user, str(rid))

    @app.get(PREFIX + "/runs/{rid}/events")
    async def events(
        rid: UUID, request: Request, user=Depends(principal), last_event_id: str = Header(default="0")
    ):
        try:
            cursor = int(last_event_id)
            if cursor < 0:
                raise ValueError()
        except ValueError:
            raise HTTPException(400, "Invalid event cursor") from None
        await store.events(user, str(rid), cursor)

        return StreamingResponse(
            stream_events(store, service, notifications, user, str(rid), request, cursor),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )

    @app.post(PREFIX + "/background/{action}")
    async def background(action: str, request: Request, authorization: str = Header(default="")):
        if not hmac.compare_digest(("Bearer " + settings.runtime_secret.get_secret_value()).encode(), authorization.encode()):
            raise HTTPException(401, "Invalid runtime credential")
        body = await request.json()
        if action == "owners":
            return await service.briefings.owners(body.get("offset", 0))
        if action == "ticket":
            return await service.briefings.ticket(str(UUID(body["owner_id"])))
        raise HTTPException(404, "Unknown background operation")

    @app.post(PREFIX + "/dsh/{action}")
    async def dsh(action: str, request: Request, authorization: str = Header(default=""), user=Depends(principal)):
        expected = "Bearer " + settings.runtime_secret.get_secret_value()
        if not hmac.compare_digest(expected.encode(), authorization.encode()):
            raise HTTPException(401, "Invalid runtime credential")
        raw = await request.body()
        if len(raw) > 20 * 1024 * 1024:
            raise HTTPException(413, "Runtime payload too large")
        body = json.loads(raw)
        fields(session_id=body.get("session_id"), run_id=body.get("run_id"), turn=body.get("turn"),
               request_bytes=len(raw), event_type=(body.get("event") or {}).get("type"))
        if action == "session-delete":
            from aird_agent.core.session_deletion import delete_session
            return await delete_session(store, user, body)
        if body.get("session_id") and action in {"register", "admit", "admission-state", "admission-renew", "admission-release", "run-state", "run-diagnostics", "ownership", "restore", "auxiliary-admit", "gate", "tool", "native-tool"}:
            from aird_agent.core.session_deletion import require_session_available
            await require_session_available(store, user, body["session_id"])
        if user.automation_grant_id and body.get("session_id"):
            owner = await service.briefings.owner(user)
            if not any(task["session_id"] == body["session_id"] and task["status"] in {"active", "paused", "ended", "INSTALLING"} for task in owner["tasks"].values()):
                raise HTTPException(403, "BACKGROUND_SESSION_DENIED")
        if action == "builtin-plan":
            return await service.briefings.install(user)
        if action == "builtin-state":
            return await service.briefings.owner(user)
        if action == "builtin-pending":
            return await service.briefings.pending(user)
        if action == "builtin-sync":
            return await service.briefings.reconcile(user, body)
        if action == "builtin-start":
            return await service.briefings.begin_creation(user, body)
        if action == "builtin-revoke":
            return await service.briefings.revoke(user)
        if action in {"context", "resource"}:
            return await service.session_view(user, action, body)
        if action == "catalog":
            context = await service.java(user, "runtime_context", {"profile": body["profile"], "context_refs": []})
            packs = context["pack_ids"]
            return {"tools": service.packs.tools(packs),
                "instructions": "你是企业研发助手。只使用当前用户授权的资料。", "max_output_tokens": settings.model_output_tokens,
                "max_tool_characters": service.tool_character_budget,
                "web_search_available": (await service.host_call(user, "web_search_status")).get("available", False)}
        if action == "bind":
            return await service.bind_session(user, body["run_id"], body["session_id"])
        if action == "admit":
            return await service.register_step(user, {**body, "admission_only": True})
        if action in {"admission-state", "admission-renew", "admission-release"}:
            run = await store.get(user, "run", body["run_id"])
            if run.get("dsh_session_id") != body.get("session_id"):
                raise HTTPException(403, "SESSION_NOT_OWNED")
            if action == "admission-state":
                return await service.admission.state(user, run["id"])
            if action == "admission-renew":
                return await service.admission.renew(user, run["id"], body["epoch"])
            return await service.admission.release(user, run["id"], body.get("epoch"),
                cancelled=body.get("cancelled",False), stopped=body.get("stopped",False))
        if action in {"run-cancel", "run-retry"}:
            run=await store.get(user,"run",body["run_id"])
            if run.get("dsh_session_id") != body.get("session_id"):
                raise HTTPException(403,"SESSION_NOT_OWNED")
            return await (service.cancel(user,run["id"]) if action=="run-cancel" else service.resume(user,run["id"]))
        if action in {"run-state", "run-diagnostics"}:
            conversation = await service.session_conversation(user, body["session_id"])
            if not conversation:
                return {"run": None}
            await service.authorize_conversation(user, conversation)
            rid = conversation.get("active_run") or conversation.get("last_run")
            if not rid:
                return {"run": None}
            run = await store.get(user,"run",rid)
            await service.authorized_topic(user,run,execution=False)
            value = await service.admission.state(user,rid)
            value.update(message=public_failure(run.get("error")), retryable=retryable_failure(run))
            if action == "run-diagnostics":
                if user.role.upper() not in {"ADMIN", "SUPER_ADMIN", "SYSTEM_ADMIN"}:
                    raise HTTPException(403,"ADMIN_REQUIRED")
                async with store.pool.connection() as conn:
                    row=await(await conn.execute("SELECT state,epoch,lease_until,attempts,diagnostic FROM agent_runtime.harness_admission WHERE run_id=%s",(rid,))).fetchone()
                value["diagnostic"]={"error":run.get("error"),"details":run.get("failure_diagnostic"),"admission":row}
            return {"run":value,"admin":user.role.upper() in {"ADMIN","SUPER_ADMIN","SYSTEM_ADMIN"}}
        if action == "register":
            return await service.register_step(user, body)
        if action == "output-repair":
            return await service.begin_output_repair(user, body)
        if action in {"restore", "ownership"}:
            conversation = await service.session_conversation(user, body["session_id"])
            if not conversation:
                if action in {"restore", "ownership"}:
                    return {"run": None}
                raise HTTPException(404, "Session not found")
            if conversation["profile"] != body["profile"]:
                raise HTTPException(403, "SESSION_PROFILE_CHANGED")
            await service.authorize_conversation(user, conversation)
            snapshots = await service.authorize_history(user, conversation)
            rid = conversation.get("active_run") or conversation.get("last_run")
            if not rid:
                return {"run": None}
            run = snapshots.get(rid) or await store.get(user, "run", rid)
            await service.authorized_topic(user, run, execution=False)
            if action == "ownership":
                return {"allowed": True}
            return {"run": {**run, "tools": service.tools_for(run), "active_tools": run.get("active_tools", service.bindings.initial_tools(run)),
                "instructions": service.instructions_for(run)}}
        if action == "gate":
            return await service.gate_dsh(user, body)
        if action == "auxiliary-admit":
            return await service.admit_auxiliary(user, body)
        if action in {"model-result", "auxiliary-result"}:
            return await service.model_result(user, body, auxiliary=action == "auxiliary-result")
        if action == "native-output":
            from aird_agent.hosts.jsd.native_files import native_output
            return await native_output(service, user, body)
        if action == "native-cancel":
            from aird_agent.core.contracts import digest
            run = await service.session_run(user, body)
            await service.authorized_topic(user, run, execution=False)
            call_id = body.get("call_id")
            if not isinstance(call_id, str) or not 1 <= len(call_id) <= 200:
                raise HTTPException(422, "INVALID_CALL_ID")
            async with store.pool.connection() as conn:
                operation = await (await conn.execute(
                    "SELECT tool_name,execution_epoch FROM agent_runtime.harness_calls WHERE run_id=%s AND call_id=%s",
                    (run["id"], call_id))).fetchone()
            if operation and operation["tool_name"] != "dsh_execute":
                raise HTTPException(403, "NATIVE_EXECUTION_REQUIRED")
            epoch = operation["execution_epoch"] if operation else run["epoch"]
            result = await service.bindings.sandbox.cancel(digest([run["id"], epoch, call_id])[:32])
            key = (user.key, run["id"], body["call_id"])
            if key not in cancellations:
                task = asyncio.create_task(reconcile_cancelled_execution(service, user, run["id"], body["call_id"]))
                cancellations[key] = task
                task.add_done_callback(lambda task: cancellations.pop(key, None))
            return {"accepted": True, "status": result.get("status", "UNKNOWN"),
                "pending": result.get("status") in {"UNKNOWN", "RUNNING", "QUEUED", "CANCELLING"}}
        if action == "native-tool":
            run = await service.session_run(user, body)
            await service.authorized_topic(user, run)
            if body["name"] == "artifact_save" and not body["arguments"].get("source_quotes"):
                async with store.pool.connection() as conn:
                    prior = await (await conn.execute(
                        "SELECT result FROM agent_runtime.harness_calls WHERE run_id=%s AND tool_name='artifact_save' AND status='SUCCEEDED' "
                        "AND arguments->>'path'=%s AND arguments->>'sha256'=%s ORDER BY updated_at DESC LIMIT 1",
                        (run["id"], body["arguments"]["path"], body["arguments"]["sha256"]))).fetchone()
                if prior:
                    return prior["result"]
            return await service.tool(user, run["id"], run["epoch"], body["call_id"], body["name"], body["arguments"], native=True)
        if action == "tool":
            run = await service.session_run(user, body)
            return await service.tool(user, run["id"], run["epoch"], body["call_id"], body["name"], body["arguments"])
        if action == "event":
            return await service.project_event(user, body)
        if action == "stream":
            return await service.project_stream(user, body)
        if action == "complete":
            return await service.complete_dsh(user, body)
        if action == "failure":
            run = await service.session_run(user, body)
            async with store.transaction(user, run["id"]) as conn:
                run = await store.get(user, "run", run["id"], conn)
                if body.get("epoch") is not None and body["epoch"] != run["epoch"]:
                    raise HTTPException(409, "STALE_EXECUTION_EPOCH")
                # A native stopping error must preserve the business validation diagnosis.
                if body.get("cancelled"):
                    run.update(status="CANCELLED", error="DSH_EXECUTION_INTERRUPTED")
                elif not (run.get("status") == "FAILED" and run.get("dsh_completed_turn") == run.get("dsh_turn")):
                    code = body.get("error_code")
                    known = {"TOKEN_BUDGET_EXCEEDED", "BUDGET_EXCEEDED", "SIDE_EFFECT_RECONCILIATION_REQUIRED", "CITATION_REPAIR_BUDGET_EXCEEDED"}
                    run.update(status="FAILED", error=code if code in known else "DSH_EXECUTION_INTERRUPTED")
                run["failure_diagnostic"] = body.get("diagnostic", {})
                from aird_agent.core.admission import RunAdmission
                RunAdmission.settle_time(run)
                await store.update(user, "run", run, conn)
                await service.briefings.finish(user, run, conn)
                await store.event(user, run["id"], "run." + run["status"].lower(), {"error": run["error"]}, conn)
                await conn.execute("UPDATE agent_runtime.harness_calls SET status='UNKNOWN',updated_at=now() WHERE run_id=%s AND status='RUNNING'", (run["id"],))
            return {"accepted": True}
        raise HTTPException(404, "Unknown DeepSeek adapter operation")

    return app


def public_run(run):
    from aird_agent.evidence.links import public_answer, public_citations
    return {
        **{
            k: v
            for k, v in run.items()
            if k not in {"principal", "failure_diagnostic", "admission_resume"}
        },
        **{
            key: public_answer(normalize_citations(run[key], run.get("evidence", {})), run.get("evidence", {}))
            for key in ("result", "last_text")
            if isinstance(run.get(key), str)
        },
        "assistant_messages": [
            {**message, "text": normalize_citations(message["text"], run.get("evidence", {}))}
            for message in run.get("assistant_messages", [])
        ],
        "error": public_failure(run.get("error")),
        "retryable": retryable_failure(run),
        "citations": public_citations(run.get("result", ""), run.get("evidence", {})),
    }


def public_failure(error):
    if not error:
        return None
    return {"SIDE_EFFECT_RECONCILIATION_REQUIRED":"执行结果需要管理员核对后才能重试",
            "DSH_SESSION_LOST":"原会话记录已不可用，请新建对话并重新提交原请求",
            "OUTPUT_LIMIT_EXCEEDED":"回答较长，已达到本轮输出上限，可以继续",
            "AUTHORIZATION_REQUIRED":"权限已变化，请重新登录并重试"}.get(str(error),"本轮任务未完成，可以重试")
