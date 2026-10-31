import asyncio
import contextlib
import hmac
import json
import time
from contextlib import asynccontextmanager
from uuid import UUID

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import StreamingResponse

from .contracts import HarnessSettings, MessageRequest, Principal, QueueRequest, RunRequest, retryable_failure
from .research import normalize_citations
from .security import verify
from .service import Service
from .store import Store

PREFIX = "/internal/v1/harness"


def create_app(settings=None, store=None, service=None):
    settings = settings or HarnessSettings()
    store = store or Store(settings.database_url.get_secret_value())
    service = service or Service(settings, store)

    @asynccontextmanager
    async def lifespan(app):
        await store.pool.open(wait=True, timeout=10)
        worker = asyncio.create_task(service.worker())
        try:
            yield
        finally:
            worker.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await worker
            await service.http.aclose()
            await store.pool.close()

    app = FastAPI(title="JSD pi Harness", lifespan=lifespan)

    def principal(x_jsd_agent_ticket: str = Header(default="")):
        return verify(settings.shared_secret.get_secret_value(), x_jsd_agent_ticket, "control")[0]

    @app.get(PREFIX + "/capabilities")
    async def capabilities(user=Depends(principal)):
        import importlib.util

        available = False
        try:
            response = await service.http.get(
                settings.runtime_url + "/health",
                headers={"Authorization": "Bearer " + settings.runtime_secret.get_secret_value()},
                timeout=3,
            )
            available = response.status_code == 200 and response.json().get("modelConfigured") is True
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
            "engine": "pi-v1",
            "available": available,
            "sandbox": sandbox,
            "rcs": bool(
                settings.paperqa_model
                and settings.paperqa_api_key.get_secret_value()
                and importlib.util.find_spec("paperqa")
            ),
            "protocol_version": 1,
            "profiles": ["agent", "fast_qa"],
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
        ]

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
        return {k: v for k, v in result.items() if k not in {"checkpoint", "evidence"}}

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
        async with store.transaction(user, str(rid)) as conn:
            run = await store.get(user, "run", str(rid), conn)
            conversation = await store.get(user, "conversation", run["conversation_id"], conn)
            conversation["queue_paused"] = True
            if run["status"] not in {"RUNNING", "QUEUED", "WAITING_APPROVAL"} and conversation.get(
                "active_run"
            ):
                successor = await store.get(user, "run", conversation["active_run"], conn)
                if successor.get("queued_from_run_id") == str(rid):
                    run = successor
            if run["status"] in {"RUNNING", "QUEUED", "WAITING_APPROVAL"}:
                if run["status"] == "RUNNING" and run.get("started_at"):
                    run["active_seconds"] += time.time() - run["started_at"]
                run["status"] = "CANCELLED"
                conversation["last_run"] = run["id"]
                conversation["checkpoint"] = run.get("checkpoint")
                conversation["evidence"] = run.get("evidence", {})
                for pending in run.get("messages", []):
                    if pending["seq"] > run.get("checkpoint_message_seq", 0) and not any(
                        item["id"] == pending["id"] for item in conversation["queued"]
                    ):
                        conversation["queued"].append(
                            {**pending, "status": "QUEUED", "delivery": "follow_up"}
                        )
                run.pop("pending_approval", None)
                await store.update(user, "run", run, conn)
                await store.event(user, run["id"], "run.cancelled", {}, conn)
                await conn.execute(
                    "UPDATE agent_runtime.harness_calls SET status='UNKNOWN',updated_at=now() WHERE run_id=%s AND status='RUNNING'",
                    (run["id"],),
                )
            await store.update(user, "conversation", conversation, conn)
        with contextlib.suppress(Exception):
            await asyncio.wait_for(service.runtime(run["id"], "abort"), timeout=3)
        return {"status": run["status"], "run_id": run["id"]}

    @app.post(PREFIX + "/runs/{rid}/resume", status_code=202)
    async def resume(rid: UUID, request: Request, user=Depends(principal)):
        body = await request.json()
        await service.authorized_topic(user, await store.get(user, "run", str(rid)))
        # Allow the original runtime to flush its tool result before a new epoch.
        for _ in range(50):
            snapshot = await store.get(user, "run", str(rid))
            if (
                snapshot["status"] != "WAITING_APPROVAL"
                or snapshot.get("pause_ready")
                or snapshot["lease_until"] < time.time()
            ):
                break
            await asyncio.sleep(0.1)
        async with store.transaction(user, str(rid)) as conn:
            run = await store.get(user, "run", str(rid), conn)
            if run["status"] == "WAITING_APPROVAL":
                approval = run.get("pending_approval", {})
                if (
                    body.get("approval_id") != approval.get("id")
                    or approval.get("expires_at", 0) <= time.time()
                ):
                    raise HTTPException(409, "Approval expired or parameters changed")
                if body.get("decision") not in {"APPROVED", "REJECTED"}:
                    raise HTTPException(422, "Explicit decision required")
                if not run.get("pause_ready") and run["lease_until"] >= time.time():
                    raise HTTPException(409, "Checkpoint is still being saved; retry shortly")
                run["approvals"][approval["id"]] = {
                    **approval,
                    "decision": body["decision"],
                    "actor_id": str(user.actor_id),
                }
                run.pop("pending_approval", None)
                run["request"] = (
                    "继续此前研究。外部检索的正式决策为 " + body["decision"] + "；仅在允许时调用原检索工具。"
                )
            elif run["status"] == "FAILED" and body.get("retry") is True:
                if not retryable_failure(run):
                    raise HTTPException(409, "Run requires review, not automatic replay")
                run.setdefault("original_request", run["request"])
                run["request"] = (
                    "从已保存进度继续原任务；先核对执行账本和现有产物，避免重复执行结果未知的操作。"
                )
            else:
                raise HTTPException(409, "Run cannot resume; stopped runs require a new linked run")
            run["status"] = "QUEUED"
            run["error"] = None
            run.pop("completion", None)
            run["principal"] = user.model_dump(mode="json")
            await store.update(user, "run", run, conn)
            await store.event(user, str(rid), "run.queued", {"resumed": True}, conn)
        return {"run_id": str(rid), "status": "QUEUED"}

    @app.get(PREFIX + "/runs/{rid}/workspace")
    async def workspace(rid: UUID, user=Depends(principal)):
        from .workspace import Workspace

        await service.authorized_topic(user, await store.get(user, "run", str(rid)), execution=False)
        return Workspace(settings.storage_root / "workspaces", str(user.organization_id), str(rid)).manifest()

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

        async def stream():
            nonlocal cursor
            while not await request.is_disconnected():
                run = await store.get(user, "run", str(rid))
                await service.authorized_topic(user, run, execution=False)
                batch = await store.events(user, str(rid), cursor)
                for event in batch:
                    cursor = event["seq"]
                    yield f"id: {cursor}\nevent: {event['type']}\ndata: {json.dumps(event, ensure_ascii=False)}\n\n"
                if not batch and run["status"] in {"SUCCEEDED", "FAILED", "CANCELLED"}:
                    return
                yield ": keepalive\n\n"
                await asyncio.sleep(0.5)

        return StreamingResponse(
            stream(),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )

    @app.post(PREFIX + "/runtime/{rid}/{action}")
    async def callback(rid: UUID, action: str, request: Request, authorization: str = Header(default="")):
        expected = "Bearer " + settings.runtime_secret.get_secret_value()
        if not hmac.compare_digest(expected.encode(), authorization.encode()):
            raise HTTPException(401, "Invalid runtime credential")
        raw = await request.body()
        if len(raw) > 20 * 1024 * 1024:
            raise HTTPException(413, "Runtime payload too large")
        # Principal is retrieved from the owned run, never accepted from a tool call.
        async with store.pool.connection() as conn:
            row = await (
                await conn.execute(
                    "SELECT payload FROM agent_runtime.harness_records WHERE kind='run' AND id=%s",
                    (str(rid),),
                )
            ).fetchone()
        if not row:
            raise HTTPException(404, "Run not found")
        user = Principal.model_validate(row["payload"]["principal"])
        return await service.callback(user, str(rid), action, json.loads(raw))

    return app


def public_run(run):
    return {
        **{k: v for k, v in run.items() if k not in {"checkpoint", "principal", "answer_context"}},
        **{
            key: normalize_citations(run[key], run.get("evidence", {}))
            for key in ("result", "last_text")
            if isinstance(run.get(key), str)
        },
        "assistant_messages": [
            {**message, "text": normalize_citations(message["text"], run.get("evidence", {}))}
            for message in run.get("assistant_messages", [])
        ],
        "retryable": retryable_failure(run),
    }
