import asyncio
import contextlib
import hmac
import json
import time
from contextlib import asynccontextmanager
from uuid import UUID

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import StreamingResponse

from aird_agent.server.task_entry import TaskRequest, create_task
from aird_agent.capabilities.literature.private_sources import (
    OaRequest,
    Upload,
    acquire_source,
    create_conversation,
    detach,
    list_sources,
    upload,
)
from aird_agent.core.store import Store
from aird_agent.evidence.citations import normalize_citations
from aird_agent.hosts.jsd.composition import Service
from aird_agent.hosts.jsd.operations import list_operations, monitor, reconcile
from aird_agent.server.contracts import (
    HarnessSettings,
    MessageRequest,
    Principal,
    QueueRequest,
    RunRequest,
    retryable_failure,
)
from aird_agent.server.event_stream import EventNotifications, stream_events
from aird_agent.server.security import verify

PREFIX = "/internal/v1/harness"


def create_app(settings=None, store=None, service=None):
    settings = settings or HarnessSettings()
    store = store or Store(settings.database_url.get_secret_value())
    service = service or Service(settings, store)
    notifications = EventNotifications()

    @asynccontextmanager
    async def lifespan(app):
        await store.pool.open(wait=True, timeout=10)
        listener = asyncio.create_task(notifications.listen(settings.database_url.get_secret_value()))
        try:
            yield
        finally:
            listener.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await listener
            await service.http.aclose()
            await store.pool.close()

    app = FastAPI(title="JSD DeepSeek business service", lifespan=lifespan)

    def principal(x_jsd_agent_ticket: str = Header(default="")):
        return verify(settings.shared_secret.get_secret_value(), x_jsd_agent_ticket, "control")[0]

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

    @app.post(PREFIX + "/dsh/{action}")
    async def dsh(action: str, request: Request, authorization: str = Header(default=""), user=Depends(principal)):
        expected = "Bearer " + settings.runtime_secret.get_secret_value()
        if not hmac.compare_digest(expected.encode(), authorization.encode()):
            raise HTTPException(401, "Invalid runtime credential")
        raw = await request.body()
        if len(raw) > 20 * 1024 * 1024:
            raise HTTPException(413, "Runtime payload too large")
        body = json.loads(raw)
        if action in {"context", "resource"}:
            return await service.session_view(user, action, body)
        if action == "catalog":
            context = await service.java(user, "runtime_context", {"profile": body["profile"], "context_refs": []})
            packs = context["pack_ids"]
            return {"tools": service.packs.tools(packs),
                "instructions": "你是企业研发助手。只使用当前用户授权的资料。", "max_output_tokens": settings.model_output_tokens,
                "web_search_available": (await service.host_call(user, "web_search_status")).get("available", False)}
        if action == "bind":
            return await service.bind_session(user, body["run_id"], body["session_id"])
        if action == "register":
            return await service.register_step(user, body)
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
            return {"run": {**run, "tools": service.tools_for(run), "active_tools": run.get("active_tools", service.bindings.initial_tools(run)),
                "instructions": service.instructions_for(run)}}
        if action == "gate":
            return await service.gate_dsh(user, body)
        if action == "auxiliary-admit":
            return await service.admit_auxiliary(user, body)
        if action in {"model-result", "auxiliary-result"}:
            return await service.model_result(user, body, auxiliary=action == "auxiliary-result")
        if action == "native-cancel":
            from aird_agent.core.contracts import digest
            run = await service.session_run(user, body)
            await service.authorized_topic(user, run, execution=False)
            await service.bindings.sandbox.cancel(digest([run["id"], run["epoch"], body["call_id"]])[:32])
            return {"accepted": True}
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
                # A native stopping error must preserve the business validation diagnosis.
                if body.get("cancelled"):
                    run.update(status="CANCELLED", error="DSH_EXECUTION_INTERRUPTED")
                elif not (run.get("status") == "FAILED" and run.get("dsh_completed_turn") == run.get("dsh_turn")):
                    code = body.get("error_code")
                    known = {"TOKEN_BUDGET_EXCEEDED", "BUDGET_EXCEEDED", "SIDE_EFFECT_RECONCILIATION_REQUIRED"}
                    run.update(status="FAILED", error=code if code in known else "DSH_EXECUTION_INTERRUPTED")
                await store.update(user, "run", run, conn)
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
            if k not in {"principal"}
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
        "retryable": retryable_failure(run),
        "citations": public_citations(run.get("result", ""), run.get("evidence", {})),
    }
