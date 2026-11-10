"""Literature business adapter with explicit control-plane ports.

The caller owns authorization, schema validation, leases and the call ledger.
This adapter owns source reading, research quality, approvals and Java research objects.
"""

import hashlib
import json
import time
from uuid import uuid4

import httpx
from fastapi import HTTPException

from aird_agent.capabilities.literature.acquisition import acquired_source
from aird_agent.capabilities.literature.quality import (
    REVIEW_CONTEXT_POLICY,
    ReportAuditor,
    ReviewOutputInvalid,
    prepare_review,
    quotes,
    report_digest,
    resolve_quote,
    reusable_unit_reviews,
    unavailable_review,
    update_matrix,
)
from aird_agent.capabilities.literature.rcs import RCS, canonical_excerpt
from aird_agent.core.contracts import digest
from aird_agent.core.receipts import receipt
from aird_agent.core.workspace import Workspace
from aird_agent.evidence.citations import normalize_citations, validate_report
from aird_agent.evidence.models import SourceRef
from aird_agent.evidence.navigation import cached_host, context_for, preview_context
from aird_agent.evidence.quotes import table_headers
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
        if name == "evidence_image_read":
            candidate = run.get("evidence", {}).get(args["evidence_id"])
            if candidate is None:
                raise HTTPException(422, "UNKNOWN_EVIDENCE_ID: retrieve the document evidence first")
            if candidate["source"]["kind"] != "KB_PUBLICATION":
                raise HTTPException(422, "SOURCE_IMAGE_REQUIRES_KNOWLEDGE_PUBLICATION")
            result = await self.java(principal, "source_image", {
                **self.context_request(run), "topic_id": run.get("topic_id"),
                "source": candidate["source"], "view": args.get("view", "auto"),
            }, run["id"])
            return {**result, "evidence_id": candidate["evidence_id"],
                    "image_analysis_hint": "调用原生read_image(file_path=business_file.path)才会把原图传给视觉模型。结合此证据正文、图注及页码核对；预览看不清时以view=detail重新读取高清PDF页，再调用read_image。注意image中的实际尺寸及降采样信息；仍无法辨认时说明限制，不猜测键型、立体构型或公式符号。"}
        if name in {"knowledge_search", "document_read", "document_outline", "table_read"}:
            context = self.context_request(run)
            if name == "knowledge_search" and args.get("documents"):
                from aird_agent.server.contracts import PublicationRef
                requested = [PublicationRef.model_validate(ref).model_dump(mode="json") for ref in args["documents"]]
                fixed = (run.get("retrieval_scope") or {}).get("knowledge_documents")
                if fixed and any(ref not in fixed for ref in requested):
                    raise HTTPException(403, "RETRIEVAL_FILTER_CANNOT_EXPAND")
                context["retrieval_scope"] = {**(run.get("retrieval_scope") or {}), "knowledge_documents": requested}
            result = await (
                self.java(
                    principal,
                    name,
                    {**args, **context, "topic_id": run.get("topic_id")},
                    run["id"],
                )
                if name == "table_read"
                else cached_host(
                    self.runtime,
                    principal,
                    run,
                    name,
                    {**args, **context, "topic_id": run.get("topic_id")},
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
                    delivered=await self.store.get(principal, "run", run["id"]),
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
            run = await self.ensure_table_headers(principal, run, candidate)
            spans = quotes(candidate)
            offset = args.get("quote_offset", 0)
            result = {
                "evidence_id": candidate["evidence_id"],
                "source": candidate["source"],
                "quotes": [],
                "quote_count": len(spans),
                "workspace_path": candidate.get("workspace_path"),
            }
            if candidate.get("table_context"):
                headers, missing = table_headers(run["evidence"], candidate)
                result["table_context"] = {
                    **candidate["table_context"],
                    "headers": [
                        {"evidence_id": item["evidence_id"], "source": item["source"], "quotes": quotes(item)}
                        for item in headers if item["evidence_id"] != candidate["evidence_id"]
                    ],
                    "requires_context_read": bool(missing),
                    "instruction": "Read columns in order with these exact headers; left and right sample groups and their units are independent. Do not replace an unusual source unit with a guessed correction.",
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
                progress_key = digest(["evidence_read", candidate["evidence_id"], offset])
                stalls = current.setdefault("navigation_stalls", {})
                stalls[progress_key] = 0 if new else stalls.get(progress_key, 0) + 1
                blocked = stalls[progress_key] >= 3 and not args.get("refresh")
                await self.store.update(principal, "run", current, conn)
            if blocked:
                raise HTTPException(
                    409, "READ_NO_PROGRESS: this exact source window has already been read repeatedly. "
                    "Re-reading it cannot supply missing table headers, other rows or test conditions. "
                    "Use evidence_context for neighbouring source nodes, advance next_quote_offset, "
                    "or revise the failed assertion using the already adopted quotes. "
                    "If the required support is still unavailable, disclose that gap and finish PARTIAL."
                )
            result["new_quote_count"] = len(new)
            if not new and not args.get("refresh"):
                result["next_action"] = (
                    "Reuse returned quote handles; use evidence_context or next_quote_offset for new spans."
                )
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
                changed = matrix != current.get("research_matrix")
                current["research_matrix"] = matrix
                if changed:
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
            for eid in validate_report(text, run["evidence"]):
                run = await self.ensure_table_headers(principal, run, run["evidence"][eid])
            try:
                prepared = prepare_review(run, args["title"], text)
            except HTTPException as error:
                # Capacity failures are deterministic check results too. Persist
                # them through the normal review path so unchanged drafts reuse
                # the failure and never reserve another reviewer model call.
                if error.status_code != 422 or not str(error.detail).startswith((
                    "REPORT_REVIEW_UNITS_TOO_LARGE:", "REPORT_REVIEW_CONTEXT_TOO_LARGE:",
                )):
                    raise
                matrix = run.get("research_matrix", {})
                prepared = {
                    "report_hash": report_digest(args["title"], text, matrix),
                    "matrix_revision": matrix.get("revision", 0),
                    "units": [], "questions": [],
                    "blocking_findings": [{"unit_id": "report", "reason": str(error.detail)}],
                }
            previous = run.get("report_checks", {}).get(prepared["report_hash"])
            if (previous and previous.get("quality_check_version") == 3
                    and previous.get("verification") != "INVALID_REVIEW_OUTPUT_NOT_VERIFIED"
                    and (not previous.get("passed") or not prepared.get("blocking_findings"))):
                return {
                    **previous,
                    "unchanged_report": True,
                    "next_action": previous.get("next_action") or "The report and evidence matrix are unchanged. Reuse this check; do not resubmit the same draft. "
                    "Revise the specific unsupported statements or add real source evidence before checking again. "
                    "If no supported revision is possible or the reviewer remains unavailable, disclose the gap and preserve the draft.",
                }
            prepared["reusable_units"] = reusable_unit_reviews(prepared, run.get("report_checks", {}))
            review = await self.check_report(principal, run, prepared)
            async with self.store.transaction(principal, run["id"]) as conn:
                current = await self.active(principal, run["id"], run["epoch"], conn)
                if (
                    report_digest(args["title"], text, current.get("research_matrix", {}))
                    != prepared["report_hash"]
                ):
                    raise HTTPException(422, "REPORT_MATRIX_CHANGED_DURING_REVIEW")
                assertions = {unit["unit_id"]: unit["text"] for unit in prepared["units"]}
                review["findings"] = [
                    {**unit, "text": assertions[unit["unit_id"]]}
                    for unit in review.get("units", [])
                    if unit["verdict"] not in {"SUPPORTED", "NON_FACTUAL"}
                ]
                for finding in review.get("blocking_findings", []):
                    if finding["unit_id"] in assertions:
                        review["findings"].append({
                            **finding, "verdict": "INSUFFICIENT", "text": assertions[finding["unit_id"]],
                        })
                current.pop("report_review_failure", None)
                if review["findings"] and review.get("verification") != "INVALID_REVIEW_OUTPUT_NOT_VERIFIED":
                    review["next_action"] = (
                        "Revise the exact text in findings and its supporting citations, then check the revised report. "
                        "Evidence gaps and proposed verification can remain as clearly limited statements. "
                        "Do not abandon supported findings or resubmit the unchanged report. "
                        "Retain the user's requested length and scope: a brief report needs concise findings, "
                        "their sources, relevant limits and next steps, not repeated results or a full technical review."
                    )
                checks = current.setdefault("report_checks", {})
                checks[prepared["report_hash"]] = review
                # Reverting between drafts does not make the same check new work.
                while len(checks) > 8:
                    del checks[next(iter(checks))]
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
            async def generate(system, messages):
                return await self.runtime.dsh_command(principal, "review", {
                    "session_id": run["dsh_session_id"], "run_id": run["id"],
                    "purpose": "evidence-score", "system": system, "messages": messages,
                })
            # The native model service admits and meters this batch once.
            result = await self.rcs.score(args["question"], candidates, generate=generate)
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
                        422, "REPORT_REVIEW_REQUIRED: the checker is unavailable for this run; preserve the draft and finish PARTIAL"
                        if run.get("report_review_failure", {}).get("policy") == REVIEW_CONTEXT_POLICY else
                        "REPORT_REVIEW_REQUIRED: run report_check; fix unsupported claims before saving"
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
        if name == "literature_acquire_status":
            result = await self.java(
                principal, name, {**args, "conversation_id": run["conversation_id"]}, run["id"]
            )
            return await acquired_source(self.runtime, principal, run, result, args["work_id"])
        if name in {"bibliography_lookup", "literature_search", "literature_expand", "literature_acquire"}:
            if name == "literature_acquire" and "private-literature" not in run.get("pack_ids", []):
                raise HTTPException(409, "PRIVATE_LITERATURE_CAPABILITY_REQUIRED")
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
                        "description": (
                            "获取已选论文的开放全文，解析后用于本次研究。不会发布到企业知识库。"
                            if name == "literature_acquire"
                            else "查找这篇论文引用的文献。" if name == "literature_expand" and args.get("direction", "references") == "references"
                            else "查找引用这篇论文的后续研究。" if name == "literature_expand"
                            else "将搜索：" + args.get("query", "")
                        ),
                        "expires_at": time.time() + 900,
                    }
                    current["active_seconds"] += time.time() - current["started_at"]
                    await self.store.update(principal, "run", current, conn)
                    await self.store.event(
                        principal, run["id"], "approval.required", current["pending_approval"], conn
                    )
                return {"approval_required": True, "approval_id": approval_key, "query": args.get("query", args.get("work_id", ""))}
            result = await self.java(
                principal, name,
                {**args, "conversation_id": run["conversation_id"], "run_id": run["id"]}
                if name == "literature_acquire" else args,
                run["id"],
            )
            if name == "literature_acquire":
                return await acquired_source(self.runtime, principal, run, result, args["work_id"])
            return result
        raise HTTPException(400, "Unknown tool")

    async def check_report(self, principal, run, prepared):
        async def generate(system, messages):
            return await self.runtime.dsh_command(principal, "review", {
                "session_id": run["dsh_session_id"], "run_id": run["id"],
                "system": system, "messages": messages,
            })

        invalid = None
        for attempt in range(2):
            try:
                if invalid is None:
                    return await self.report_auditor.review(prepared, generate=generate)
                return await self.report_auditor.review(prepared, repair=invalid, generate=generate)
            except ReviewOutputInvalid as error:
                invalid = error
                if attempt == 1:
                    return unavailable_review(prepared, error=error)
                async with self.store.transaction(principal, run["id"]) as conn:
                    await self.active(principal, run["id"], run["epoch"], conn)
                    await self.store.event(principal, run["id"], "model.auxiliary",
                                           {"purpose": "report_review_format_repair"}, conn)
            except HTTPException as error:
                if error.status_code == 503 or error.detail == "DSH_COMMAND_REFUSED":
                    return unavailable_review(prepared, reason="REPORT_REVIEW_UNAVAILABLE: checker not configured")
                raise
            except httpx.HTTPError:
                return unavailable_review(prepared, reason="REPORT_REVIEW_UNAVAILABLE: checker request failed; no verdict was recorded")
            except Exception:
                raise HTTPException(422, "REPORT_REVIEW_UNAVAILABLE: no passing check was recorded") from None

    async def ensure_table_headers(self, principal, run, candidate):
        _, missing = table_headers(run["evidence"], candidate)
        if missing:
            # Read-only host navigation rechecks the publication and permissions;
            # it cannot invent a relation by proximity or join another version.
            await context_for(self.runtime, principal, run, candidate, before=0, after=0)
            return await self.store.get(principal, "run", run["id"])
        return run

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
