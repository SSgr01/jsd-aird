"""JSD business authorization, source evidence and delivery policy."""

import contextlib
import json
import re
from datetime import datetime

from fastapi import HTTPException

from aird_agent.capabilities.definitions import INITIAL_TOOLS, TOOLS
from aird_agent.capabilities.files.handler import FILE_TOOL_NAMES, FileTools
from aird_agent.capabilities.literature.export import validate_literature_export
from aird_agent.capabilities.literature.handler import LiteratureTools
from aird_agent.capabilities.literature.quality import matrix_summary
from aird_agent.capabilities.registry import CORE_INSTRUCTIONS
from aird_agent.core.bindings import RuntimeBindings
from aird_agent.core.contracts import digest
from aird_agent.core.workspace import Workspace
from aird_agent.evidence.citations import normalize_citations, validate_generated, validate_report
from aird_agent.evidence.exports import LITERATURE_KINDS, export_sources
from aird_agent.evidence.metadata import merge_metadata, metadata_preview
from aird_agent.evidence.navigation import quote_window
from aird_agent.evidence.presentation import model_candidate, source_label
from aird_agent.evidence.registry import register_candidates
from aird_agent.evidence.retrieval_context import qa_history
from aird_agent.hosts.jsd.task_state import (
    ACTIVITIES,
    check_delivery,
    read_progress,
    scope_digest,
    selected_evidence,
    selected_sources,
)
from aird_agent.sandbox.client import Sandbox


class JsdBindings(RuntimeBindings):
    activities = ACTIVITIES
    activity_event = "research.activity"
    qa_history = staticmethod(qa_history)

    def attach(self, runtime):
        super().attach(runtime)
        self.java = runtime.host_call
        self.literature = LiteratureTools(
            self.settings,
            self.store,
            host_call=self.java,
            active_run=self.active,
            context_request=self.context_request,
            character_budget=lambda: runtime.tool_character_budget,
        )
        self.literature.runtime = runtime
        self.sandbox = Sandbox(self.settings)
        self.files = FileTools(
            self.settings, self.store, host_call=self.java, sandbox=self.sandbox,
            character_budget=lambda: runtime.tool_character_budget,
            source_policy=self.file_source_policy,
        )

    @property
    def tool_character_budget(self):
        return self.runtime.tool_character_budget

    async def cancel_execution(self, principal, run):
        async with self.store.pool.connection() as conn:
            calls = await (
                await conn.execute(
                    "SELECT call_id,execution_epoch FROM agent_runtime.harness_calls "
                    "WHERE run_id=%s AND tool_name='dsh_execute' AND status IN ('RUNNING','UNKNOWN')",
                    (run["id"],),
                )
            ).fetchall()
        for call in calls:
            with contextlib.suppress(Exception):
                await self.sandbox.cancel(digest([run["id"], call["execution_epoch"], call["call_id"]])[:32])

    async def authorized_topic(self, principal, run, *, execution=True):
        if run.get("pack_versions"):
            if execution:
                try:
                    self.packs.verify(run["pack_ids"], run["pack_versions"])
                except ValueError as error:
                    raise HTTPException(409, "PACK_VERSION_MISMATCH") from error
            topic = await self.java(principal, "runtime_context", self.context_request(run), run.get("id"))
            if execution and topic.get("private_sources_ready") is False:
                raise HTTPException(409, "PRIVATE_SOURCES_NOT_READY")
            if set(run["pack_ids"]) - set(topic.get("pack_ids", [])):
                raise HTTPException(403, "CAPABILITY_ACCESS_REVOKED")
            from aird_agent.hosts.jsd.business_context import refresh_draft_context
            await refresh_draft_context(self, principal, run)
            await self.java(
                principal,
                "authorize_sources",
                await self.source_authorization(principal, run, historical=not execution),
                run.get("id"),
            )
        else:
            topic = await self.java(principal, "topic", {"id": run["topic_id"]}, run["id"])
        documents = {(item["document_id"], item["publication_id"]): item
                     for item in run.get("source_documents", [])}
        for item in run.get("document_metadata", []):
            documents[(item["document_id"], item["publication_id"])] = {
                "document_id": item["document_id"], "publication_id": item["publication_id"]}
        await self.java(principal, "authorize", {"documents": list(documents.values())}, run["id"])
        if execution and run["scope_hash"] != scope_digest(topic):
            raise HTTPException(409, "SOURCE_SCOPE_CHANGED: start a new run with authorized context")
        policy = topic.get("retrieval_policy", {})
        if policy.get("fingerprint") and run.get("id"):
            async with self.store.transaction(principal, run["id"]) as conn:
                current = (
                    await self.active(principal, run["id"], run["epoch"], conn)
                    if execution
                    else await self.store.get(principal, "run", run["id"], conn)
                )
                if current.get("retrieval_policy", {}).get("fingerprint") != policy["fingerprint"]:
                    current["retrieval_policy"] = policy
                    for key in ("navigation_cache", "ranking_snapshots", "navigation_stalls"):
                        current.pop(key, None)
                    await self.store.update(principal, "run", current, conn)
        return topic

    async def source_authorization(self, principal, run, *, historical=False):
        request = {"sources": self.authorization_sources(run.get("source_refs", []))}
        if historical and await self.runtime.briefings.historical_run(principal, run):
            request["purpose"] = "briefing-history"
        return request

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
        return {
            key: run.get(key)
            for key in (
                "topic_id",
                "profile",
                "context_refs",
                "retrieval_scope",
                "conversation_id",
                "private_source_ids",
            )
        } | {"conversation_id": run.get("conversation_id") or run.get("id")}

    async def authorize_conversation(self, principal, conversation):
        # Sources are authorized from each immutable run, not from a session-level selection.
        await self.java(principal, "runtime_context", {"profile": conversation["profile"]})

    def tools_for(self, run):
        tools = self.packs.tools(run["pack_ids"]) if run.get("pack_versions") else TOOLS
        # The native catalog must already contain readers before an approved acquisition
        # adds its first source. Initial activation is still limited; readers enforce membership.
        if "business-data" in run.get("pack_ids", []):
            tools = [item for item in tools if item["name"] not in {
                "search_projects", "get_project_snapshot", "list_project_stages", "list_project_tasks",
                "get_task_detail", "get_project_records", "search_experiments", "get_experiment_record",
                "query_structured_data",
            }]
        if run.get("automation"):
            from aird_agent.hosts.jsd.briefings import READ_TOOLS
            tools = [item for item in tools if item["name"] in READ_TOOLS]
        return tools

    def mutation_tools(self, run):
        base = {
            "dsh_execute",
            "note_save",
            "evidence_save",
            "report_save",
            "artifact_save",
            "matrix_record",
            "literature_acquire",
        }
        return base | (self.packs.mutations(run["pack_ids"]) if run.get("pack_versions") else set())

    def bounded_result(self, principal, run, result, call_id):
        from aird_agent.evidence.presentation import model_result
        if "report_hash" in result and "quality_check_version" in result and "units" in result:
            # The full independent review remains in the run. The author needs
            # the failed assertions, not dozens of already supported verdicts.
            result = {
                **{key: value for key, value in result.items() if key != "units"},
                "checked_unit_count": len(result["units"]),
                "units": [unit for unit in result["units"] if unit["verdict"] not in {"SUPPORTED", "NON_FACTUAL"}],
            }
        if (
            result.get("trace", {}).get("profile") == "research"
            or result.get("trace", {}).get("mode") == "search"
            or "SCOPED_BM25" in result.get("trace", {}).get("strategy", "")
        ):
            from aird_agent.evidence.navigation import search_preview

            return search_preview(result, self.tool_character_budget, delivered=run)
        if result.get("file_id") and result.get("id"):
            return {
                key: result[key]
                for key in ("id", "name", "file_id", "bytes", "sha256", "status")
                if key in result
            } | {"provenance_url": "/api/v1/agents/research/artifact/" + result["id"] + "/provenance"}
        serialized = json.dumps(model_result(result), ensure_ascii=False, default=str)
        if len(serialized.encode()) > 10 * 1024 * 1024:
            return {
                "error": "TOOL_OUTPUT_TOO_LARGE",
                "retryable": False,
                "hint": "Request a smaller page or narrower source scope",
            }
        # dsh owns generic tool-result pruning and context compaction. Business
        # readers paginate at their source; never point native tools at a
        # Python-only workspace or truncate an authoritative business record.
        return result

    def delivery_result(self, run, name, arguments, result):
        from aird_agent.evidence.delivery import delivery_result

        return delivery_result(run, name, arguments, result)

    async def remember_sources(self, principal, run, result):
        groups = [(name, result.get(name, [])) for name in ("candidates", "context_candidates")]
        candidates = [candidate for _, items in groups for candidate in items]
        if not candidates and not result.get("document_metadata"):
            return result
        if not isinstance(candidates, list) or len(candidates) > 100:
            raise HTTPException(502, "Invalid source batch")
        workspace = Workspace(
            self.settings.storage_root / "workspaces", str(principal.organization_id), run["id"]
        )
        workspace.prepare()
        async with self.store.transaction(principal, run["id"]) as conn:
            current = await self.active(principal, run["id"], run["epoch"], conn)
            current["document_metadata"] = merge_metadata(current.get("document_metadata", []), result.get("document_metadata", []))
            documents = {(d["document_id"], d["publication_id"]): d for d in current.get("source_documents", [])}
            for item in result.get("document_metadata", []):
                documents[(item["document_id"], item["publication_id"])] = {"document_id": item["document_id"], "publication_id": item["publication_id"]}
            current["source_documents"] = list(documents.values())
            refs = {digest(source): source for source in current.get("source_refs", [])}
            registered = register_candidates(current["evidence"], candidates)
            current["evidence"] = registered
            accessed = set(current.get("turn_evidence_ids", []))
            for original in candidates:
                from aird_agent.evidence.registry import canonical_candidate

                candidate = canonical_candidate(original)
                eid = candidate["evidence_id"]
                accessed.add(eid)
                candidate["source"] = registered[eid]["source"]
                refs[digest(candidate["source"])] = candidate["source"]
                candidate["workspace_path"] = "input/evidence/" + eid + ".json"
                original.clear()
                original.update(candidate)
                current["evidence"][eid] = candidate
                workspace.materialize(
                    candidate["workspace_path"], json.dumps(candidate, ensure_ascii=False).encode()
                )
            current["source_refs"] = list(refs.values())
            current["turn_evidence_ids"] = sorted(accessed)
            if len(current["source_refs"]) > 500:
                raise HTTPException(409, "SOURCE_BUDGET_EXCEEDED")
            current.pop("completion", None)
            await self.store.update(principal, "run", current, conn)
        return result

    async def dispatch(self, principal, run, topic, name, args, call_id):
        if name == "tool_search":
            words = re.findall(r"[a-z0-9_]+|[\u4e00-\u9fff]+", args["query"].lower())
            words = list(dict.fromkeys(words + [word[index:index+2]
                for word in words if re.fullmatch(r"[\u4e00-\u9fff]+", word)
                for index in range(len(word)-1)]))
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
            selected = matches[:8]
            # Schemas already live in the native registered catalog. Repeating
            # them here can overflow the result budget and hide activation.
            return {
                "tools": [{"name": tool["name"], "description": tool["description"]} for tool in selected],
                "activate_tools": [tool["name"] for tool in selected],
            }
        if name in FILE_TOOL_NAMES:
            return await self.files.dispatch(principal, run, name, args, call_id)
        return await self.literature.dispatch(principal, run, topic, name, args, call_id)

    @staticmethod
    def file_source_policy(run, args, inputs, input_contents, parsed):
        source_quotes, source_refs = export_sources(run, args.get("source_quotes", []), inputs, input_contents)
        validate_literature_export(
            args["path"], parsed, [q for q in source_quotes if q["source"].get("kind") in LITERATURE_KINDS]
        )
        return {"source_quotes": source_quotes, "source_refs": source_refs}

    async def prepare_run(self, principal, run):
        # Seed an explicitly selected paper through the normal authorized, budgeted
        # read tool. No query rewrite/model call, and no duplicate read on recovery.
        if selected_sources(run) and not selected_evidence(run):
            doc, publication = sorted(selected_sources(run), key=lambda item: (item[0], item[1] or ""))[0]
            private = any(item["document_id"] == doc for item in run.get("private_documents", []))
            name = "private_document_read" if private else "document_read"
            args = {"document_id": doc, "offset": 0}
            if not private:
                if publication is None:
                    publication = next((item["publication_id"] for item in run.get("source_documents", [])
                                        if item["document_id"] == doc), None)
                if publication is None:
                    return run
                args.update(publication_id=publication, limit=4)
            await self.tool(principal, run["id"], run["epoch"], "selected-source-opening", name, args)
            # A failed read remains in the ledger. The agent can use another
            # authorized read or disclose the gap; COMPLETE still checks evidence.
            return await self.store.get(principal, "run", run["id"])
        return run

    def instructions_for(self, run):
        return CORE_INSTRUCTIONS

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
                    await self.source_authorization(principal, previous, historical=True),
                    rid,
                )
        return snapshots

    async def prepare_request(self, principal, request):
        payload = request.model_dump(mode="json")
        if not request.private_source_ids:
            payload.pop("private_source_ids", None)
        if request.private_source_ids and not request.conversation_id:
            raise HTTPException(422, "Private sources require their owning conversation")
        if payload.get("research_requirements"):
            for option in ("require_report", "require_cards"):
                if not payload["research_requirements"][option]:
                    payload["research_requirements"].pop(option)
            for option in ("question_ids", "question_targets"):
                if not payload["research_requirements"][option]:
                    payload["research_requirements"].pop(option)
        if request.conversation_id:
            existing = await self.store.get(principal, "conversation", str(request.conversation_id))
            if set(payload.get("private_source_ids", [])) - set(existing.get("private_source_ids") or []):
                raise HTTPException(403, "PRIVATE_SOURCE_NOT_IN_CONVERSATION")
            # The conversation registers ownership, never default inputs or retrieval filters.
        topic = await self.java(principal, "runtime_context", self.context_request(payload))
        if topic.get("private_sources_ready") is False:
            raise HTTPException(409, "PRIVATE_SOURCES_NOT_READY")
        if "pack_ids" not in topic:
            raise HTTPException(503, "HOST_CAPABILITIES_UNAVAILABLE")
        selected = payload.get("pack_ids") or topic["pack_ids"]
        selected = list(dict.fromkeys(["core", *selected]))
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
                (item["document_id"], item["publication_id"])
                for item in [*topic.get("documents", []), *topic.get("private_documents", [])]
            }
            if any(
                (item["document_id"], item["publication_id"]) not in authorized
                for item in requirements["fulltext_documents"]
            ):
                raise HTTPException(403, "FULLTEXT_REQUIREMENTS_OUTSIDE_SELECTED_CONTEXT")

        return payload, topic

    def conversation_metadata(self, payload):
        return {"profile": payload["profile"], "private_source_ids": []}

    def validate_conversation_scope(self, conversation, payload):
        if conversation["profile"] != payload["profile"]:
            raise HTTPException(409, "SESSION_PROFILE_CHANGED")

    def scope_digest(self, scope):
        return scope_digest(scope)

    def initial_state(self, topic, evidence):
        return {
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
            "private_documents": topic.get("private_documents", []),
        }

    async def authorize_tool(self, principal, rid, name, arguments):
        if name in {"document_read", "document_outline", "table_read"}:
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

    def output_receipt(self, run, name, arguments, result):
        if name == "create_experiment_draft" and result.get("id") and result.get("status") == "DRAFT":
            return {
                **{key: result[key] for key in ("id", "title", "path", "version_id", "experiment_no", "status") if key in result},
                "kind": "experiment_draft",
                **{key: arguments[key] for key in ("project_id", "stage_id", "task_id") if arguments.get(key)},
            }
        registered_output = None
        if name in {"evidence_save", "report_save", "artifact_save"} and result.get("id"):
            registered_output = {
                "id": result["id"],
                "kind": {"evidence_save": "card", "report_save": "report", "artifact_save": "artifact"}[name],
                "title": result.get("title", result.get("name", result.get("instance", "研究成果"))),
            }
            if name == "artifact_save":
                registered_output["path"] = arguments["path"]
            sources = []
            if name == "evidence_save":
                sources = [
                    run["evidence"].get(field.get("evidence_id"), {}).get("source", field.get("source", {}))
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
                        if source.get("kind") in {"KB_PUBLICATION", "RUN_UPLOAD", "EXTERNAL_FULLTEXT"}
                    }
                )
            ]
        return registered_output

    def error_info(self, exc):
        from aird_agent.hosts.jsd.host import HostToolError

        diagnosis = exc.tool_error if isinstance(exc, HostToolError) else {}
        detail = exc.detail if isinstance(exc, HTTPException) else ""
        if isinstance(exc, HTTPException) and exc.status_code in {401, 403}:
            return {**diagnosis, "access_denied": True}
        if (
            isinstance(exc, HTTPException)
            and exc.status_code == 422
            and str(detail).startswith("EVIDENCE_FIELD_")
        ):
            return {
                "correctable": True,
                "hint": "No card was saved. Read the original evidence and correct the fields, then submit a new tool call; do not replay the unchanged invalid arguments.",
            }
        return diagnosis

    def gate_state(self, run):
        # Native messages already contain source handles and reading results.
        # Repeating their growing catalog on every step appends another context
        # snapshot without adding evidence. Keep that detail in the ledger.
        state = {key: run[key] for key in (
            "report_review_failure", "research_requirements", "registered_outputs",
            "business_context_updates", "required_artifacts",
        ) if run.get(key)}
        if run.get("research_matrix"):
            state["research_matrix"] = matrix_summary(run)
        if (run.get("research_requirements") or {}).get("fulltext_documents"):
            state["read_coverage"] = read_progress(run.get("read_coverage", {}))
        if run.get("output_repair"):
            repair = run["output_repair"]
            state["citation_repair"] = {
                "remaining_model_calls": repair["max_model_calls"] - repair["model_calls"],
                "remaining_reads": repair["max_tool_calls"] - repair["tool_calls"],
                "instruction": "仅修复当前回答的引用，使用已读证据。最后一次模型请求必须直接完成答复；不得继续调用读取工具或扩展检索。",
            }
        return state

    def evidence_summaries(self, run):
        return [
            {
                "evidence_id": item["evidence_id"],
                "question": item["question"],
                "summary": item["summary"],
                "score": item["score"],
                "source": source_label(run.get("evidence", {}).get(item["evidence_id"], {}).get("source", {})),
                "source_quotes": quote_window(
                    run["evidence"][item["evidence_id"]], item["question"]
                )[2] if item["evidence_id"] in run.get("evidence", {}) else [],
            }
            for item in run.get("evidence_contexts", [])[-12:]
        ]

    def input_context(self, run, topic):
        return {
            **{key: topic.get(key) for key in ("id", "title", "goal", "documents", "scope_mode")},
            "private_documents": topic.get("private_documents", []),
            **self.context_request(run),
            "pack_ids": run.get("pack_ids", ["literature"]),
        }

    async def runtime_topic(self, principal, run, topic):
        value = {
            "current_date": datetime.now().astimezone().date().isoformat(),
            **{key: topic.get(key) for key in ("id", "title", "goal", "documents", "scope_mode")},
            "referenced_records": [model_candidate(item) for item in topic.get("context_candidates", [])],
            "evidence_catalog": [
                {**model_candidate(c), "text": c["text"][:160]}
                for c in list(run.get("evidence", {}).values())[-20:]
            ],
            "private_documents": topic.get("private_documents", []),
            "sandbox_enabled": self.settings.sandbox_enabled,
            "previous_request": run.get("continuation_request") or run.get("original_request"),
        }
        value["document_metadata"] = metadata_preview(run.get("document_metadata", []), 5000)
        if selected_sources(run):
            reading = {"candidates": [], "instruction": "已授权原文节点，仅代表下面实际提供的范围，不代表读完全文。"}
            candidates = sorted((run["evidence"][eid] for eid in selected_evidence(run)),
                                key=lambda item: (item.get("node_offset") is None, item.get("node_offset", 0)))
            for candidate in candidates[:8]:
                proposed = {**reading, "candidates": [*reading["candidates"], model_candidate(candidate)]}
                if len(json.dumps(proposed, ensure_ascii=False)) <= 10000:
                    reading = proposed
            value["selected_source_reading"] = reading
        return value

    def initial_tools(self, run):
        names = self.packs.initial_tools(run["pack_ids"]) if run.get("pack_versions") else INITIAL_TOOLS
        available = {item["name"] for item in self.tools_for(run)}
        return [
            name
            for name in names
            if name in available and (run.get("private_source_ids")
            or any(item.get("source", {}).get("kind") in {"RUN_UPLOAD", "EXTERNAL_FULLTEXT"}
                   for item in run.get("evidence", {}).values())
            or not name.startswith("private_document_"))
        ]

    def normalize_output(self, text, evidence):
        return normalize_citations(text, evidence)

    def validate_output(self, run, text):
        checked, valid, _ = self._check_output(run, text)
        return checked, valid

    def output_diagnostics(self, run, text):
        from aird_agent.evidence.citation_repair import diagnostics

        checked, valid, reason = self._check_output(run, text)
        return {} if valid else diagnostics(run, checked, reason)

    def prepare_output_repair(self, run):
        diagnostics = self.output_diagnostics(run, run.get("last_text", ""))
        if not diagnostics:
            raise HTTPException(409, "OUTPUT_REPAIR_NOT_REQUIRED")
        evidence = run.get("evidence", {})
        delivered = set(run.get("evidence_delivery", {})) | set(run.get("turn_evidence_ids", []))
        return {"turn": run["dsh_turn"], "model_calls": 0, "tool_calls": 0,
                "max_model_calls": self.settings.citation_repair_model_calls,
                "max_tool_calls": self.settings.citation_repair_tool_calls,
                "allowed_evidence_ids": sorted(set(evidence) & delivered or set(evidence)),
                "diagnostics": diagnostics}

    def authorize_repair_tool(self, run, name, arguments):
        from aird_agent.evidence.citation_repair import authorize_read

        authorize_read(run, name, arguments)

    def _check_output(self, run, text):
        from aird_agent.evidence.links import canonical_links, citation_links
        text = canonical_links(text, citation_links(run.get("evidence", {}), self.settings.public_url if hasattr(self, "settings") else "http://localhost:5173"))
        text = normalize_citations(text, run["evidence"])
        try:
            cited = validate_generated(text, run["evidence"])
            # Replies belong to this mapped native turn. A closing sentence may
            # use the cited answer from that turn without replaying old checkpoints.
            replies = run.get("assistant_messages", [])
            if not cited and replies and replies[-1]["text"].strip() == text.strip():
                for reply in reversed(replies[:-1]):
                    try:
                        refs = validate_generated(normalize_citations(reply["text"], run["evidence"]), run["evidence"])
                    except HTTPException:
                        continue
                    if refs:
                        cited = refs
                        break
            # Reusable evidence from a previous turn must not force a plain
            # business receipt to repeat that turn's scientific answer. Reads
            # in this turn, including rereads of inherited evidence, still cite.
            uses_sources = (
                bool(selected_sources(run)) and bool(run.get("evidence"))
                or bool(run.get("context_refs")) and bool(run.get("evidence"))
                or bool(run.get("business_records_read"))
                or bool((run.get("research_requirements") or {}).get("require_report"))
            )
            if (uses_sources and run.get("evidence") and selected_sources(run)
                    and not set(cited).intersection(selected_evidence(run))):
                raise HTTPException(422, "SELECTED_SOURCE_CITATIONS_REQUIRED")
            if uses_sources and run.get("evidence") and not cited:
                raise HTTPException(422, "SOURCE_CITATIONS_REQUIRED")
            return text, True, None
        except HTTPException as error:
            return text, False, str(error.detail)

    async def validate_delivery(self, principal, run, conn):
        # File registration checks the exact producing execution and byte hash.
        # An unrelated failed command does not invalidate a read-only business answer.
        check_delivery(run)

    def default_completion(self, run):
        return {
            "outcome": "COMPLETE" if run["status"] == "SUCCEEDED" else "PARTIAL",
            "summary": "本轮业务交付检查已通过。" if run["status"] == "SUCCEEDED" else "本轮业务交付检查未通过。",
            "gaps": [run["error"]] if run.get("error") else [],
            "verification": "BUSINESS_RECEIPTS_CHECKED_NOT_SCIENTIFIC_VERIFICATION",
        }
