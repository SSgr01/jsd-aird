"""Durable, host-independent scheduling, leases, budget gates and tool-call ledger."""

import asyncio
import hashlib
import json
import logging
import re
import time
from uuid import UUID, uuid4

import httpx
from fastapi import HTTPException
from psycopg.types.json import Jsonb

from aird_agent.core.capabilities import ExecutionContext
from aird_agent.core.contracts import digest
from aird_agent.core.messages import record_replies, requeue_unconsumed
from aird_agent.core.receipts import receipt
from aird_agent.core.state import initial_run_state
from aird_agent.core.workspace import Workspace

ACTIVE = {"QUEUED", "RUNNING", "WAITING_APPROVAL", "WAITING_CLARIFICATION"}
logger = logging.getLogger(__name__)


class Coordinator:
    def __init__(self, settings, store, *, registry, host, bindings, http=None):
        self.settings, self.store = settings, store
        self.http = http or httpx.AsyncClient(timeout=120, follow_redirects=False)
        self.host, self.packs, self.bindings = host, registry, bindings
        self.tool_limits = {}
        bindings.attach(self)

    async def authorized_topic(self, principal, run, *, execution=True):
        return await self.bindings.authorized_topic(principal, run, execution=execution)

    async def host_call(self, principal, action, arguments=None, run_id=None):
        return await self.host.call(principal, action, arguments or {}, run_id)

    def authorization_sources(self, sources):
        return self.bindings.authorization_sources(sources)

    def context_request(self, run):
        return self.bindings.context_request(run)

    async def authorize_conversation(self, principal, conversation):
        return await self.bindings.authorize_conversation(principal, conversation)

    def tools_for(self, run):
        return self.bindings.tools_for(run)

    def mutation_tools(self, run):
        return self.bindings.mutation_tools(run)

    async def runtime(self, rid, operation, body=None):
        response = await self.http.post(
            f"{self.settings.runtime_url}/runs/{rid}/{operation}",
            headers={"Authorization": "Bearer " + self.settings.runtime_secret.get_secret_value()},
            json=body or {},
        )
        if response.status_code >= 400:
            raise HTTPException(503, "PI_RUNTIME_UNAVAILABLE")
        return response.json()

    async def create_run(self, principal, request, key):
        payload, topic, legacy = await self.bindings.prepare_request(principal, request)

        async def create(conn):
            if request.conversation_id:
                conversation = await self.store.get(
                    principal, "conversation", str(request.conversation_id), conn
                )
                self.bindings.validate_conversation_scope(conversation, payload)
                if conversation.get("profile", "agent") != request.profile:
                    raise HTTPException(409, "CONVERSATION_PROFILE_CHANGED")
                if conversation.get("pack_versions") and conversation["pack_versions"] != payload.get(
                    "pack_versions"
                ):
                    if not request.upgrade_capabilities or set(conversation.get("pack_ids", [])) != set(
                        payload.get("pack_ids", [])
                    ):
                        raise HTTPException(409, "PACK_VERSION_MISMATCH")
                    conversation["pack_versions"] = payload["pack_versions"]
                if request.expected_revision != conversation["revision"]:
                    raise HTTPException(409, "Conversation revision changed")
                if conversation.get("active_run"):
                    current = await self.store.get(principal, "run", conversation["active_run"], conn)
                    if current["status"] in ACTIVE:
                        raise HTTPException(409, "Use steer or follow_up while a conversation is active")
            else:
                conversation = await self.store.create(
                    principal,
                    "conversation",
                    {
                        **self.bindings.conversation_metadata(payload),
                        "title": request.request[:80],
                        "messages": [],
                        "notes": [],
                        "queued": [],
                        "checkpoint": None,
                    },
                    conn,
                )
            checkpoint = conversation.get("checkpoint")
            evidence = conversation.get("evidence", {})
            if request.resume_from_run_id:
                previous = await self.store.get(principal, "run", str(request.resume_from_run_id), conn)
                if previous["conversation_id"] != conversation["id"]:
                    raise HTTPException(403, "Resume source belongs to another conversation")
                checkpoint = previous.get("checkpoint")
                evidence = previous.get("evidence", {})
                continuation_request = (
                    previous.get("original_request")
                    or (
                        previous.get("continuation_request")
                        if re.fullmatch(r"继续[。！!\s]*", previous["request"].strip())
                        else None
                    )
                    or previous["request"]
                )
            else:
                continuation_request = None
            scope_hash = self.bindings.scope_digest(topic)
            if conversation.get("scope_hash") != scope_hash or (
                request.resume_from_run_id and previous.get("scope_hash") != scope_hash
            ):
                checkpoint = None
                evidence = {}
            if request.profile == "fast_qa":
                checkpoint = None
            rebuild = bool(request.resume_from_run_id and request.resume_context == "rebuild" and checkpoint)
            if rebuild:
                # Discard model-generated conversation summaries, not authorized
                # source records. All evidence remains re-readable in workspace.
                checkpoint = None
            if not checkpoint and not rebuild:
                evidence = {}
            inherited = {}
            if (checkpoint or rebuild) and request.resume_from_run_id:
                # Coverage is an audited fact about this pinned publication, not
                # the new model's claimed progress. Never inherit completion.
                inherited = self.bindings.inherit(previous)
            run = await self.store.create(
                principal,
                "run",
                {
                    **payload,
                    **inherited,
                    "continuation_request": continuation_request,
                    "conversation_id": conversation["id"],
                    **initial_run_state(principal),
                    "engine_version": "pi-v1" if legacy else "unified-v1",
                    "evidence": evidence,
                    "checkpoint": checkpoint,
                    "scope_hash": scope_hash,
                    **self.bindings.initial_state(topic, evidence),
                },
                conn,
            )
            conversation["active_run"] = run["id"]
            if request.resume_queue:
                conversation["queue_paused"] = False
            conversation["scope_hash"] = scope_hash
            conversation["messages"].append(
                {"id": str(uuid4()), "role": "user", "text": request.request, "run_id": run["id"]}
            )
            await self.store.update(principal, "conversation", conversation, conn)
            await self.store.event(
                principal, run["id"], "run.queued", {"conversationId": conversation["id"]}, conn
            )
            return run

        return await self.store.once(principal, "create_run", key, payload, create)

    async def accept_message(self, principal, cid, request, key):
        conversation = await self.store.get(principal, "conversation", cid)
        await self.authorize_conversation(principal, conversation)
        if conversation.get("profile") == "fast_qa" and request.delivery == "steer":
            raise HTTPException(409, "FAST_QA_ACCEPTS_FOLLOW_UP_ONLY")

        async def accept(conn):
            conversation = await self.store.get(principal, "conversation", cid, conn)
            if conversation["revision"] != request.expected_revision:
                raise HTTPException(409, "Conversation revision changed")
            mid = str(uuid4())
            if not request.text.strip():
                raise HTTPException(422, "Message must not be blank")
            message = {"id": mid, "role": "user", "text": request.text, "delivery": request.delivery}
            if request.delivery == "follow_up":
                if not conversation.get("active_run"):
                    raise HTTPException(409, "Create a new run in this conversation")
                current = await self.store.get(principal, "run", conversation["active_run"], conn)
                if current["status"] not in {"RUNNING", "QUEUED"}:
                    raise HTTPException(409, "This run is not accepting follow-up messages")
                if len(conversation["queued"]) >= 50:
                    raise HTTPException(409, "MESSAGE_QUEUE_FULL")
                message["status"] = "QUEUED"
                conversation["queued"].append(message)
            elif conversation.get("active_run"):
                run = await self.store.get(principal, "run", conversation["active_run"], conn)
                if run["status"] not in {"RUNNING", "QUEUED"}:
                    raise HTTPException(409, "This run is not accepting steering messages")
                message["seq"] = len(run["messages"]) + 1
                message.update(status="PENDING", run_id=run["id"])
                run["messages"].append(message)
                run.pop("completion", None)
                await self.store.update(principal, "run", run, conn)
                await self.store.event(principal, run["id"], "message.accepted", message, conn)
            else:
                raise HTTPException(409, "Create a new run in this conversation")
            conversation["messages"].append(message)
            saved = await self.store.update(principal, "conversation", conversation, conn)
            await self.store.event(principal, conversation["active_run"], "conversation.queue", {}, conn)
            return {"message_id": mid, "revision": saved["revision"], "delivery": request.delivery}

        return await self.store.once(principal, "message:" + cid, key, request.model_dump(), accept)

    async def change_queue(self, principal, cid, request, key):
        conversation = await self.store.get(principal, "conversation", cid)
        await self.authorize_conversation(principal, conversation)
        if conversation.get("profile") == "fast_qa" and request.action == "promote":
            raise HTTPException(409, "FAST_QA_ACCEPTS_FOLLOW_UP_ONLY")

        async def change(conn):
            conversation = await self.store.get(principal, "conversation", cid, conn)
            if conversation["revision"] != request.expected_revision:
                raise HTTPException(409, "Conversation revision changed")
            queue = conversation.get("queued", [])
            mid = str(request.message_id)
            item = next((item for item in queue if item["id"] == mid), None)
            if item is None:
                raise HTTPException(409, "MESSAGE_ALREADY_DISPATCHED_OR_REMOVED")
            current_id = conversation.get("active_run")
            current = await self.store.get(principal, "run", current_id, conn) if current_id else None
            if request.action == "edit":
                item["text"] = request.text.strip()
                for message in conversation["messages"]:
                    if message["id"] == mid:
                        message["text"] = item["text"]
            elif request.action == "delete":
                queue.remove(item)
                conversation["messages"] = [
                    message for message in conversation["messages"] if message["id"] != mid
                ]
            elif request.action == "move":
                if request.position >= len(queue):
                    raise HTTPException(422, "Invalid queue position")
                queue.remove(item)
                queue.insert(request.position, item)
            else:
                if not current or current["status"] not in {"RUNNING", "QUEUED"}:
                    raise HTTPException(409, "RUN_NOT_ACCEPTING_STEERING")
                queue.remove(item)
                item.update(
                    delivery="steer", status="PENDING", run_id=current_id, seq=len(current["messages"]) + 1
                )
                current["messages"].append(dict(item))
                current.pop("completion", None)
                for message in conversation["messages"]:
                    if message["id"] == mid:
                        message.update(item)
                await self.store.update(principal, "run", current, conn)
                await self.store.event(principal, current_id, "message.accepted", item, conn)
            saved = await self.store.update(principal, "conversation", conversation, conn)
            if current_id:
                await self.store.event(principal, current_id, "conversation.queue", {}, conn)
            return {"message_id": mid, "revision": saved["revision"], "action": request.action}

        return await self.store.once(principal, "queue:" + cid, key, request.model_dump(mode="json"), change)

    async def gate(self, principal, rid, epoch):
        run = await self.store.get(principal, "run", rid)
        topic = await self.authorized_topic(principal, run)
        async with self.store.transaction(principal, rid) as conn:
            run = await self.active(principal, rid, epoch, conn)
            if run["model_calls"] >= self.settings.max_model_calls or run["steps"] >= self.settings.max_steps:
                raise HTTPException(409, "BUDGET_EXCEEDED")
            run["model_calls"] += 1
            run["steps"] += 1
            run["lease_until"] = time.time() + 180
            await self.store.update(principal, "run", run, conn)
            return {
                "stop": False,
                "messages": run["messages"],
                "notes": [
                    *topic.get("notes", []),
                    *(await self.store.get(principal, "conversation", run["conversation_id"], conn)).get(
                        "notes", []
                    ),
                ],
                "task_state": self.bindings.gate_state(run),
                "evidence_summaries": self.bindings.evidence_summaries(run),
            }

    async def active(self, principal, rid, epoch, conn):
        run = await self.store.get(principal, "run", rid, conn)
        if run["status"] != "RUNNING" or run["epoch"] != epoch or run["lease_until"] < time.time():
            raise HTTPException(409, "RUN_INACTIVE_OR_LEASE_LOST")
        if run["active_seconds"] + time.time() - run["started_at"] > self.settings.max_active_seconds:
            raise HTTPException(409, "BUDGET_EXCEEDED")
        return run

    async def tool(self, principal, rid, epoch, call_id, name, arguments):
        limit = self.tool_limits.setdefault(rid, asyncio.Semaphore(self.settings.parallelism))
        async with limit:
            return await self._tool(principal, rid, epoch, call_id, name, arguments)

    async def _tool(self, principal, rid, epoch, call_id, name, arguments):
        from jsonschema import ValidationError, validate

        run = await self.store.get(principal, "run", rid)
        spec = next((tool for tool in self.tools_for(run) if tool["name"] == name), None)
        if not spec or not isinstance(call_id, str) or not 1 <= len(call_id) <= 200:
            raise HTTPException(400, "Unknown tool or invalid call ID")
        try:
            validate(arguments, spec["parameters"])
        except ValidationError:
            raise HTTPException(422, "Invalid tool arguments") from None
        topic = await self.authorized_topic(principal, run)
        if run.get("profile") == "fast_qa" and (
            name != "retrieve_answer_context" or call_id != "fast-retrieval-1"
        ):
            raise HTTPException(403, "FAST_QA_TOOL_NOT_ALLOWED")
        await self.bindings.authorize_tool(principal, rid, name, arguments)
        signature = {"tool": name, "arguments": arguments, "scope": run["scope_hash"]}
        if run.get("pack_versions"):
            signature["pack_versions"] = run["pack_versions"]
        request_hash = digest(signature)
        async with self.store.transaction(principal, rid) as conn:
            run = await self.active(principal, rid, epoch, conn)
            prior = await (
                await conn.execute(
                    "SELECT * FROM agent_runtime.harness_calls WHERE run_id=%s AND call_id=%s", (rid, call_id)
                )
            ).fetchone()
            if prior:
                if prior["request_hash"] != request_hash:
                    raise HTTPException(409, "TOOL_CALL_CONFLICT")
                if prior["status"] in {"SUCCEEDED", "FAILED"}:
                    return prior["result"]
                raise HTTPException(409, "TOOL_RESULT_UNKNOWN: do not blindly replay")
            if name in self.mutation_tools(run):
                unknown = await (
                    await conn.execute(
                        "SELECT c.call_id FROM agent_runtime.harness_calls c JOIN agent_runtime.harness_records r ON r.id=c.run_id AND r.kind='run' "
                        "WHERE r.organization_id=%s AND r.actor_id=%s AND r.payload->>'conversation_id'=%s "
                        "AND c.status='UNKNOWN' AND (c.tool_name=ANY(%s) OR c.tool_name='legacy-unknown') LIMIT 1",
                        (*principal.key, run["conversation_id"], sorted(self.mutation_tools(run))),
                    )
                ).fetchone()
                if unknown:
                    raise HTTPException(
                        409, "TOOL_RESULT_UNKNOWN: equivalent operation requires reconciliation"
                    )
            if run["tool_calls"] >= self.settings.max_tool_calls:
                raise HTTPException(409, "BUDGET_EXCEEDED")
            run["tool_calls"] += 1
            run["lease_until"] = time.time() + 180
            await self.store.update(principal, "run", run, conn)
            await conn.execute(
                "INSERT INTO agent_runtime.harness_calls(run_id,call_id,request_hash,tool_name,status,arguments,execution_epoch) VALUES(%s,%s,%s,%s,'RUNNING',%s,%s)",
                (rid, call_id, request_hash, name, Jsonb(arguments), epoch),
            )
            if name in self.bindings.activities:
                await self.store.event(
                    principal,
                    rid,
                    self.bindings.activity_event,
                    {
                        "title": self.bindings.activities[name],
                        "status": "running",
                        "call_id": call_id,
                        "detail": arguments.get("query", arguments.get("question", ""))[:200],
                    },
                    conn,
                )
        outcome = None
        registered_output = None
        authority_result = None
        try:
            handler = self.packs.handler(run["pack_ids"], name) if run.get("pack_versions") else None
            if handler:
                result = await handler(
                    ExecutionContext(principal, run, topic, self.host_call, self), arguments
                )
                result = await self.remember_sources(principal, run, result)
            else:
                result = await self.dispatch(principal, run, topic, name, arguments, call_id)
            registered_output = self.bindings.output_receipt(run, name, arguments, result)
            authority_result = result
            result = self.bounded_result(principal, run, result, call_id)
        except Exception as exc:
            if not isinstance(exc, HTTPException):
                logger.exception("Harness tool failed: %s", name)
            detail = exc.detail if isinstance(exc, HTTPException) else "Tool execution failed"
            mutation = name in self.mutation_tools(run)
            known_denial = isinstance(exc, HTTPException) and (
                exc.status_code < 500 or str(detail).startswith("SANDBOX_UNAVAILABLE:")
            )
            if mutation and not known_denial and not isinstance(exc, httpx.ConnectError):
                # A lost response is not proof that the remote transaction did not commit.
                outcome = "UNKNOWN"
                result = {"error": "TOOL_RESULT_UNKNOWN", "retryable": False, "requires_reconciliation": True}
            else:
                result = {"error": str(detail), "retryable": False}
                result.update(self.bindings.error_info(exc))
        async with self.store.transaction(principal, rid) as conn:
            current = await self.store.get(principal, "run", rid, conn)
            if (
                current["epoch"] != epoch
                or current["status"] not in {"RUNNING", "WAITING_APPROVAL"}
                or current["lease_until"] < time.time()
            ):
                raise HTTPException(409, "RUN_INACTIVE_OR_LEASE_LOST")
            await conn.execute(
                "UPDATE agent_runtime.harness_calls SET status=%s,result=%s,updated_at=now() WHERE run_id=%s AND call_id=%s",
                (outcome or ("FAILED" if "error" in result else "SUCCEEDED"), Jsonb(result), rid, call_id),
            )
            if not outcome and "error" not in result and name in self.mutation_tools(run):
                await receipt(self.store, principal, rid, call_id, "tool-result", authority_result, conn)
            if registered_output:
                current.setdefault("registered_outputs", []).append(registered_output)
                current.pop("completion", None)
                await self.store.update(principal, "run", current, conn)
            if name in self.bindings.activities:
                await self.store.event(
                    principal,
                    rid,
                    self.bindings.activity_event,
                    {
                        "title": self.bindings.activities[name],
                        "status": "failed" if "error" in result else "done",
                        "call_id": call_id,
                        "candidates": len(result.get("candidates", [])),
                    },
                    conn,
                )
        return result

    @property
    def tool_character_budget(self):
        budget = min(64000, self.settings.model_context_window - self.settings.model_output_tokens - 4096)
        return min(16000, max(1024, budget // 4))

    def bounded_result(self, principal, run, result, call_id):
        return self.bindings.bounded_result(principal, run, result, call_id)

    async def remember_sources(self, principal, run, result):
        return await self.bindings.remember_sources(principal, run, result)

    async def dispatch(self, principal, run, topic, name, args, call_id):
        return await self.bindings.dispatch(principal, run, topic, name, args, call_id)

    async def claim(self):
        async with self.store.pool.connection() as capacity, capacity.transaction():
            await capacity.execute("SELECT pg_advisory_xact_lock(1847091011)")
            row = await (
                await capacity.execute(
                    "SELECT count(*) AS running FROM agent_runtime.harness_records WHERE kind='run' AND payload->>'status'='RUNNING' AND (payload->>'lease_until')::double precision>=%s",
                    (time.time(),),
                )
            ).fetchone()
            if row["running"] >= self.settings.max_running_runs:
                return None
            return await self._claim()

    async def _claim(self):

        async with self.store.pool.connection() as conn:
            rows = await (
                await conn.execute(
                    "SELECT id,payload FROM agent_runtime.harness_records WHERE kind='run' AND "
                    "(payload->>'status'='QUEUED' OR (payload->>'status'='RUNNING' AND (payload->>'lease_until')::double precision<%s)) "
                    "ORDER BY created_at LIMIT 10",
                    (time.time(),),
                )
            ).fetchall()
        for row in rows:
            principal = self.bindings.restore_principal(row["payload"]["principal"])
            async with self.store.transaction(principal, str(row["id"])) as conn:
                run = await self.store.get(principal, "run", str(row["id"]), conn)
                if run["status"] != "QUEUED" and not (
                    run["status"] == "RUNNING" and run["lease_until"] < time.time()
                ):
                    continue
                if run["started_at"] and run["status"] == "RUNNING":
                    run["active_seconds"] += max(0, min(time.time(), run["lease_until"]) - run["started_at"])
                await conn.execute(
                    "UPDATE agent_runtime.harness_calls SET status='UNKNOWN',updated_at=now() WHERE run_id=%s AND status='RUNNING'",
                    (run["id"],),
                )
                run.update(
                    status="RUNNING",
                    workspace_expired=False,
                    epoch=run["epoch"] + 1,
                    started_at=time.time(),
                    lease_until=time.time() + 180,
                )
                saved = await self.store.update(principal, "run", run, conn)
                return principal, saved
        return None

    async def worker(self):
        last_cleanup = 0
        while True:
            try:
                if time.monotonic() - last_cleanup >= 60:
                    await self.cleanup_workspaces()
                    last_cleanup = time.monotonic()
                claim = await self.claim()
            except Exception:
                logger.exception("Harness claim failed; retrying without discarding durable work")
                await asyncio.sleep(2)
                continue
            if not claim:
                await asyncio.sleep(0.5)
                continue
            principal, run = claim
            try:
                topic = await self.authorized_topic(principal, run)
                run = await self.bindings.prepare_run(principal, run)
                workspace = Workspace(
                    self.settings.storage_root / "workspaces", str(principal.organization_id), run["id"]
                )
                workspace.prepare()
                workspace.materialize(
                    "input/context.json" if run.get("pack_versions") else "input/topic.json",
                    json.dumps(self.bindings.input_context(run, topic), ensure_ascii=False).encode(),
                )
                for eid, candidate in run["evidence"].items():
                    workspace.materialize(
                        "input/evidence/" + eid + ".json", json.dumps(candidate, ensure_ascii=False).encode()
                    )
                await self.runtime(
                    run["id"],
                    "start",
                    {
                        "id": run["id"],
                        "epoch": run["epoch"],
                        "request": run.get("prepared_qa", {}).get("prompt", run["request"]),
                        "checkpoint": None
                        if run.get("profile") == "fast_qa"
                        else await self.store.checkpoint(principal, run["checkpoint"]),
                        "contextWindow": self.settings.model_context_window,
                        "outputTokens": self.settings.model_output_tokens,
                        "tools": [] if run.get("profile") == "fast_qa" else self.tools_for(run),
                        "initialTools": self.bindings.initial_tools(run),
                        "profile": run.get("profile", "agent"),
                        **({"instructions": self.instructions_for(run)} if run.get("pack_versions") else {}),
                        "messageSeq": run.get("checkpoint_message_seq", 0),
                        "parallelism": self.settings.parallelism,
                        "topic": await self.bindings.runtime_topic(principal, run, topic),
                    },
                )
            except Exception:
                async with self.store.transaction(principal, run["id"]) as conn:
                    current = await self.store.get(principal, "run", run["id"], conn)
                    if current["epoch"] != run["epoch"] or current["status"] != "RUNNING":
                        continue
                    current.update(status="FAILED", error="RUNTIME_OR_AUTHORIZATION_UNAVAILABLE")
                    await self.store.update(principal, "run", current, conn)
                    conversation = await self.store.get(
                        principal, "conversation", current["conversation_id"], conn
                    )
                    requeue_unconsumed(current, conversation)
                    record_replies(current, conversation)
                    conversation.update(queue_paused=True, last_run=current["id"])
                    if conversation.get("active_run") == current["id"]:
                        conversation["active_run"] = None
                    await self.store.update(principal, "conversation", conversation, conn)
                    await self.store.event(
                        principal, run["id"], "run.failed", {"code": current["error"]}, conn
                    )

    async def prepare_fast_qa(self, principal, run):
        return await self.bindings.prepare_fast_qa(principal, run)

    def instructions_for(self, run):
        return self.bindings.instructions_for(run)

    def qa_history(self, conversation, run):
        return self.bindings.qa_history(conversation, run)

    async def authorize_history(self, principal, conversation):
        return await self.bindings.authorize_history(principal, conversation)

    async def cleanup_workspaces(self):

        async with self.store.pool.connection() as conn:
            expired = await (
                await conn.execute(
                    "SELECT id,payload FROM agent_runtime.harness_records WHERE kind='run' "
                    "AND payload->>'status'='WAITING_APPROVAL' AND (payload->'pending_approval'->>'expires_at')::double precision<=%s LIMIT 100",
                    (time.time(),),
                )
            ).fetchall()
        for row in expired:
            principal = self.bindings.restore_principal(row["payload"]["principal"])
            rid = str(row["id"])
            async with self.store.transaction(principal, rid) as conn:
                run = await self.store.get(principal, "run", rid, conn)
                if (
                    run["status"] != "WAITING_APPROVAL"
                    or run.get("pending_approval", {}).get("expires_at", float("inf")) > time.time()
                ):
                    continue
                run.update(status="CANCELLED", error="APPROVAL_EXPIRED")
                run.pop("pending_approval", None)
                await conn.execute(
                    "UPDATE agent_runtime.harness_calls SET status='UNKNOWN',updated_at=now() WHERE run_id=%s AND status='RUNNING'",
                    (rid,),
                )
                await self.store.update(principal, "run", run, conn)
                await self.store.event(principal, rid, "run.cancelled", {"reason": "APPROVAL_EXPIRED"}, conn)
        async with self.store.pool.connection() as conn:
            rows = await (
                await conn.execute(
                    "SELECT id,payload FROM agent_runtime.harness_records WHERE kind='run' "
                    "AND payload->>'status' IN ('SUCCEEDED','FAILED','CANCELLED') "
                    "AND coalesce(payload->>'workspace_expired','false')='false' "
                    "AND updated_at < now() - (%s * interval '1 day') LIMIT 100",
                    (self.settings.workspace_retention_days,),
                )
            ).fetchall()
        for row in rows:
            principal = self.bindings.restore_principal(row["payload"]["principal"])
            rid = str(row["id"])
            async with self.store.transaction(principal, rid) as conn:
                run = await self.store.get(principal, "run", rid, conn)
                if run["status"] not in {"SUCCEEDED", "FAILED", "CANCELLED"} or run.get("workspace_expired"):
                    continue
                workspace = Workspace(
                    self.settings.storage_root / "workspaces", str(principal.organization_id), rid
                )
                await asyncio.to_thread(workspace.discard)
                run["workspace_expired"] = True
                await self.store.update(principal, "run", run, conn)
                await self.store.event(
                    principal, rid, "workspace.expired", {"authority_preserved": True}, conn
                )

    async def callback(self, principal, rid, action, body):
        epoch = body.get("epoch")
        if action == "gate":
            return await self.gate(principal, rid, epoch)
        if action == "tool":
            return await self.tool(principal, rid, epoch, body["call_id"], body["name"], body["arguments"])
        if action in {"heartbeat", "settled", "auxiliary", "review"}:
            current = await self.store.get(principal, "run", rid)
            await self.authorized_topic(principal, current)
        async with self.store.transaction(principal, rid) as conn:
            run = await self.store.get(principal, "run", rid, conn)
            if (
                run["epoch"] != epoch
                or run["status"] not in {"RUNNING", "WAITING_APPROVAL"}
                or run["lease_until"] < time.time()
            ):
                raise HTTPException(409, "RUN_INACTIVE_OR_LEASE_LOST")
            run["lease_until"] = time.time() + 180
            if action == "review":
                output_valid = "text" not in body or self.bindings.validate_output(run, body["text"])[1]
                return {
                    "messages": run["messages"],
                    "ready": (
                        bool(run.get("completion"))
                        and len(run["messages"]) <= run.get("checkpoint_message_seq", 0)
                        and output_valid
                    )
                    or run["status"] == "WAITING_APPROVAL",
                    "repair_output": not output_valid,
                    "hint": "最终回复缺少有效来源引用。只修正交付摘要，使用本次已获授权的真实[E:evidence_id]引用；保留已保存成果，不重复检索、计算、保存报告或执行自检。"
                    if not output_valid else "交付前用 finish_task 检查需求和成果；有缺口时如实标记 PARTIAL/NEEDS_INPUT。",
                }
            if action == "auxiliary":
                if run["status"] != "RUNNING" or run["model_calls"] >= self.settings.max_model_calls:
                    raise HTTPException(409, "BUDGET_EXCEEDED")
                run["model_calls"] += 1
                await self.store.event(
                    principal,
                    rid,
                    "model.auxiliary",
                    {"purpose": body.get("purpose", "context_compaction")},
                    conn,
                )
            elif action == "heartbeat":
                if (
                    run["status"] == "RUNNING"
                    and run["active_seconds"] + time.time() - run["started_at"]
                    > self.settings.max_active_seconds
                ):
                    raise HTTPException(409, "BUDGET_EXCEEDED")
            elif action == "checkpoint":
                content = body.get("content", "")
                if not isinstance(content, str) or len(content.encode()) > 16 * 1024 * 1024:
                    raise HTTPException(413, "Checkpoint too large")
                for line in content.splitlines():
                    json.loads(line)
                sha256 = hashlib.sha256(content.encode()).hexdigest()
                await conn.execute(
                    "INSERT INTO agent_runtime.harness_checkpoints(organization_id,actor_id,sha256,content) "
                    "VALUES(%s,%s,%s,%s) ON CONFLICT DO NOTHING",
                    (*principal.key, sha256, content),
                )
                run["checkpoint"] = {"sha256": sha256}
                seq = body.get("message_seq", 0)
                if not isinstance(seq, int) or not 0 <= seq <= len(run["messages"]):
                    raise HTTPException(422, "Invalid checkpoint message sequence")
                run["checkpoint_message_seq"] = seq
            elif action == "event":
                kind = body["type"]
                if not re.fullmatch(r"[a-z.]+", kind):
                    raise HTTPException(400, "Invalid event type")
                if kind == "message.completed":
                    after_message_seq = body["payload"].get("after_message_seq", 0)
                    if not isinstance(after_message_seq, int) or not 0 <= after_message_seq <= len(
                        run["messages"]
                    ):
                        raise HTTPException(422, "Invalid assistant message sequence")
                    run["last_text"] = self.bindings.normalize_output(
                        body["payload"].get("text", ""), run["evidence"]
                    )
                    body["payload"]["text"] = run["last_text"]
                    # Current workers keep one identity from start through completion.
                    # Older workers omit it and still receive a generated identity.
                    body["payload"]["id"] = (
                        str(UUID(body["payload"]["id"])) if body["payload"].get("id") else str(uuid4())
                    )
                    if run["last_text"].strip():
                        run["assistant_messages"] = [
                            *run.get("assistant_messages", [])[-19:],
                            {
                                "id": body["payload"]["id"],
                                "text": run["last_text"],
                                "after_message_seq": after_message_seq,
                            },
                        ]
                elif kind == "message.applied":
                    mid = body["payload"].get("message_id")
                    item = next((item for item in run["messages"] if item["id"] == mid), None)
                    if item is None:
                        raise HTTPException(422, "Unknown steering message")
                    item["status"] = "DELIVERED"
                    conversation = await self.store.get(
                        principal, "conversation", run["conversation_id"], conn
                    )
                    for message in conversation["messages"]:
                        if message["id"] == mid:
                            message["status"] = "DELIVERED"
                    await self.store.update(principal, "conversation", conversation, conn)
                await self.store.event(principal, rid, kind, body["payload"], conn)
            elif action in {"settled", "failed"}:
                if run["status"] == "WAITING_APPROVAL":
                    run["pause_ready"] = True
                    await self.store.update(principal, "run", run, conn)
                    await self.store.event(principal, rid, "approval.ready", {}, conn)
                    return {"waiting": True}
                run["active_seconds"] += time.time() - run["started_at"]
                text, valid = self.bindings.validate_output(run, body.get("text", ""))
                unresolved = await (
                    await conn.execute(
                        "SELECT 1 FROM agent_runtime.harness_calls WHERE run_id=%s AND status='UNKNOWN' "
                        "AND (tool_name=ANY(%s) OR tool_name='legacy-unknown') LIMIT 1",
                        (rid, list(self.mutation_tools(run))),
                    )
                ).fetchone()
                run["status"] = "SUCCEEDED" if action == "settled" and valid and not unresolved else "FAILED"
                run["result"] = text if valid else ""
                if not run.get("completion"):
                    run["completion"] = self.bindings.default_completion(run)
                run["error"] = (
                    "SIDE_EFFECT_RECONCILIATION_REQUIRED"
                    if unresolved
                    else body.get("code")
                    if valid
                    else "CITATION_INVALID"
                )
                conversation = await self.store.get(principal, "conversation", run["conversation_id"], conn)
                conversation["checkpoint"] = run["checkpoint"]
                conversation["evidence"] = run["evidence"]
                conversation["last_run"] = rid
                if conversation.get("active_run") == rid:
                    conversation["active_run"] = None
                # A steer arriving after the last model boundary must not disappear.
                requeue_unconsumed(run, conversation)
                record_replies(run, conversation)
                if run["status"] == "FAILED":
                    conversation["queue_paused"] = True
                if (
                    run["status"] == "SUCCEEDED"
                    and conversation.get("queued")
                    and not conversation.get("queue_paused")
                ):
                    follow_up = conversation["queued"].pop(0)
                    next_run = await self.store.create(
                        principal,
                        "run",
                        {
                            **{
                                k: v
                                for k, v in run.items()
                                if k
                                not in {
                                    "id",
                                    "revision",
                                    "created_at",
                                    "updated_at",
                                    "result",
                                    "pending_approval",
                                    "pause_ready",
                                    "answer_context",
                                    "retrieval_trace",
                                }
                            },
                            **initial_run_state(principal),
                            "engine_version": run["engine_version"],
                            "evidence": run["evidence"],
                            "checkpoint": run.get("checkpoint"),
                            "request": follow_up["text"],
                            "queued_from_run_id": rid,
                            "status": "QUEUED",
                            "epoch": 0,
                            "lease_until": 0,
                            "model_calls": 0,
                            "tool_calls": 0,
                            "steps": 0,
                            "active_seconds": 0,
                            "plan": None,
                            "completion": None,
                            **(
                                {"checkpoint": None, "evidence": {}, "source_refs": []}
                                if run.get("profile") == "fast_qa"
                                else {}
                            ),
                        },
                        conn,
                    )
                    conversation["active_run"] = next_run["id"]
                    conversation["messages"] = [
                        message for message in conversation["messages"] if message["id"] != follow_up["id"]
                    ]
                    conversation["messages"].append(
                        {**follow_up, "status": "DISPATCHED", "run_id": next_run["id"]}
                    )
                    await self.store.event(
                        principal, next_run["id"], "run.queued", {"follow_up_to": rid}, conn
                    )
                await self.store.update(principal, "conversation", conversation, conn)
                await self.store.event(
                    principal,
                    rid,
                    "run." + run["status"].lower(),
                    {"text": run.get("result", ""), "error": run["error"]},
                    conn,
                )
            else:
                raise HTTPException(404, "Unknown runtime callback")
            await self.store.update(principal, "run", run, conn)
            return {"accepted": True}
