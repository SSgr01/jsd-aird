"""Literature business adapter with explicit control-plane ports.

The caller owns authorization, schema validation, leases and the call ledger.
This adapter owns source reading, research quality, approvals and Java research objects.
"""

import hashlib
import json
import time
from uuid import uuid4

from fastapi import HTTPException

from aird_agent.capabilities.literature.quality import (
    ReportAuditor,
    ReviewOutputInvalid,
    prepare_review,
    quotes,
    report_digest,
    resolve_quote,
    update_matrix,
)
from aird_agent.capabilities.literature.rcs import RCS, canonical_excerpt
from aird_agent.core.contracts import digest
from aird_agent.core.receipts import receipt
from aird_agent.core.workspace import Workspace
from aird_agent.evidence.citations import normalize_citations, validate_report
from aird_agent.evidence.models import SourceRef
from aird_agent.evidence.navigation import cached_host, context_for, preview_context
from aird_agent.hosts.jsd.task_state import bound_document_page, record_read


class LiteratureTools:
    def __init__(self, settings, store, *, host_call, active_run, context_request, character_budget):
        self.settings, self.store = settings, store
        self.java, self.active = host_call, active_run
        self.context_request = context_request
        self._character_budget = character_budget
        self.rcs = RCS(settings)
        self.report_auditor = ReportAuditor(settings)

    @property
    def tool_character_budget(self):
        return self._character_budget()

    async def dispatch(self, principal, run, topic, name, args, call_id):
        workspace = Workspace(
            self.settings.storage_root / "workspaces", str(principal.organization_id), run["id"]
        )
        workspace.prepare()
        if name in {"knowledge_search", "document_read", "document_outline", "table_read"}:
            result = await (
                self.java(
                    principal,
                    name,
                    {**args, **self.context_request(run), "topic_id": run.get("topic_id")},
                    run["id"],
                )
                if name == "table_read"
                else cached_host(
                    self.runtime,
                    principal,
                    run,
                    name,
                    {**args, **self.context_request(run), "topic_id": run.get("topic_id")},
                )
            )
            if name == "document_read":
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
                if name in {"document_read", "document_outline", "table_read"}:
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
            if name == "knowledge_search":
                result["query"] = args["query"]
                result.setdefault("trace", {})["offset"] = args.get("offset", 0)
            if name == "knowledge_search" and result.get("candidates"):
                result["context"] = preview_context(
                    await context_for(self.runtime, principal, run, result["candidates"][0])
                )
            return result
        if name == "evidence_context":
            candidate = run["evidence"].get(args["evidence_id"])
            if candidate is None:
                raise HTTPException(422, "UNKNOWN_EVIDENCE_ID")
            return preview_context(
                await context_for(
                    self.runtime,
                    principal,
                    run,
                    candidate,
                    args.get("before", 1),
                    args.get("after", 1),
                    args.get("cursor", 0),
                    args.get("relation", "neighbors"),
                )
            )
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
            result = {
                "evidence_id": candidate["evidence_id"],
                "source": candidate["source"],
                "quotes": [],
                "quote_count": len(spans),
                "workspace_path": candidate.get("workspace_path"),
            }
            for span in spans[offset : offset + 6]:
                proposed = {**result, "quotes": [*result["quotes"], span]}
                if len(json.dumps(proposed, ensure_ascii=False)) > self.tool_character_budget - 256:
                    break
                result = proposed
            if not result["quotes"] and offset < len(spans):
                raise HTTPException(413, "QUOTE_METADATA_EXCEEDS_TOOL_BUDGET")
            end = offset + len(result["quotes"])
            result["next_quote_offset"] = end if end < len(spans) else None
            async with self.store.transaction(principal, run["id"]) as conn:
                current = await self.active(principal, run["id"], run["epoch"], conn)
                delivered = set(current.get("navigation_quote_ids", []))
                new = {q["quote_id"] for q in result["quotes"]} - delivered
                if new:
                    current["navigation_quote_ids"] = sorted(delivered | new)
                    current["navigation_stalls"] = {}
                    await self.store.update(principal, "run", current, conn)
            return result

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
                result = {
                    "revision": matrix["revision"],
                    "question": matrix["questions"][args["question_id"]],
                }
                await receipt(self.store, principal, run["id"], call_id, "control-transaction", result, conn)
            return result
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
            invalid = None
            for attempt in range(2):
                try:
                    review = (
                        await self.report_auditor.review(prepared)
                        if invalid is None
                        else await self.report_auditor.review(prepared, repair=invalid)
                    )
                    break
                except ReviewOutputInvalid as error:
                    invalid = error
                    if attempt == 0:
                        async with self.store.transaction(principal, run["id"]) as conn:
                            current = await self.active(principal, run["id"], run["epoch"], conn)
                            if current["model_calls"] >= self.settings.max_model_calls:
                                raise HTTPException(409, "BUDGET_EXCEEDED") from None
                            current["model_calls"] += 1
                            await self.store.update(principal, "run", current, conn)
                            await self.store.event(
                                principal,
                                run["id"],
                                "model.auxiliary",
                                {"purpose": "report_review_format_repair"},
                                conn,
                            )
                        continue
                    review = {
                        "passed": False,
                        "units": [],
                        "questions": [],
                        "report_hash": prepared["report_hash"],
                        "matrix_revision": prepared["matrix_revision"],
                        "quality_check_version": 3,
                        "blocking_findings": [
                            {
                                "unit_id": "review",
                                "reason": "REVIEW_OUTPUT_INVALID: retry review; no source support was established",
                            }
                        ],
                        "verification": "INVALID_REVIEW_OUTPUT_NOT_VERIFIED",
                    }
                except Exception:
                    raise HTTPException(
                        422, "REPORT_REVIEW_UNAVAILABLE: no passing check was recorded"
                    ) from None
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
                    await receipt(
                        self.store, principal, run["id"], call_id, "control-transaction", note, conn
                    )
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
