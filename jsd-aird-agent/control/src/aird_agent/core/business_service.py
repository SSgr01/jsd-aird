"""Enterprise business authorization and operation ledger; DeepSeek owns execution and Inbox."""
import asyncio
import json
import logging
import re
import time
from uuid import uuid4

import httpx
from fastapi import HTTPException
from psycopg.types.json import Jsonb

from aird_agent.core.capabilities import ExecutionContext
from aird_agent.core.contracts import digest
from aird_agent.core.receipts import receipt
from aird_agent.core.state import initial_run_state
from aird_agent.server.contracts import RunRequest
from aird_agent.server.security import issue

ACTIVE = {"QUEUED", "RUNNING", "WAITING_APPROVAL", "WAITING_CLARIFICATION"}
logger = logging.getLogger(__name__)

class BusinessService:
    def __init__(self, settings, store, *, registry, host, bindings, http=None):
        self.settings, self.store = settings, store
        # This client only carries internal service requests and delegation tickets.
        self.http = http or httpx.AsyncClient(timeout=120, follow_redirects=False, trust_env=False)
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


    async def tool(self, principal, rid, epoch, call_id, name, arguments, *, native=False):
        limit = self.tool_limits.setdefault(rid, asyncio.Semaphore(self.settings.parallelism))
        async with limit:
            return await self._tool(principal, rid, epoch, call_id, name, arguments, native=native)


    async def _tool(self, principal, rid, epoch, call_id, name, arguments, *, native=False):
        from jsonschema import ValidationError, validate

        run = await self.store.get(principal, "run", rid)
        if native:
            from aird_agent.hosts.jsd.native_files import native_spec
            required_pack = "retrieval" if name == "dsh_web_search" else "files"
            if run.get("profile") != "agent" or required_pack not in run.get("pack_ids", []):
                raise HTTPException(403, "CAPABILITY_NOT_AUTHORIZED")
            if name == "dsh_web_search":
                from aird_agent.hosts.jsd.native_web import search_spec
                spec = search_spec()
            else:
                spec = native_spec(name)
        else:
            spec = next((tool for tool in self.tools_for(run) if tool["name"] == name), None)
        if not spec or not isinstance(call_id, str) or not 1 <= len(call_id) <= 200:
            raise HTTPException(400, "Unknown tool or invalid call ID")
        try:
            validate(arguments, spec["parameters"])
        except ValidationError as error:
            # Describe the public contract, never echo submitted values or a schema traceback.
            detail = {"code": "INVALID_TOOL_ARGUMENTS", "path": list(error.absolute_path),
                      "rule": error.validator, "expected": error.validator_value}
            if error.validator == "additionalProperties" and isinstance(error.instance, dict):
                detail["unexpected_fields"] = sorted(set(error.instance) - set(error.schema.get("properties", {})))[:20]
            raise HTTPException(422, detail) from None
        topic = await self.authorized_topic(principal, run)
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
                        "AND c.status='UNKNOWN' AND c.tool_name=ANY(%s) LIMIT 1",
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
            if native and name == "dsh_web_search":
                from aird_agent.hosts.jsd.native_web import search
                result = await search(self, principal, run, arguments)
            elif native:
                from aird_agent.hosts.jsd.native_files import native_execute, native_artifact
                result = await (native_execute if name == "dsh_execute" else native_artifact)(self, principal, run, arguments, call_id)
            elif handler:
                result = await handler(
                    ExecutionContext(principal, run, topic, self.host_call, self, call_id), arguments
                )
                result = await self.remember_sources(principal, run, result)
            else:
                result = await self.dispatch(principal, run, topic, name, arguments, call_id)
            registered_output = self.bindings.output_receipt(run, name, arguments, result)
            authority_result = result
            result = result if native else self.bounded_result(principal, run, result, call_id)
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
                logger.warning("Business outcome requires reconciliation run=%s call=%s tool=%s status=%s failure=%s",
                               rid, call_id, name, getattr(exc, "status_code", None),
                               str(detail).split(":", 1)[0] if isinstance(detail, str) and re.fullmatch(r"[A-Z_]+(?::.*)?", detail) else type(exc).__name__)
                result = {"error": "TOOL_RESULT_UNKNOWN", "retryable": False, "requires_reconciliation": True}
            else:
                result = {"error": str(detail), "retryable": False}
                result.update(self.bindings.error_info(exc))
        async with self.store.transaction(principal, rid) as conn:
            current = await self.store.get(principal, "run", rid, conn)
            if (
                current["epoch"] != epoch
                or current["status"] not in {"RUNNING", "WAITING_APPROVAL"}
            ):
                raise HTTPException(409, "RUN_INACTIVE_OR_LEASE_LOST")
            if not native and any(key in result for key in ("candidates", "evidence_id", "quotes")):
                from aird_agent.evidence.links import citation_links
                links = citation_links(current.get("evidence", {}), self.settings.public_url)
                if links:
                    result = {**result, "citation_links": links}
            await conn.execute(
                "UPDATE agent_runtime.harness_calls SET status=%s,result=%s,updated_at=now() WHERE run_id=%s AND call_id=%s",
                (outcome or ("FAILED" if "error" in result else "SUCCEEDED"), Jsonb(result), rid, call_id),
            )
            if not outcome and "error" not in result and name in self.mutation_tools(run):
                await receipt(self.store, principal, rid, call_id, "tool-result", authority_result, conn)
            if result.get("activate_tools"):
                allowed = {tool["name"] for tool in self.tools_for(current)}
                current["active_tools"] = sorted(set(current.get("active_tools", self.bindings.initial_tools(current)))
                    | (set(result["activate_tools"]) & allowed))
                await self.store.update(principal, "run", current, conn)
            if registered_output:
                current.setdefault("registered_outputs", []).append(registered_output)
                current.pop("completion", None)
                await self.store.update(principal, "run", current, conn)
            if not outcome and "error" not in result and name in {
                "get_project_snapshot", "list_project_stages", "list_project_tasks",
                "get_task_detail", "get_experiment_record",
            } and authority_result.get("candidates"):
                current["business_records_read"] = True
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


    def instructions_for(self, run):
        return self.bindings.instructions_for(run)


    async def authorize_history(self, principal, conversation):
        return await self.bindings.authorize_history(principal, conversation)


    async def active(self, principal, rid, epoch, conn):
        run = await self.store.get(principal, "run", rid, conn)
        if run["epoch"] != epoch or run["status"] not in {"RUNNING", "WAITING_APPROVAL"}:
            raise HTTPException(409, "RUN_INACTIVE")
        return run

    async def runtime(self, rid, operation, body=None):
        """Business commands target the native DeepSeek Session."""
        async with self.store.pool.connection() as conn:
            row = await (await conn.execute(
                "SELECT payload FROM agent_runtime.harness_records WHERE kind='run' AND id=%s", (rid,)
            )).fetchone()
        if not row:
            raise HTTPException(404, "Run not found")
        from aird_agent.core.contracts import Principal
        run = row["payload"]
        principal = Principal.model_validate(run["principal"])
        if run.get("engine_version") != "dsh-v1":
            raise HTTPException(409, "DSH_SESSION_REQUIRED")
        action = {"abort": "cancel"}.get(operation, operation)
        return await self.dsh_command(principal, action, {
            "run_id": rid, "session_id": run.get("dsh_session_id"), "profile": run["profile"], **(body or {})
        })

    async def dsh_command(self, principal, action, body):
        ticket = issue(self.settings.shared_secret.get_secret_value(), principal, "java-tools")
        try:
            response = await self.http.post(self.settings.runtime_url + "/internal/" + action,
                headers={"Authorization": "Bearer " + self.settings.runtime_secret.get_secret_value()},
                json={**body, "ticket": ticket}, timeout=120)
        except httpx.HTTPError:
            raise HTTPException(503, "DSH_RUNTIME_UNAVAILABLE") from None
        if response.status_code >= 400:
            raise HTTPException(response.status_code, "DSH_COMMAND_REFUSED")
        return response.json()

    async def create_run(self, principal, request, key, *, dispatch=True):
        payload, topic = await self.bindings.prepare_request(principal, request)
        from aird_agent.evidence.registry import register_candidates
        evidence = {}
        if request.conversation_id:
            conversation = await self.store.get(principal, "conversation", str(request.conversation_id))
            # Native messages can refer to evidence from any earlier turn. Recheck
            # its current authorization, while keeping request filters independent.
            snapshots = await self.authorize_history(principal, conversation)
            pinned = (payload.get("retrieval_scope") or {}).get("knowledge_documents")
            allowed = {(item["document_id"], item["publication_id"]) for item in pinned or []}
            for snapshot in snapshots.values():
                candidates = snapshot.get("evidence", {}).values()
                if allowed:
                    candidates = [candidate for candidate in candidates
                        if (candidate["source"].get("document_id"), candidate["source"].get("publication_id")) in allowed]
                evidence = register_candidates(evidence, candidates)
        evidence = register_candidates(evidence, topic.get("context_candidates", []))

        async def save(conn):
            previous = None
            if request.conversation_id:
                conversation = await self.store.get(principal, "conversation", str(request.conversation_id), conn)
                if conversation.get("engine_version") != "dsh-v1":
                    raise HTTPException(409, "DSH_SESSION_REQUIRED")
                self.bindings.validate_conversation_scope(conversation, payload)
                if conversation.get("profile", "agent") != request.profile:
                    raise HTTPException(409, "CONVERSATION_PROFILE_CHANGED")
                if request.expected_revision is not None and request.expected_revision != conversation["revision"]:
                    raise HTTPException(409, "Conversation revision changed")
                if conversation.get("active_run"):
                    previous = await self.store.get(principal, "run", conversation["active_run"], conn)
                    if previous["status"] in {"RUNNING", "QUEUED", "WAITING_APPROVAL"}:
                        raise HTTPException(409, "Use native Inbox while a conversation is active")
                if conversation.get("last_run"):
                    previous = await self.store.get(principal, "run", conversation["last_run"], conn)
            else:
                conversation = await self.store.create(principal, "conversation", {
                    **self.bindings.conversation_metadata(payload), "engine_version": "dsh-v1",
                    "title": request.request[:80], "messages": [], "notes": [],
                }, conn)
            turn_evidence = register_candidates({}, topic.get("context_candidates", []))
            inherited = {"registered_outputs": previous.get("registered_outputs", [])} if previous else {}
            run = await self.store.create(principal, "run", {
                **payload, **initial_run_state(principal), **inherited,
                "engine_version": "dsh-v1", "conversation_id": conversation["id"],
                "scope_hash": self.bindings.scope_digest(topic), "evidence": evidence,
                "turn_evidence_ids": list(turn_evidence),
                **self.bindings.initial_state(topic, evidence),
                "dsh_session_id": conversation.get("dsh_session_id"), "dsh_version": "0.2.2-rc.1",
            }, conn)
            conversation.update(engine_version="dsh-v1", active_run=run["id"], scope_hash=run["scope_hash"])
            conversation["messages"].append({"id": str(uuid4()), "role": "user", "text": request.request, "run_id": run["id"]})
            await self.store.update(principal, "conversation", conversation, conn)
            await self.store.event(principal, run["id"], "run.queued", {"conversationId": conversation["id"]}, conn)
            return run

        run = await self.store.once(principal, "create_run", key, payload, save)
        if dispatch:
            await self.launch(principal, run["id"])
            run = await self.store.get(principal, "run", run["id"])
        return run

    async def launch(self, principal, rid):
        run = await self.store.get(principal, "run", rid)
        await self.authorized_topic(principal, run, execution=False)
        if run.get("dsh_dispatched"):
            return run
        # A known UUID lets the native request log deduplicate an uncertain prompt acceptance.
        if not run.get("dsh_session_id"):
            allocated = await self.dsh_command(principal, "create", {
                "run_id": rid, "profile": run["profile"], "session_id": rid,
            })
            await self.bind_session(principal, rid, allocated["sessionId"])
        run = await self.store.get(principal, "run", rid)
        await self.dsh_command(principal, "prompt", {"run_id": rid, "profile": run["profile"],
            "session_id": run["dsh_session_id"], "request_id": rid, "text": run["request"]})
        async with self.store.transaction(principal, rid) as conn:
            current = await self.store.get(principal, "run", rid, conn)
            current["dsh_dispatched"] = True
            await self.store.update(principal, "run", current, conn)
        return current

    async def bind_session(self, principal, rid, sid):
        async with self.store.transaction(principal, rid) as conn:
            run = await self.store.get(principal, "run", rid, conn)
            if run.get("engine_version") != "dsh-v1" or run.get("dsh_session_id") not in {None, sid}:
                raise HTTPException(409, "SESSION_BINDING_CONFLICT")
            conversation = await self.store.get(principal, "conversation", run["conversation_id"], conn)
            if conversation.get("dsh_session_id") not in {None, sid}:
                raise HTTPException(409, "SESSION_BINDING_CONFLICT")
            run["dsh_session_id"] = sid
            conversation["dsh_session_id"] = sid
            await self.store.update(principal, "run", run, conn)
            await self.store.update(principal, "conversation", conversation, conn)
        return {"accepted": True}

    async def session_conversation(self, principal, sid):
        async with self.store.pool.connection() as conn:
            row = await (await conn.execute(
                "SELECT id FROM agent_runtime.harness_records WHERE kind='conversation' AND organization_id=%s "
                "AND actor_id=%s AND payload->>'dsh_session_id'=%s", (*principal.key, sid)
            )).fetchone()
        return await self.store.get(principal, "conversation", str(row["id"])) if row else None

    async def session_run(self, principal, body):
        run = await self.store.get(principal, "run", body["run_id"])
        if run.get("engine_version") != "dsh-v1" or run.get("dsh_session_id") != body["session_id"]:
            raise HTTPException(403, "SESSION_NOT_OWNED")
        if body.get("turn") is not None and run.get("dsh_turn") != body["turn"]:
            raise HTTPException(409, "TURN_BINDING_CONFLICT")
        return run

    async def register_step(self, principal, body):
        sid, turn = body["session_id"], body["turn"]
        conversation = await self.session_conversation(principal, sid)
        run = None
        if conversation:
            if conversation["profile"] != body["profile"]:
                raise HTTPException(403, "SESSION_PROFILE_CHANGED")
            await self.authorize_conversation(principal, conversation)
            await self.authorize_history(principal, conversation)
            rid = conversation.get("active_run") or conversation.get("last_run")
            if rid:
                current = await self.store.get(principal, "run", rid)
                if current.get("dsh_turn") in {None, turn}:
                    run = current
        if run is None:
            from aird_agent.hosts.jsd.request_inputs import request_inputs, native_documents
            inputs = request_inputs(body.get("request", ""))
            if body.get("attachments"):
                conversation = await self.ensure_conversation(principal, sid, body["profile"])
                inputs["private_source_ids"] = await native_documents(self, principal, conversation, body["attachments"])
                conversation = await self.store.get(principal, "conversation", conversation["id"])
            request = RunRequest(**inputs, profile=body["profile"],
                **({"conversation_id": conversation["id"], "expected_revision": conversation["revision"]} if conversation else {}))
            run = await self.create_run(principal, request, digest([sid, turn]), dispatch=False)
            await self.bind_session(principal, run["id"], sid)
        if run["status"] in {"CANCELLED", "FAILED", "SUCCEEDED"}:
            raise HTTPException(409, "RUN_REQUIRES_EXPLICIT_RESUME")
        if run.get("dsh_turn") == turn and (body.get("request") or body.get("attachments")):
            from aird_agent.hosts.jsd.request_inputs import request_inputs
            supplemental = request_inputs(body.get("request", ""))
            # A steer message cannot silently replace an already admitted filter.
            incoming = supplemental.get("retrieval_scope", {}).get("knowledge_documents")
            pinned = (run.get("retrieval_scope") or {}).get("knowledge_documents")
            if incoming and incoming != pinned:
                raise HTTPException(409, "RETRIEVAL_FILTER_REQUIRES_NEXT_TURN")
            refs = list(run.get("context_refs", []))
            for ref in supplemental["context_refs"]:
                if ref not in refs:
                    refs.append(ref)
            private_ids = list(run.get("private_source_ids", []))
            if body.get("attachments"):
                from aird_agent.hosts.jsd.request_inputs import native_documents
                for sidoc in await native_documents(self, principal, conversation, body["attachments"]):
                    if sidoc not in private_ids:
                        private_ids.append(sidoc)
            if refs != run.get("context_refs", []) or private_ids != run.get("private_source_ids", []):
                amended = {**run, "context_refs": refs, "private_source_ids": private_ids}
                topic = await self.host_call(principal, "runtime_context", self.context_request(amended), run["id"])
                async with self.store.transaction(principal, run["id"]) as conn:
                    run = await self.store.get(principal, "run", run["id"], conn)
                    run.update(context_refs=refs, private_source_ids=private_ids,
                        private_documents=topic.get("private_documents", []), scope_hash=self.bindings.scope_digest(topic))
                    await self.store.update(principal, "run", run, conn)
        async with self.store.transaction(principal, run["id"]) as conn:
            await conn.execute("SELECT pg_advisory_xact_lock(1847091011)")
            run = await self.store.get(principal, "run", run["id"], conn)
            if run["status"] != "RUNNING":
                count = await (await conn.execute("SELECT count(*) AS count FROM agent_runtime.harness_records WHERE kind='run' AND payload->>'engine_version'='dsh-v1' AND payload->>'status'='RUNNING' AND id<>%s", (run["id"],))).fetchone()
                if count["count"] >= self.settings.max_running_runs:
                    raise HTTPException(429, "RUN_CAPACITY_REACHED")
            run.update(status="RUNNING", dsh_turn=turn, epoch=max(1, run["epoch"]), started_at=run.get("started_at") or time.time())
            await self.store.update(principal, "run", run, conn)
        run = await self.bindings.prepare_run(principal, run)
        await self.authorized_topic(principal, run)
        # Native session messages retain inputs and tool results. Introduce source
        # text only with a new input; subsequent snapshots carry business state.
        # Citation URLs belong to the renderer, not every model request.
        context = {"task_state": self.bindings.gate_state(run),
                   "evidence": self.bindings.evidence_summaries(run)}
        if body.get("request") or body.get("attachments") or not run.get("dsh_generation_started"):
            topic = await self.host_call(principal, "runtime_context", self.context_request(run), run["id"])
            context["authorized_context"] = await self.bindings.runtime_topic(principal, run, topic)
        conversation = await self.session_conversation(principal, sid)
        from aird_agent.evidence.links import citation_links
        links = citation_links(run.get("evidence", {}), self.settings.public_url)
        return {**run, "tools": self.tools_for(run), "active_tools": run.get("active_tools", self.bindings.initial_tools(run)),
            "citation_links": links,
            "instructions": self.instructions_for(run), "context": json.dumps(context, ensure_ascii=False)}

    async def gate_dsh(self, principal, body):
        run = await self.session_run(principal, body)
        await self.authorized_topic(principal, run)
        async with self.store.transaction(principal, run["id"]) as conn:
            run = await self.active(principal, run["id"], run["epoch"], conn)
            unknown = await (await conn.execute("SELECT 1 FROM agent_runtime.harness_calls WHERE run_id=%s AND status='UNKNOWN' LIMIT 1", (run["id"],))).fetchone()
            if unknown:
                raise HTTPException(409, "SIDE_EFFECT_RECONCILIATION_REQUIRED")
            if run["active_seconds"] + time.time() - run["started_at"] > self.settings.max_active_seconds:
                raise HTTPException(409, "BUDGET_EXCEEDED")
            usage_records = [call.get("usage", {}) for call in run.get("dsh_model_attempts", [])] if run.get("dsh_model_attempts") else run.get("dsh_usage", [])
            if sum(usage.get("totalTokens", usage.get("inputTokens", 0) + usage.get("outputTokens", 0)) for usage in usage_records) >= self.settings.max_total_tokens:
                raise HTTPException(409, "TOKEN_BUDGET_EXCEEDED")
            kind = body["kind"]
            counter, maximum = {"step": ("steps", self.settings.max_steps),
                "model": ("model_calls", self.settings.max_model_calls), "tool": ("tool_calls", self.settings.max_tool_calls), "native-tool": ("tool_calls", self.settings.max_tool_calls)}[kind]
            if run[counter] >= maximum:
                raise HTTPException(409, "BUDGET_EXCEEDED")
            if kind != "tool":
                run[counter] += 1
            if kind == "model":
                run["dsh_generation_started"] = True
                run.setdefault("dsh_model_attempts", []).append({
                    "call_id": body["call_id"], "purpose": body.get("purpose", "conversation"),
                    "provider": body.get("provider"), "model": body.get("model"), "status": "PENDING",
                })
            await self.store.update(principal, "run", run, conn)
        return {"allowed": True}

    async def model_result(self, principal, body, *, auxiliary=False):
        if auxiliary:
            record = await self.session_conversation(principal, body["session_id"])
            if not record:
                raise HTTPException(404, "Session not found")
            await self.authorize_conversation(principal, record)
            kind, field = "conversation", "dsh_auxiliary_calls"
        else:
            record = await self.session_run(principal, body)
            kind, field = "run", "dsh_model_attempts"
        async with self.store.transaction(principal, record["id"]) as conn:
            record = await self.store.get(principal, kind, record["id"], conn)
            attempt = next((call for call in record.get(field, []) if call["call_id"] == body["call_id"]), None)
            if attempt is None:
                raise HTTPException(409, "MODEL_ADMISSION_REQUIRED")
            if attempt["status"] != "PENDING":
                return {"accepted": True}
            attempt.update(usage=body.get("usage") or {}, status="COMPLETED" if body.get("completed") else "INTERRUPTED")
            await self.store.update(principal, kind, record, conn)
        return {"accepted": True}

    async def admit_auxiliary(self, principal, body):
        if body.get("purpose") != "session-title":
            raise HTTPException(403, "AUXILIARY_PURPOSE_DENIED")
        conversation = await self.ensure_conversation(principal, body["session_id"], "agent")
        # Current IAM admission is checked even before a conversation has a Run.
        await self.java(principal, "runtime_context", {"profile": "agent", "context_refs": []})
        async with self.store.transaction(principal, conversation["id"]) as conn:
            conversation = await self.store.get(principal, "conversation", conversation["id"], conn)
            calls = conversation.setdefault("dsh_auxiliary_calls", [])
            if len(calls) >= self.settings.max_model_calls:
                raise HTTPException(409, "BUDGET_EXCEEDED")
            if sum(call.get("usage", {}).get("totalTokens", 0) for call in calls) >= self.settings.max_total_tokens:
                raise HTTPException(409, "TOKEN_BUDGET_EXCEEDED")
            calls.append({key: body[key] for key in ("call_id", "purpose", "provider", "model")})
            calls[-1]["status"] = "PENDING"
            await self.store.update(principal, "conversation", conversation, conn)
        return {"allowed": True}

    async def callback(self, principal, rid, action, body):
        # Business retrieval/review reservations only. No model queue, checkpoint or execution loop.
        if action != "auxiliary":
            raise HTTPException(404, "Unknown business callback")
        async with self.store.transaction(principal, rid) as conn:
            run = await self.active(principal, rid, body["epoch"], conn)
            if run["model_calls"] >= self.settings.max_model_calls:
                raise HTTPException(409, "BUDGET_EXCEEDED")
            run["model_calls"] += 1
            await self.store.update(principal, "run", run, conn)
            await self.store.event(principal, rid, "model.auxiliary", {"purpose": body.get("purpose")}, conn)
        return {"accepted": True}

    async def project_event(self, principal, body):
        conversation = await self.session_conversation(principal, body["session_id"])
        if not conversation:
            return {"accepted": True}
        event = body["event"]
        seq = event.get("seq")
        if not isinstance(seq, int):
            raise HTTPException(422, "INVALID_SESSION_EVENT")
        async with self.store.transaction(principal, conversation["id"]) as conn:
            conversation = await self.store.get(principal, "conversation", conversation["id"], conn)
            if seq <= conversation.get("dsh_seq", -1):
                return {"accepted": True}
            rid = conversation.get("active_run") or conversation.get("last_run")
            if not rid:
                return {"accepted": True}
            run = await self.store.get(principal, "run", rid, conn)
            kind, data = event["type"], event.get("data", {})
            turn = data.get("turn")
            if turn is not None and run.get("dsh_turn") not in {None, turn}:
                row = await (await conn.execute("SELECT id FROM agent_runtime.harness_records WHERE kind='run' AND organization_id=%s AND actor_id=%s AND payload->>'dsh_session_id'=%s AND (payload->>'dsh_turn')::int=%s", (*principal.key, body["session_id"], turn))).fetchone()
                if row:
                    rid = str(row["id"])
                    run = await self.store.get(principal, "run", rid, conn)
                else:
                    # New-turn events precede pre-step registration; replay them after the mapping exists.
                    return {"accepted": True, "pending_registration": True}
            if kind == "assistant/message":
                message = data.get("message", data)
                # The native delivery intent is authoritative for promised paths.
                # A failed present must not finish successfully just because its
                # final text acknowledges that registration failed.
                for part in message.get("content", []):
                    if part.get("type") != "tool-call" or part.get("name", part.get("toolName")) != "present":
                        continue
                    args = part.get("arguments", {})
                    try:
                        args = json.loads(args) if isinstance(args, str) else args
                    except (ValueError, TypeError):
                        continue
                    if isinstance(args, dict):
                        run["required_artifacts"] = list(dict.fromkeys([
                            *run.get("required_artifacts", []),
                            *(item["path"].replace("\\", "/").rsplit("/", 1)[-1]
                              for item in args.get("files", [])
                              if isinstance(item, dict) and isinstance(item.get("path"), str)),
                        ]))
                text = "".join(part.get("text", "") for part in message.get("content", []) if part.get("type") == "text")
                if text:
                    message_id = message.get("id") or str(uuid4())
                    run["last_text"] = self.bindings.normalize_output(text, run["evidence"])
                    projected = {"id": message_id, "role": "assistant", "text": run["last_text"], "run_id": rid}
                    run["assistant_messages"] = [*run.get("assistant_messages", []), projected]
                    if not any(item.get("id") == message_id for item in conversation["messages"]):
                        conversation["messages"].append(projected)
                    usage = data.get("usage", {})
                    await self.store.event(principal, rid, "message.completed", {**projected,"usage":{"input":usage.get("inputTokens",0),"output":usage.get("outputTokens",0)}}, conn, key=f"dsh:{seq}")
            elif kind == "user/message":
                message = data.get("message", data)
                text = "".join(part.get("text", "") for part in message.get("content", []) if part.get("type") == "text")
                if text and message.get("source", {}).get("kind") == "user" and not any(
                    item.get("run_id") == rid and item.get("role") == "user" and item.get("text") == text for item in conversation["messages"]):
                    conversation["messages"].append({"id": message.get("id", str(uuid4())), "role": "user", "text": text, "run_id": rid})
            elif kind == "turn/end":
                run["dsh_stop_reason"] = data.get("reason")
                if data.get("reason", {}).get("kind") == "max-tokens" and run.get("error") != "SIDE_EFFECT_RECONCILIATION_REQUIRED":
                    run.update(status="FAILED", error="OUTPUT_LIMIT_EXCEEDED", completion={
                        "outcome":"PARTIAL", "summary":"回答被截断，本轮任务尚未完成。",
                        "gaps":["OUTPUT_LIMIT_EXCEEDED"], "verification":"BUSINESS_RECEIPTS_CHECKED_NOT_SCIENTIFIC_VERIFICATION"})
                conversation["last_run"] = rid
                if conversation.get("active_run") == rid:
                    conversation["active_run"] = None
            elif kind == "agent/inbox/spliced":
                # Event projection only: dispatch and edits are owned by the native Inbox.
                await self.store.event(principal, rid, "conversation.queue", {"native": True}, conn, key=f"dsh:{seq}")
            if kind == "assistant/message":
                usage = data.get("usage")
                if usage:
                    run.setdefault("dsh_usage", []).append(usage)
            conversation["dsh_seq"] = seq
            run["dsh_seq"] = seq
            await self.store.update(principal, "run", run, conn)
            await self.store.update(principal, "conversation", conversation, conn)
        return {"accepted": True}

    async def project_stream(self, principal, body):
        run = await self.session_run(principal, body)
        async with self.store.transaction(principal, run["id"]) as conn:
          for frame in body.get("frames", [body.get("frame", {})]):
            if frame.get("type") == "chunk":
                chunk = frame.get("chunk", {})
                if chunk.get("type") in {"text-delta", "text_delta"}:
                    await self.store.event(principal, run["id"], "message.delta", {"delta": chunk.get("text", chunk.get("delta", ""))}, conn)
        return {"accepted": True}

    async def complete_dsh(self, principal, body):
        run = await self.session_run(principal, body)
        if run.get("dsh_completed_turn") == body.get("turn"):
            return {"accepted": True, "status": run["status"], "completion": run.get("completion")}
        await self.authorized_topic(principal, run)
        text, valid = self.bindings.validate_output(run, run.get("last_text", ""))
        async with self.store.transaction(principal, run["id"]) as conn:
            run = await self.store.get(principal, "run", run["id"], conn)
            if run.get("dsh_completed_turn") == body.get("turn"):
                return {"accepted": True, "status": run["status"], "completion": run.get("completion")}
            unknown = await (await conn.execute(
                "SELECT 1 FROM agent_runtime.harness_calls WHERE run_id=%s AND status='UNKNOWN' LIMIT 1", (run["id"],)
            )).fetchone()
            run["status"] = "SUCCEEDED" if valid and not unknown else "FAILED"
            run["result"] = text if valid else ""
            run["error"] = "SIDE_EFFECT_RECONCILIATION_REQUIRED" if unknown else None if valid else "CITATION_INVALID"
            try:
                await self.bindings.validate_delivery(principal, run, conn)
            except HTTPException as error:
                run.update(status="FAILED", error=error.detail)
            if body.get("stop_reason") == "max-tokens" and not unknown:
                run.update(status="FAILED", error="OUTPUT_LIMIT_EXCEEDED")
            run["completion"] = self.bindings.default_completion(run)
            if body.get("check_only"):
                # The native stop hook may steer a correction while this run
                # remains active. A preflight never commits a terminal result.
                return {"accepted": True, "status": run["status"], "completion": run["completion"]}
            run["active_seconds"] += max(0, time.time() - (run.get("started_at") or time.time()))
            run["started_at"] = None
            run["dsh_completed_turn"] = body.get("turn")
            await self.store.update(principal, "run", run, conn)
            from aird_agent.evidence.links import public_answer, public_citations
            await self.store.event(principal, run["id"], "run." + run["status"].lower(), {
                "text": public_answer(run["result"], run["evidence"], self.settings.public_url),
                "citations": public_citations(run["result"], run["evidence"], self.settings.public_url),
                "error": run["error"]}, conn)
        return {"accepted": True, "status": run["status"], "completion": run["completion"]}

    async def accept_message(self, principal, cid, request, key):
        conversation = await self.store.get(principal, "conversation", cid)
        await self.authorize_conversation(principal, conversation)
        rid = conversation.get("active_run") or conversation.get("last_run")
        if not rid or not conversation.get("dsh_session_id"):
            raise HTTPException(409, "DSH_SESSION_REQUIRED")

        await self.runtime(rid, "prompt", {"text": request.text, "mode": "steer" if request.delivery == "steer" else "queue", "request_id": key})
        return {"revision": conversation["revision"], "delivery": request.delivery, "native": True}

    async def change_queue(self, principal, cid, request, key):
        conversation = await self.store.get(principal, "conversation", cid)
        await self.authorize_conversation(principal, conversation)
        rid = conversation.get("active_run") or conversation.get("last_run")
        if not rid or not conversation.get("dsh_session_id"):
            raise HTTPException(409, "DSH_SESSION_REQUIRED")
        return await self.runtime(rid, "queue", {"item_id": str(request.message_id),
            "action": {"kind": request.action, "text": request.text}})

    async def cancel(self, principal, rid):
        run = await self.store.get(principal, "run", rid)
        await self.authorized_topic(principal, run, execution=False)
        await self.runtime(rid, "abort")
        await self.bindings.cancel_execution(principal, run)
        async with self.store.transaction(principal, rid) as conn:
            run = await self.store.get(principal, "run", rid, conn)
            run["status"] = "CANCELLED"
            await conn.execute("UPDATE agent_runtime.harness_calls SET status='UNKNOWN',updated_at=now() WHERE run_id=%s AND status='RUNNING'", (rid,))
            await self.store.update(principal, "run", run, conn)
            await self.store.event(principal, rid, "run.cancelled", {}, conn)
        return {"run_id": rid, "status": "CANCELLED"}

    async def resume(self, principal, rid):
        run = await self.store.get(principal, "run", rid)
        await self.authorized_topic(principal, run, execution=False)
        if run.get("engine_version") != "dsh-v1":
            raise HTTPException(409, "DSH_SESSION_REQUIRED")
        async with self.store.transaction(principal, rid) as conn:
            unknown = await (await conn.execute("SELECT 1 FROM agent_runtime.harness_calls WHERE run_id=%s AND status IN ('UNKNOWN','RUNNING') LIMIT 1", (rid,))).fetchone()
            if unknown:
                raise HTTPException(409, "SIDE_EFFECT_RECONCILIATION_REQUIRED")
            run = await self.store.get(principal, "run", rid, conn)
            run.update(status="QUEUED", principal=principal.model_dump(mode="json"), error=None)
            run.pop("dsh_turn", None)
            run.pop("dsh_completed_turn", None)
            run["started_at"] = None
            await self.store.update(principal, "run", run, conn)
        await self.runtime(rid, "resume", {"text": "继续当前任务，先核对已有业务回执和已登记成果。", "request_id": str(uuid4())})
        return {"run_id": rid, "status": "QUEUED"}
