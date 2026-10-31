"""Durable tasks around pi; business identity and research objects stay in Java."""

import asyncio
import base64
import hashlib
import json
import logging
import re
import time
from uuid import uuid4

import httpx
from fastapi import HTTPException
from psycopg.types.json import Jsonb

from jsd_aird_agents.runtime_core import ExecutionContext

from .contracts import SourceRef, digest
from .host import JsdHostGateway
from .packs import FAST_QA_INSTRUCTIONS, default_registry
from .research import RCS, canonical_excerpt, normalize_citations, validate_report
from .research_quality import (
    ReportAuditor,
    matrix_summary,
    prepare_review,
    quotes,
    report_digest,
    resolve_quote,
    update_matrix,
)
from .sandbox import Sandbox
from .task_state import (
    ACTIVITIES,
    bound_document_page,
    check_completion,
    check_plan,
    read_progress,
    record_read,
    scope_digest,
)
from .tools import INITIAL_TOOLS, SKILL, TOOLS
from .workspace import Workspace

ACTIVE = {"QUEUED", "RUNNING", "WAITING_APPROVAL", "WAITING_CLARIFICATION"}
logger = logging.getLogger(__name__)


class Service:
    def __init__(self, settings, store, *, registry=None, host=None):
        self.settings, self.store = settings, store
        self.http = httpx.AsyncClient(timeout=120, follow_redirects=False)
        self.host = host or JsdHostGateway(settings, self.http)
        self.packs = registry or default_registry()
        self.rcs = RCS(settings)
        self.report_auditor = ReportAuditor(settings)
        self.sandbox = Sandbox(settings)
        self.tool_limits = {}

    async def authorized_topic(self, principal, run, *, execution=True):
        # Compatibility name for old callers; this now authorizes any run context.
        if run.get("pack_versions"):
            if execution:
                try:
                    self.packs.verify(run["pack_ids"], run["pack_versions"])
                except ValueError as error:
                    raise HTTPException(409, "PACK_VERSION_MISMATCH") from error
            topic = await self.java(principal, "runtime_context", self.context_request(run), run.get("id"))
            if set(run["pack_ids"]) - set(topic.get("pack_ids", [])):
                raise HTTPException(403, "CAPABILITY_ACCESS_REVOKED")
            await self.java(
                principal,
                "authorize_sources",
                {"sources": self.authorization_sources(run.get("source_refs", []))},
                run.get("id"),
            )
        else:
            topic = await self.java(principal, "topic", {"id": run["topic_id"]}, run["id"])
        await self.java(principal, "authorize", {"documents": run.get("source_documents", [])}, run["id"])
        if execution and run["scope_hash"] != scope_digest(topic):
            raise HTTPException(409, "SOURCE_SCOPE_CHANGED: start a new run with authorized context")
        return topic

    async def java(self, principal, action, arguments=None, run_id=None):
        return await self.host.call(principal, action, arguments or {}, run_id)

    @staticmethod
    def authorization_sources(sources):
        # KB authorization is publication-scoped. Rechecking 384 source nodes
        # must not reload the same two publication snapshots 384 times. This is
        # per-request deduplication, never an ACL cache across requests/users.
        unique = {}
        for source in sources:
            key = (
                ("KB_PUBLICATION", source["document_id"], source.get("publication_id"))
                if source.get("kind") == "KB_PUBLICATION"
                else digest(source)
            )
            unique[key] = source
        return list(unique.values())

    @staticmethod
    def context_request(run):
        return {key: run.get(key) for key in ("topic_id", "profile", "context_refs", "retrieval_scope")}

    async def authorize_conversation(self, principal, conversation):
        if conversation.get("pack_versions"):
            context = await self.java(principal, "runtime_context", self.context_request(conversation))
            if set(conversation["pack_ids"]) - set(context.get("pack_ids", [])):
                raise HTTPException(403, "CAPABILITY_ACCESS_REVOKED")
        else:
            await self.java(principal, "topic", {"id": conversation["topic_id"]})

    def tools_for(self, run):
        return self.packs.tools(run["pack_ids"]) if run.get("pack_versions") else TOOLS

    def mutation_tools(self, run):
        base = {
            "workspace_write",
            "sandbox_execute",
            "note_save",
            "evidence_save",
            "report_save",
            "artifact_save",
            "matrix_record",
        }
        return base | (self.packs.mutations(run["pack_ids"]) if run.get("pack_versions") else set())

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
        payload = request.model_dump(mode="json")
        if payload.get("research_requirements"):
            for option in ("require_report", "require_cards"):
                if not payload["research_requirements"][option]:
                    payload["research_requirements"].pop(option)
            for option in ("question_ids", "question_targets"):
                if not payload["research_requirements"][option]:
                    payload["research_requirements"].pop(option)
        if not request.upgrade_capabilities:
            payload.pop(
                "upgrade_capabilities", None
            )  # Keep existing default-request idempotency hashes stable.
        legacy = (
            request.topic_id is not None
            and request.pack_ids is None
            and request.profile == "agent"
            and not request.context_refs
            and request.research_requirements is None
        )
        if request.conversation_id:
            existing = await self.store.get(principal, "conversation", str(request.conversation_id))
            if existing.get("pack_versions"):
                legacy = False
                for name in ("topic_id", "context_refs", "pack_ids", "retrieval_scope"):
                    if name not in request.model_fields_set:
                        payload[name] = existing.get(name)
        if legacy:
            topic = await self.java(principal, "topic", {"id": str(request.topic_id)})
        else:
            topic = await self.java(principal, "runtime_context", self.context_request(payload))
            if "pack_ids" not in topic:
                raise HTTPException(503, "HOST_CAPABILITIES_UNAVAILABLE")
            selected = payload.get("pack_ids") or topic["pack_ids"]
            selected = list(dict.fromkeys(["core", *selected]))
            if request.profile == "fast_qa" and set(selected) != {"core", "fast-qa"}:
                raise HTTPException(422, "FAST_QA_REQUIRES_FIXED_CAPABILITIES")
            if set(selected) - set(topic["pack_ids"]):
                raise HTTPException(403, "CAPABILITY_NOT_AUTHORIZED")
            try:
                payload.update(pack_ids=selected, pack_versions=self.packs.pin(selected))
            except ValueError as error:
                raise HTTPException(422, "CAPABILITY_NOT_REGISTERED") from error
            requirements = payload.get("research_requirements")
            if requirements:
                if "literature" not in selected:
                    raise HTTPException(422, "FULLTEXT_REQUIRES_LITERATURE_CAPABILITY")
                authorized = {
                    (item["document_id"], item["publication_id"]) for item in topic.get("documents", [])
                }
                if any(
                    (item["document_id"], item["publication_id"]) not in authorized
                    for item in requirements["fulltext_documents"]
                ):
                    raise HTTPException(403, "FULLTEXT_REQUIREMENTS_OUTSIDE_SELECTED_CONTEXT")

        async def create(conn):
            if request.conversation_id:
                conversation = await self.store.get(
                    principal, "conversation", str(request.conversation_id), conn
                )
                if conversation.get("topic_id") != payload.get("topic_id"):
                    raise HTTPException(409, "Conversation belongs to another research topic")
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
                        **{
                            name: payload.get(name)
                            for name in (
                                "topic_id",
                                "profile",
                                "context_refs",
                                "retrieval_scope",
                                "pack_ids",
                                "pack_versions",
                            )
                        },
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
            scope_hash = scope_digest(topic)
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
                inherited = {
                    key: previous[key]
                    for key in ("read_coverage", "evidence_contexts", "research_matrix")
                    if key in previous
                }
            run = await self.store.create(
                principal,
                "run",
                {
                    **payload,
                    **inherited,
                    "continuation_request": continuation_request,
                    "conversation_id": conversation["id"],
                    "principal": principal.model_dump(mode="json"),
                    "engine_version": "pi-v1" if legacy else "unified-v1",
                    "status": "QUEUED",
                    "epoch": 0,
                    "lease_until": 0,
                    "model_calls": 0,
                    "tool_calls": 0,
                    "steps": 0,
                    "active_seconds": 0,
                    "evidence": evidence,
                    "messages": [],
                    "checkpoint": checkpoint,
                    "scope_hash": scope_hash,
                    "source_documents": list(
                        {
                            (item["document_id"], item["publication_id"]): item
                            for item in [
                                *topic.get("documents", []),
                                *[
                                    {
                                        "document_id": item["source"]["document_id"],
                                        "publication_id": item["source"]["publication_id"],
                                    }
                                    for item in evidence.values()
                                    if item["source"]["kind"] == "KB_PUBLICATION"
                                ],
                            ]
                        }.values()
                    ),
                    "source_refs": list(
                        {
                            digest(item["source"]): item["source"]
                            for item in evidence.values()
                            if item["source"]["kind"] != "KB_PUBLICATION"
                        }.values()
                    ),
                    "assistant_messages": [],
                    "registered_outputs": [],
                    "checkpoint_message_seq": 0,
                    "started_at": None,
                    "last_text": "",
                    "approvals": {},
                    "error": None,
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
                "task_state": {
                    key: read_progress(run.get(key, {}))
                    if key == "read_coverage"
                    else matrix_summary(run)
                    if key == "research_matrix"
                    else run.get(key)
                    for key in (
                        "research_requirements",
                        "research_matrix",
                        "read_coverage",
                        "plan",
                        "completion",
                        "registered_outputs",
                    )
                },
                "evidence_summaries": [
                    {
                        "evidence_id": item["evidence_id"],
                        "question": item["question"],
                        "summary": item["summary"],
                        "score": item["score"],
                    }
                    for item in run.get("evidence_contexts", [])[-12:]
                ],
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
        if name in {"document_read", "table_read"}:
            await self.java(
                principal,
                "authorize",
                {
                    "documents": [
                        {
                            "document_id": arguments["document_id"],
                            "publication_id": arguments["publication_id"],
                        }
                    ]
                },
                rid,
            )
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
                "INSERT INTO agent_runtime.harness_calls(run_id,call_id,request_hash,tool_name,status) VALUES(%s,%s,%s,%s,'RUNNING')",
                (rid, call_id, request_hash, name),
            )
            if name in ACTIVITIES:
                await self.store.event(
                    principal,
                    rid,
                    "research.activity",
                    {
                        "title": ACTIVITIES[name],
                        "status": "running",
                        "call_id": call_id,
                        "detail": arguments.get("query", arguments.get("question", ""))[:200],
                    },
                    conn,
                )
        outcome = None
        registered_output = None
        try:
            handler = self.packs.handler(run["pack_ids"], name) if run.get("pack_versions") else None
            if handler:
                result = await handler(ExecutionContext(principal, run, topic, self.java), arguments)
                result = await self.remember_sources(principal, run, result)
            else:
                result = await self.dispatch(principal, run, topic, name, arguments, call_id)
            if name in {"evidence_save", "report_save", "artifact_save"} and result.get("id"):
                registered_output = {
                    "id": result["id"],
                    "kind": {"evidence_save": "card", "report_save": "report", "artifact_save": "artifact"}[
                        name
                    ],
                    "title": result.get("title", result.get("name", result.get("instance", "研究成果"))),
                }
                sources = []
                if name == "evidence_save":
                    sources = [
                        run["evidence"]
                        .get(field.get("evidence_id"), {})
                        .get("source", field.get("source", {}))
                        for field in arguments.get("fields", [])
                    ]
                elif name == "report_save":
                    cited = validate_report(
                        normalize_citations(arguments["text"], run["evidence"]), run["evidence"]
                    )
                    sources = [run["evidence"][eid]["source"] for eid in cited]
                    if result.get("quality_protocol") == 4:
                        registered_output["quality_review"] = {
                            key: result["quality_review"][key]
                            for key in ("passed", "report_hash", "matrix_revision")
                        }
                registered_output["publications"] = [
                    {"document_id": did, "publication_id": pid}
                    for did, pid in sorted(
                        {
                            (source["document_id"], source["publication_id"])
                            for source in sources
                            if source.get("kind") == "KB_PUBLICATION"
                        }
                    )
                ]
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
                if (
                    isinstance(exc, HTTPException)
                    and exc.status_code == 422
                    and str(detail).startswith("EVIDENCE_FIELD_")
                ):
                    result.update(
                        correctable=True,
                        hint="No card was saved. Read the original evidence and correct the fields, then submit a new tool call; do not replay the unchanged invalid arguments.",
                    )
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
            if registered_output:
                current.setdefault("registered_outputs", []).append(registered_output)
                current.pop("completion", None)
                await self.store.update(principal, "run", current, conn)
            if name in ACTIVITIES:
                await self.store.event(
                    principal,
                    rid,
                    "research.activity",
                    {
                        "title": ACTIVITIES[name],
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
        serialized = json.dumps(result, ensure_ascii=False, default=str)
        if len(serialized) <= self.tool_character_budget:
            return result
        if len(serialized.encode()) > 10 * 1024 * 1024:
            return {
                "error": "TOOL_OUTPUT_TOO_LARGE",
                "retryable": False,
                "hint": "Request a smaller page or narrower source scope",
            }
        workspace = Workspace(
            self.settings.storage_root / "workspaces", str(principal.organization_id), run["id"]
        )
        path = "input/results/" + digest(call_id)[:32] + ".json"
        projection = workspace.materialize(path, serialized.encode())
        return {
            **projection,
            **({"activate_tools": result["activate_tools"]} if "activate_tools" in result else {}),
            "truncated": True,
            "preview": serialized[: self.tool_character_budget // 2],
            "evidence_ids": [
                item["evidence_id"] for item in result.get("candidates", []) if item.get("evidence_id")
            ],
            "instruction": "This is only a preview. Read the workspace file in windows; do not infer missing facts from a truncated result.",
        }

    async def remember_sources(self, principal, run, result):
        candidates = result.get("candidates", [])
        if not candidates and run.get("profile") != "fast_qa":
            return result
        if not isinstance(candidates, list) or len(candidates) > 100:
            raise HTTPException(502, "Invalid source batch")
        workspace = Workspace(
            self.settings.storage_root / "workspaces", str(principal.organization_id), run["id"]
        )
        workspace.prepare()
        async with self.store.transaction(principal, run["id"]) as conn:
            current = await self.active(principal, run["id"], run["epoch"], conn)
            refs = {digest(source): source for source in current.get("source_refs", [])}
            characters = sum(len(item["text"]) for item in current["evidence"].values())
            for candidate in candidates:
                source = SourceRef.model_validate(candidate["source"])
                if hashlib.sha256(candidate["text"].encode()).hexdigest() != source.content_sha256:
                    raise HTTPException(502, "SOURCE_HASH_MISMATCH")
                eid = "e" + digest(source.model_dump(mode="json"))[:24]
                if eid not in current["evidence"]:
                    characters += len(candidate["text"])
                    if characters > 2_000_000 or len(current["evidence"]) >= 500:
                        raise HTTPException(409, "EVIDENCE_BUDGET_EXCEEDED")
                candidate["evidence_id"] = eid
                current["evidence"][eid] = candidate
                refs[digest(candidate["source"])] = candidate["source"]
                candidate["workspace_path"] = "input/evidence/" + eid + ".json"
                workspace.materialize(
                    candidate["workspace_path"], json.dumps(candidate, ensure_ascii=False).encode()
                )
            current["source_refs"] = list(refs.values())
            if run.get("profile") == "fast_qa":
                context_text = result.get("context", "")
                if not isinstance(context_text, str) or len(context_text) > self.tool_character_budget * 2:
                    raise HTTPException(502, "INVALID_ANSWER_CONTEXT")
                for candidate in candidates:
                    label = candidate.get("label")
                    if isinstance(label, str) and re.fullmatch(r"[KDW][0-9]+", label):
                        context_text = context_text.replace(
                            "[evidenceRef=" + label + "]", "[E:" + candidate["evidence_id"] + "]"
                        )
                current["answer_context"] = context_text
                current["retrieval_trace"] = result.get("trace", {})
            if len(current["source_refs"]) > 500:
                raise HTTPException(409, "SOURCE_BUDGET_EXCEEDED")
            current.pop("completion", None)
            await self.store.update(principal, "run", current, conn)
        return result

    async def dispatch(self, principal, run, topic, name, args, call_id):
        workspace = Workspace(
            self.settings.storage_root / "workspaces", str(principal.organization_id), run["id"]
        )
        workspace.prepare()
        if name == "tool_search":
            words = re.findall(r"[a-z0-9_]+|[\u4e00-\u9fff]+", args["query"].lower())
            aliases = {
                "search": "search 检索 搜索 文献",
                "evidence": "证据 评分 比较 抽取 RCS",
                "report": "报告 写作 导出 Word",
                "workspace": "文件 工作区 file",
                "sandbox": "分析 脚本 Python 计算 code",
                "plan": "计划 进度 todo",
                "finish": "交付 完成 检查",
            }

            def rank(tool):
                label = (
                    tool["name"]
                    + " "
                    + tool["description"]
                    + " "
                    + " ".join(value for key, value in aliases.items() if key in tool["name"])
                ).lower()
                return sum(1 for word in words if word in label)

            catalog = self.tools_for(run)
            matches = sorted((tool for tool in catalog if rank(tool)), key=rank, reverse=True)
            selected = matches[:8] if matches else catalog
            # Schemas already live in the native registered catalog. Repeating
            # them here can overflow the result budget and hide activation.
            return {
                "tools": [{"name": tool["name"], "description": tool["description"]} for tool in selected],
                "activate_tools": [tool["name"] for tool in selected],
            }
        if name in {"plan_update", "finish_task"}:
            if (
                name == "plan_update"
                and args["task_type"] == "GENERAL"
                and any(item in {"report", "comparison"} for item in args["deliverables"])
            ):
                raise HTTPException(
                    422,
                    "GENERAL_OUTPUT: use answer or analysis_file; save downloadable files through artifact_save",
                )
            async with self.store.transaction(principal, run["id"]) as conn:
                current = await self.active(principal, run["id"], run["epoch"], conn)
                if name == "plan_update":
                    current["plan"] = check_plan(args)
                    current.pop("completion", None)
                    kind, value = "task.plan", args
                else:
                    current["completion"] = check_completion(current, args)
                    kind, value = "task.review", current["completion"]
                await self.store.update(principal, "run", current, conn)
                await self.store.event(principal, run["id"], kind, value, conn)
                return {"saved": True, **value}
        if name == "skill_read":
            if run.get("pack_versions"):
                ids = [args["name"]] if args.get("name") else run["pack_ids"]
                if set(ids) - set(run["pack_ids"]):
                    raise HTTPException(403, "SKILL_NOT_IN_RUN_CAPABILITIES")
                return {"name": ",".join(ids), "instructions": self.packs.instructions(ids)}
            return {"name": "literature-research", "version": "1", "instructions": SKILL}
        if name == "workspace_list":
            return workspace.manifest()
        if name == "workspace_read":
            return workspace.read(
                args["path"], args.get("offset", 0), min(12000, self.tool_character_budget // 2)
            )
        if name == "workspace_write":
            return workspace.write(args["path"], args["content"])
        if name == "artifact_save":
            path = workspace.resolve(args["path"], writable=True)
            if (
                not args["path"].startswith("output/")
                or not path.is_file()
                or path.stat().st_size > 10 * 1024 * 1024
            ):
                raise HTTPException(400, "Register a bounded file from output")
            content = path.read_bytes()
            return await self.java(
                principal,
                "artifact_save",
                {
                    "topic_id": run.get("topic_id"),
                    "conversation_id": run["conversation_id"],
                    "source_refs": run.get("source_refs", []),
                    "run_id": run["id"],
                    "name": path.name,
                    "base64": base64.b64encode(content).decode(),
                    "key": digest([run["id"], call_id]),
                },
                run["id"],
            )
        if name == "sandbox_execute":
            return await self.sandbox.execute(
                workspace, args["script"], args["language"], digest([run["id"], run["epoch"], call_id])[:32]
            )
        if name in {"knowledge_search", "document_read", "table_read"}:
            result = await self.java(
                principal,
                name,
                {**args, **self.context_request(run), "topic_id": run.get("topic_id")},
                run["id"],
            )
            if name == "document_read" or (
                name == "knowledge_search" and isinstance(result.get("trace", {}).get("candidate_count"), int)
            ):
                for candidate in result.get("candidates", []):
                    source = SourceRef.model_validate(candidate["source"])
                    candidate["evidence_id"] = "e" + digest(source.model_dump(mode="json"))[:24]
                    candidate["workspace_path"] = "input/evidence/" + candidate["evidence_id"] + ".json"
                result = bound_document_page(
                    result,
                    args,
                    self.tool_character_budget,
                    total_key="total_nodes" if name == "document_read" else "candidate_count",
                )
            candidates = result.get("candidates", [])
            async with self.store.transaction(principal, run["id"]) as conn:
                current = await self.active(principal, run["id"], run["epoch"], conn)
                sources = {
                    (item["document_id"], item["publication_id"]): item
                    for item in current.get("source_documents", [])
                }
                if name in {"document_read", "table_read"}:
                    sources[(args["document_id"], args["publication_id"])] = {
                        "document_id": args["document_id"],
                        "publication_id": args["publication_id"],
                    }
                current.pop("completion", None)
                evidence_chars = sum(len(item["text"]) for item in current["evidence"].values())
                for candidate in candidates:
                    source = SourceRef.model_validate(candidate["source"])
                    sources[(source.document_id, source.publication_id)] = {
                        "document_id": source.document_id,
                        "publication_id": source.publication_id,
                    }
                    if len(sources) > 100:
                        raise HTTPException(
                            409, "SOURCE_BUDGET_EXCEEDED: narrow the task or start another linked run"
                        )
                    if hashlib.sha256(candidate["text"].encode()).hexdigest() != source.content_sha256:
                        raise HTTPException(502, "SOURCE_HASH_MISMATCH")
                    candidate["evidence_id"] = "e" + digest(source.model_dump(mode="json"))[:24]
                    if candidate["evidence_id"] not in current["evidence"]:
                        evidence_chars += len(candidate["text"])
                        if evidence_chars > 2_000_000 or len(current["evidence"]) >= 500:
                            raise HTTPException(
                                409,
                                "EVIDENCE_BUDGET_EXCEEDED: save adopted evidence and start a fresh research run",
                            )
                    current["evidence"][candidate["evidence_id"]] = candidate
                    candidate["workspace_path"] = "input/evidence/" + candidate["evidence_id"] + ".json"
                    workspace.materialize(
                        candidate["workspace_path"], json.dumps(candidate, ensure_ascii=False).encode()
                    )
                current["source_documents"] = list(sources.values())
                if name == "document_read":
                    record_read(current, args, result)
                await self.store.update(principal, "run", current, conn)
            return result
        if name == "evidence_read":
            candidate = run["evidence"].get(args["evidence_id"])
            if candidate is None:
                raise HTTPException(
                    422, "UNKNOWN_EVIDENCE_ID: use an exact ID returned by retrieval/read tools"
                )
            if run.get("pack_versions", {}).get("literature", {}).get("version") != "4":
                return candidate
            spans = quotes(candidate)
            offset = args.get("quote_offset", 0)
            return {
                "evidence_id": candidate["evidence_id"],
                "source": candidate["source"],
                "quotes": spans[offset : offset + 6],
                "quote_count": len(spans),
                "next_quote_offset": offset + 6 if offset + 6 < len(spans) else None,
                "workspace_path": candidate.get("workspace_path"),
            }
        if name == "matrix_read":
            matrix = run.get("research_matrix", {"revision": 0, "questions": {}})
            if args.get("question_id"):
                row = matrix["questions"].get(args["question_id"])
                if row is None:
                    raise HTTPException(422, "MATRIX_QUESTION_NOT_FOUND")
                return {"revision": matrix["revision"], "question": row}
            return {
                "revision": matrix["revision"],
                "questions": [
                    {
                        "question_id": row["question_id"],
                        "question": row["question"],
                        "status": row["status"],
                        "claim_count": len(row["claims"]),
                        "gap_note": row["gap_note"],
                    }
                    for row in matrix["questions"].values()
                ],
            }
        if name == "matrix_record":
            async with self.store.transaction(principal, run["id"]) as conn:
                current = await self.active(principal, run["id"], run["epoch"], conn)
                matrix = update_matrix(current, args)
                current["research_matrix"] = matrix
                current.pop("report_checks", None)
                current.pop("completion", None)
                await self.store.update(principal, "run", current, conn)
                await self.store.event(
                    principal,
                    run["id"],
                    "research.matrix.updated",
                    {"question_id": args["question_id"], "revision": matrix["revision"]},
                    conn,
                )
            return {"revision": matrix["revision"], "question": matrix["questions"][args["question_id"]]}
        if name == "report_check":
            text = normalize_citations(args["text"], run["evidence"])
            prepared = prepare_review(run, args["title"], text)
            async with self.store.transaction(principal, run["id"]) as conn:
                current = await self.active(principal, run["id"], run["epoch"], conn)
                if (
                    not prepared.get("blocking_findings")
                    and current["model_calls"] >= self.settings.max_model_calls
                ):
                    raise HTTPException(409, "BUDGET_EXCEEDED")
                if not prepared.get("blocking_findings"):
                    current["model_calls"] += 1
                await self.store.update(principal, "run", current, conn)
            try:
                review = await self.report_auditor.review(prepared)
            except Exception:
                raise HTTPException(422, "REPORT_REVIEW_UNAVAILABLE: no passing check was recorded") from None
            async with self.store.transaction(principal, run["id"]) as conn:
                current = await self.active(principal, run["id"], run["epoch"], conn)
                if (
                    report_digest(args["title"], text, current.get("research_matrix", {}))
                    != prepared["report_hash"]
                ):
                    raise HTTPException(422, "REPORT_MATRIX_CHANGED_DURING_REVIEW")
                current["report_checks"] = {prepared["report_hash"]: review}
                await self.store.update(principal, "run", current, conn)
                await self.store.event(
                    principal,
                    run["id"],
                    "research.report.checked",
                    {"passed": review["passed"], "report_hash": prepared["report_hash"]},
                    conn,
                )
            return review
        if name == "evidence_score":
            candidates = []
            for eid in args["evidence_ids"]:
                if eid not in run["evidence"]:
                    raise HTTPException(422, "Unknown evidence")
                candidates.append(run["evidence"][eid])
            if any(
                candidate["source"]["kind"] not in {"KB_PUBLICATION", "RUN_UPLOAD", "EXTERNAL_FULLTEXT"}
                for candidate in candidates
            ):
                raise HTTPException(422, "RCS_REQUIRES_DOCUMENT_EVIDENCE")
            if any(len(candidate["text"]) > 24000 for candidate in candidates):
                raise HTTPException(
                    413, "RCS_CHUNK_TOO_LARGE: use narrower indexed evidence or read the table in windows"
                )
            async with self.store.transaction(principal, run["id"]) as conn:
                current = await self.active(principal, run["id"], run["epoch"], conn)
                if current["model_calls"] + len(candidates) > self.settings.max_model_calls:
                    raise HTTPException(409, "BUDGET_EXCEEDED")
                current["model_calls"] += len(candidates)
                await self.store.update(principal, "run", current, conn)
            result = await self.rcs.score(args["question"], candidates)
            async with self.store.transaction(principal, run["id"]) as conn:
                current = await self.active(principal, run["id"], run["epoch"], conn)
                contexts = {
                    digest([item["question"], item["evidence_id"]]): item
                    for item in current.get("evidence_contexts", [])
                }
                for item in result["contexts"]:
                    if item["evidence_id"] not in {candidate["evidence_id"] for candidate in candidates}:
                        raise HTTPException(502, "RCS_UNKNOWN_CONTEXT")
                    contexts[digest([args["question"], item["evidence_id"]])] = {
                        **item,
                        "question": args["question"],
                    }
                if len(contexts) > 200:
                    raise HTTPException(409, "EVIDENCE_CONTEXT_BUDGET_EXCEEDED")
                current["evidence_contexts"] = list(contexts.values())
                current.pop("completion", None)
                await self.store.update(principal, "run", current, conn)
            return {
                **result,
                "contexts": [
                    {
                        "evidence_id": item["evidence_id"],
                        "score": item["score"],
                        "summary": item["summary"],
                        "verification_status": item["verification_status"],
                        "document_id": item["source"]["document_id"],
                        "publication_id": item["source"].get("publication_id"),
                        "workspace_path": "input/evidence/" + item["evidence_id"] + ".json",
                    }
                    for item in result["contexts"]
                ],
            }
        if name == "note_save":
            if run.get("pack_versions") and not run.get("topic_id"):
                async with self.store.transaction(principal, run["conversation_id"]) as conn:
                    conversation = await self.store.get(
                        principal, "conversation", run["conversation_id"], conn
                    )
                    note = {"id": str(uuid4()), "text": args["text"]}
                    conversation["notes"] = [*conversation.get("notes", [])[-49:], note]
                    await self.store.update(principal, "conversation", conversation, conn)
                return note
            return await self.java(
                principal,
                "note_save",
                {"topic_id": topic["id"], "text": args["text"], "key": digest([run["id"], call_id])},
                run["id"],
            )
        if name == "evidence_save":
            fields = []
            for field in args["fields"]:
                field = dict(field)
                if field.get("quote_id"):
                    quote = resolve_quote(run["evidence"], field)
                    if field.get("excerpt") and field["excerpt"] != quote["text"]:
                        raise HTTPException(422, "EVIDENCE_FIELD_QUOTE_EXCERPT_CONFLICT")
                    field.pop("quote_id")
                    field["original"] = quote["text"]
                    field["excerpt"] = quote["text"]
                    field.setdefault("qualifiers", {})["source_quote_id"] = quote["quote_id"]
                if (
                    not all(
                        isinstance(field.get(key), str) and field[key].strip()
                        for key in ("name", "original", "excerpt")
                    )
                    or not (field.get("evidence_id") or field.get("source"))
                    or not isinstance(field.get("qualifiers", {}), dict)
                ):
                    raise HTTPException(
                        422,
                        'EVIDENCE_FIELD_INVALID: each field requires name, original, excerpt (exact source text), evidence_id from a retrieved candidate; optional value, unit and qualifiers (object). Example: {"name":"method","original":"verbatim text","excerpt":"verbatim text","evidence_id":"the real candidate ID","qualifiers":{"condition":"reported condition"}}',
                    )
                eid = field.pop("evidence_id", None)
                if eid is not None:
                    candidate = run["evidence"].get(eid)
                    excerpt = (
                        canonical_excerpt(candidate["text"], field.get("excerpt")) if candidate else None
                    )
                    if excerpt is None:
                        raise HTTPException(422, "EVIDENCE_FIELD_REQUIRES_EXACT_SOURCE_EXCERPT")
                    if field.get("source") and field["source"] != candidate["source"]:
                        raise HTTPException(422, "EVIDENCE_FIELD_SOURCE_CONFLICT")
                    field["source"] = candidate["source"]
                    field["excerpt"] = excerpt
                field["status"] = "AI_EXTRACTED"
                fields.append(field)
            publications = {
                (field["source"]["document_id"], field["source"].get("publication_id")) for field in fields
            }
            if len(publications) > 1:
                raise HTTPException(
                    422, "EVIDENCE_FIELD_SINGLE_PUBLICATION_REQUIRED: save separate instance cards"
                )
            args = {**args, "fields": fields}
            topic = await self.ensure_research_topic(principal, run, topic)
            return await self.java(
                principal,
                "evidence_save",
                {**args, "topic_id": topic["id"], "key": digest([run["id"], call_id])},
                run["id"],
            )
        if name == "report_save":
            args = {**args, "text": normalize_citations(args["text"], run["evidence"])}
            quality = {}
            if run.get("pack_versions", {}).get("literature", {}).get("version") == "4":
                matrix = run.get("research_matrix", {})
                review = run.get("report_checks", {}).get(report_digest(args["title"], args["text"], matrix))
                if not review or not review.get("passed"):
                    raise HTTPException(
                        422, "REPORT_REVIEW_REQUIRED: run report_check; fix unsupported claims before saving"
                    )
                quality = {"quality_protocol": 4, "evidence_matrix": matrix, "quality_review": review}
            topic = await self.ensure_research_topic(principal, run, topic)
            cited = validate_report(args["text"], run["evidence"])
            if not cited:
                raise HTTPException(
                    422, "REPORT_REQUIRES_FULLTEXT_CITATIONS: save an outline as a note instead"
                )
            return await self.java(
                principal,
                "report_save",
                {
                    **args,
                    **quality,
                    "topic_id": topic["id"],
                    "evidence": {eid: run["evidence"][eid] for eid in cited},
                    "key": digest([run["id"], call_id]),
                },
                run["id"],
            )
        if name in {"external_search", "bibliography_lookup"}:
            approval_key = digest({"tool": name, "arguments": args})
            approval = run["approvals"].get(approval_key)
            if approval and approval.get("decision") == "REJECTED":
                return {"error": "EXTERNAL_QUERY_REJECTED", "retryable": False}
            if approval and approval.get("expires_at", 0) <= time.time():
                approval = None
            if not approval or approval.get("decision") != "APPROVED":
                async with self.store.transaction(principal, run["id"]) as conn:
                    current = await self.active(principal, run["id"], run["epoch"], conn)
                    current["status"] = "WAITING_APPROVAL"
                    current["pause_ready"] = False
                    current["pending_approval"] = {
                        "id": approval_key,
                        "tool": name,
                        "arguments": args,
                        "expires_at": time.time() + 900,
                    }
                    current["active_seconds"] += time.time() - current["started_at"]
                    await self.store.update(principal, "run", current, conn)
                    await self.store.event(
                        principal, run["id"], "approval.required", current["pending_approval"], conn
                    )
                return {"approval_required": True, "approval_id": approval_key, "query": args["query"]}
            return await self.java(principal, name, args, run["id"])
        raise HTTPException(400, "Unknown tool")

    async def ensure_research_topic(self, principal, run, scope):
        if run.get("topic_id"):
            return scope
        topic = await self.java(
            principal,
            "research_topic_ensure",
            {
                "title": run["request"][:80],
                "goal": run["request"],
                "conversation_id": run["conversation_id"],
                "documents": scope.get("documents", []),
                "scope_mode": scope.get("scope_mode", "AUTOMATIC"),
            },
            run["id"],
        )
        async with self.store.transaction(principal, run["id"]) as conn:
            current = await self.active(principal, run["id"], run["epoch"], conn)
            conversation = await self.store.get(principal, "conversation", run["conversation_id"], conn)
            current["topic_id"] = conversation["topic_id"] = topic["id"]
            await self.store.update(principal, "run", current, conn)
            await self.store.update(principal, "conversation", conversation, conn)
        run["topic_id"] = topic["id"]
        return topic

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
        from .contracts import Principal

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
            principal = Principal.model_validate(row["payload"]["principal"])
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
                if run.get("profile") == "fast_qa":
                    run = await self.prepare_fast_qa(principal, run)
                workspace = Workspace(
                    self.settings.storage_root / "workspaces", str(principal.organization_id), run["id"]
                )
                workspace.prepare()
                workspace.materialize(
                    "input/context.json" if run.get("pack_versions") else "input/topic.json",
                    json.dumps(
                        {
                            **{k: topic.get(k) for k in ("id", "title", "goal", "documents", "scope_mode")},
                            **self.context_request(run),
                            "pack_ids": run.get("pack_ids", ["literature"]),
                        },
                        ensure_ascii=False,
                    ).encode(),
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
                        "request": run["request"],
                        "checkpoint": None
                        if run.get("profile") == "fast_qa"
                        else await self.store.checkpoint(principal, run["checkpoint"]),
                        "contextWindow": self.settings.model_context_window,
                        "outputTokens": self.settings.model_output_tokens,
                        "tools": [] if run.get("profile") == "fast_qa" else self.tools_for(run),
                        "initialTools": []
                        if run.get("profile") == "fast_qa"
                        else self.packs.initial_tools(run["pack_ids"])
                        if run.get("pack_versions")
                        else INITIAL_TOOLS,
                        "profile": run.get("profile", "agent"),
                        **({"instructions": self.instructions_for(run)} if run.get("pack_versions") else {}),
                        "messageSeq": run.get("checkpoint_message_seq", 0),
                        "parallelism": self.settings.parallelism,
                        "topic": {
                            **{k: topic.get(k) for k in ("id", "title", "goal", "documents", "scope_mode")},
                            "previous_request": run.get("continuation_request")
                            or run.get("original_request"),
                            **(
                                {
                                    "answer_context": run.get(
                                        "answer_context", "（没有找到已授权的检索内容）"
                                    ),
                                    "history": self.qa_history(
                                        (
                                            await self.store.get(
                                                principal, "conversation", run["conversation_id"]
                                            )
                                        ),
                                        run,
                                    ),
                                }
                                if run.get("profile") == "fast_qa"
                                else {}
                            ),
                        },
                    },
                )
            except Exception:
                async with self.store.transaction(principal, run["id"]) as conn:
                    current = await self.store.get(principal, "run", run["id"], conn)
                    if current["epoch"] != run["epoch"] or current["status"] != "RUNNING":
                        continue
                    current.update(status="FAILED", error="RUNTIME_OR_AUTHORIZATION_UNAVAILABLE")
                    await self.store.update(principal, "run", current, conn)
                    await self.store.event(
                        principal, run["id"], "run.failed", {"code": current["error"]}, conn
                    )

    async def prepare_fast_qa(self, principal, run):
        conversation = await self.store.get(principal, "conversation", run["conversation_id"])
        await self.authorize_history(principal, conversation)
        # Recovery reuses the persisted retrieval after reauthorization, without
        # charging query understanding again or silently retrieving a new snapshot.
        if "answer_context" in run:
            await self.authorized_topic(principal, run)
            return run
        # Reserve the query-understanding call as well as the final pi generation.
        await self.callback(
            principal, run["id"], "auxiliary", {"epoch": run["epoch"], "purpose": "query_understanding"}
        )
        result = await self.tool(
            principal,
            run["id"],
            run["epoch"],
            "fast-retrieval-1",
            "retrieve_answer_context",
            {
                "question": run["request"],
                "scope": run.get("retrieval_scope", {}),
                "history": self.qa_history(conversation, run),
            },
        )
        if result.get("error"):
            raise HTTPException(503, "FAST_QA_RETRIEVAL_FAILED")
        return await self.store.get(principal, "run", run["id"])

    def instructions_for(self, run):
        # Core planning/completion instructions require tools; fixed QA has none.
        if run.get("profile") == "fast_qa":
            return FAST_QA_INSTRUCTIONS
        instructions = self.packs.instructions(run["pack_ids"])
        if run.get("research_requirements"):
            instructions += "\n\n平台强制全文要求（不能通过修改计划或改成 GENERAL 绕过）：" + json.dumps(
                run["research_requirements"], ensure_ascii=False
            )
            instructions += "\n按 document_read 返回的 next_offset 逐页读完全部目标节点，不跳页，不把检索片段算作全文。通过 gate 的 read_coverage 可核对进度。工具结果已按完整节点限制大小，直接按 next_offset 继续。重要的相关证据用 evidence_score，全文读完不等于每个节点都要评分。交付前完成 finish_task，再直接给出实际报告和结论，不复述收到、自检状态或工具确认。"
            instructions += "\n阅读覆盖不等于研究结论。针对每个研究子问题进行 knowledge_search、evidence_score 和关键原文回读，随读保存证据摘要/笔记，不要等全文结束才尝试从会话记忆拼凑事实。压缩摘要不是原文，不能因摘要里没有数值就声称正文缺失，更不能依赖参考文献猜测本文结果。证据卡字段可提交 evidence_id、name、original、excerpt、value、unit、qualifiers，由平台补齐固定来源；excerpt 必须逐字来自对应证据，不要自行标为人工已核对。"
            instructions += "\n读取范围 ranges 使用半开区间 [start,end)，如 [0,3) 与 [4,6) 之间仍缺 offset=3。直接按 gate 的 missing_ranges 和 next_unread_offset 补读，不能把相邻数字误当成已覆盖。"
            instructions += "\n先evidence_read获取原文quote_id，保存卡片时提交name、evidence_id、quote_id；原文和来源由服务端补齐，无须抄写公式。针对每个研究子问题matrix_record，记录采用的具体原文句柄、条件和本次证据缺口。矩阵先于报告，不能用累积聊天摘要写报告。先report_check核查，再以完全相同title/text调用report_save；改文或改矩阵后须重查。核查是AI辅助，不是人工确认。"
        return instructions

    @staticmethod
    def qa_history(conversation, run):
        # Queued future questions may already be in the conversation. Neither
        # those nor the current question belong in query rewriting/history.
        prior_runs = []
        for message in conversation.get("messages", []):
            if message.get("run_id") == run["id"] and message["role"] == "user":
                break
            if (
                message["role"] == "user"
                and message.get("run_id")
                and message.get("status") not in {"QUEUED", "PENDING"}
            ):
                prior_runs.append(message["run_id"])
        # Queued user messages may precede the prior answer in append order.
        # Reconstruct completed turn order by run instead of append timestamp.
        history = [
            {"role": message["role"], "content": message["text"][:4000]}
            for rid in dict.fromkeys(prior_runs)
            for message in conversation.get("messages", [])
            if message.get("run_id") == rid
            and message["role"] in {"user", "assistant"}
            and message.get("status") not in {"QUEUED", "PENDING"}
        ]
        return history[-8:]

    async def authorize_history(self, principal, conversation):
        await self.authorize_conversation(principal, conversation)
        snapshots = {}
        for rid in {item["run_id"] for item in conversation.get("messages", []) if item.get("run_id")}:
            previous = await self.store.get(principal, "run", rid)
            snapshots[rid] = previous
            await self.java(principal, "authorize", {"documents": previous.get("source_documents", [])}, rid)
            if previous.get("source_refs"):
                await self.java(
                    principal,
                    "authorize_sources",
                    {"sources": self.authorization_sources(previous["source_refs"])},
                    rid,
                )
        return snapshots

    async def cleanup_workspaces(self):
        from .contracts import Principal

        async with self.store.pool.connection() as conn:
            expired = await (
                await conn.execute(
                    "SELECT id,payload FROM agent_runtime.harness_records WHERE kind='run' "
                    "AND payload->>'status'='WAITING_APPROVAL' AND (payload->'pending_approval'->>'expires_at')::double precision<=%s LIMIT 100",
                    (time.time(),),
                )
            ).fetchall()
        for row in expired:
            principal = Principal.model_validate(row["payload"]["principal"])
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
            principal = Principal.model_validate(row["payload"]["principal"])
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
                return {
                    "ready": (
                        bool(run.get("completion"))
                        and len(run["messages"]) <= run.get("checkpoint_message_seq", 0)
                    )
                    or run["status"] == "WAITING_APPROVAL",
                    "hint": "交付前用 finish_task 检查需求和成果；有缺口时如实标记 PARTIAL/NEEDS_INPUT。",
                }
            if action == "auxiliary":
                if run["status"] != "RUNNING" or run["model_calls"] >= self.settings.max_model_calls:
                    raise HTTPException(409, "BUDGET_EXCEEDED")
                run["model_calls"] += 1
                await self.store.event(
                    principal, rid, "model.auxiliary", {"purpose": "context_compaction"}, conn
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
                    run["last_text"] = normalize_citations(body["payload"].get("text", ""), run["evidence"])
                    body["payload"]["text"] = run["last_text"]
                    body["payload"]["id"] = str(uuid4())
                    if run["last_text"].strip():
                        run["assistant_messages"] = [
                            *run.get("assistant_messages", [])[-19:],
                            {"id": body["payload"]["id"], "text": run["last_text"]},
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
                text = normalize_citations(body.get("text", ""), run["evidence"])
                try:
                    validate_report(text, run["evidence"])
                    valid = True
                except HTTPException:
                    valid = False
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
                    unread = [
                        f"原文读取范围：{sum(right - left for left, right in item.get('ranges', []))}/{item.get('total_nodes', 0)} 个节点；未覆盖全文"
                        for item in run.get("read_coverage", {}).values()
                        if sum(right - left for left, right in item.get("ranges", []))
                        < item.get("total_nodes", 0)
                    ]
                    run["completion"] = {
                        "outcome": "PARTIAL",
                        "summary": "执行已结束，但尚未完成交付自检。",
                        "checks": [],
                        "gaps": ["尚未完成需求覆盖与成果交付检查", *unread],
                        "verification": "NOT_REVIEWED",
                    }
                    if run.get("profile") == "fast_qa" and run["status"] == "SUCCEEDED":
                        run["completion"] = {
                            "outcome": "COMPLETE",
                            "summary": "固定问答已生成；引用 ID 已校验，不代表事实或科研结论经过人工验证。",
                            "checks": [{"requirement": "仅使用本次检索登记的引用 ID", "status": "covered"}],
                            "gaps": [],
                            "verification": "CITATION_IDS_CHECKED_NOT_FACT_VERIFIED",
                        }
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
                conversation["messages"].append(
                    {"id": str(uuid4()), "role": "assistant", "text": run.get("result", ""), "run_id": rid}
                )
                if conversation.get("active_run") == rid:
                    conversation["active_run"] = None
                # A steer arriving after the last model boundary must not disappear.
                for pending in run["messages"]:
                    if pending["seq"] > run.get("checkpoint_message_seq", 0) and not any(
                        item["id"] == pending["id"] for item in conversation["queued"]
                    ):
                        pending = {**pending, "delivery": "follow_up", "status": "QUEUED"}
                        conversation["queued"].append(pending)
                        for message in conversation["messages"]:
                            if message["id"] == pending["id"]:
                                message.update(pending)
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
                            "request": follow_up["text"],
                            "queued_from_run_id": rid,
                            "status": "QUEUED",
                            "epoch": 0,
                            "lease_until": 0,
                            "model_calls": 0,
                            "tool_calls": 0,
                            "steps": 0,
                            "active_seconds": 0,
                            "messages": [],
                            "checkpoint_message_seq": 0,
                            "started_at": None,
                            "last_text": "",
                            "plan": None,
                            "completion": None,
                            "assistant_messages": [],
                            "registered_outputs": [],
                            "error": None,
                            **(
                                {"checkpoint": None, "evidence": {}, "source_refs": []}
                                if run.get("profile") == "fast_qa"
                                else {}
                            ),
                        },
                        conn,
                    )
                    conversation["active_run"] = next_run["id"]
                    for message in conversation["messages"]:
                        if message["id"] == follow_up["id"]:
                            message.update(status="DISPATCHED", run_id=next_run["id"])
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
