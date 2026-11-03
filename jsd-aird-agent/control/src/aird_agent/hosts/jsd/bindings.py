"""JSD host and capability policy; the reusable coordinator imports none of these bindings."""

import base64
import contextlib
import hashlib
import json
import re

from fastapi import HTTPException

from aird_agent.capabilities.definitions import INITIAL_TOOLS, SKILL, TOOLS
from aird_agent.capabilities.fast_qa.history import answer_context, qa_history
from aird_agent.capabilities.literature.handler import LiteratureTools
from aird_agent.capabilities.literature.quality import matrix_summary
from aird_agent.capabilities.registry import FAST_QA_INSTRUCTIONS
from aird_agent.core.bindings import RuntimeBindings
from aird_agent.core.contracts import digest
from aird_agent.core.workspace import Workspace
from aird_agent.evidence.citations import normalize_citations, validate_generated, validate_report
from aird_agent.evidence.registry import register_candidates
from aird_agent.hosts.jsd.task_state import (
    ACTIVITIES,
    check_completion,
    check_plan,
    read_progress,
    scope_digest,
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

    @property
    def tool_character_budget(self):
        return self.runtime.tool_character_budget

    async def cancel_execution(self, principal, run):
        async with self.store.pool.connection() as conn:
            calls = await (
                await conn.execute(
                    "SELECT call_id,execution_epoch FROM agent_runtime.harness_calls "
                    "WHERE run_id=%s AND tool_name='sandbox_execute' AND status IN ('RUNNING','UNKNOWN')",
                    (run["id"],),
                )
            ).fetchall()
        for call in calls:
            with contextlib.suppress(Exception):
                await self.sandbox.cancel(digest([run["id"], call["execution_epoch"], call["call_id"]])[:32])

    async def authorized_topic(self, principal, run, *, execution=True):
        # Compatibility name for old callers; this now authorizes any run context.
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
        if conversation.get("pack_versions"):
            context = await self.java(principal, "runtime_context", self.context_request(conversation))
            if set(conversation["pack_ids"]) - set(context.get("pack_ids", [])):
                raise HTTPException(403, "CAPABILITY_ACCESS_REVOKED")
        else:
            await self.java(principal, "topic", {"id": conversation["topic_id"]})

    def tools_for(self, run):
        tools = self.packs.tools(run["pack_ids"]) if run.get("pack_versions") else TOOLS
        return [
            tool
            for tool in tools
            if run.get("private_source_ids") or not tool["name"].startswith("private_document_")
        ]

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

    def bounded_result(self, principal, run, result, call_id):
        if (
            result.get("trace", {}).get("profile") == "research"
            or result.get("trace", {}).get("mode") == "search"
            or "SCOPED_BM25" in result.get("trace", {}).get("strategy", "")
        ):
            from aird_agent.evidence.navigation import search_preview

            return search_preview(result, self.tool_character_budget)
        if result.get("file_id") and result.get("id"):
            return {
                key: result[key]
                for key in ("id", "name", "file_id", "bytes", "sha256", "status")
                if key in result
            } | {"provenance_url": "/api/v1/agents/research/artifact/" + result["id"] + "/provenance"}
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
            **{key: result[key] for key in ("id", "name", "receipt", "exit_code") if key in result},
            **({"activate_tools": result["activate_tools"]} if "activate_tools" in result else {}),
            "truncated": True,
            "preview": serialized[: self.tool_character_budget // 2],
            "evidence_ids": [
                item["evidence_id"] for item in result.get("candidates", []) if item.get("evidence_id")
            ],
            "instruction": "This is only a preview. Read the workspace file in windows; do not infer missing facts from a truncated result.",
        }

    async def remember_sources(self, principal, run, result):
        groups = [(name, result.get(name, [])) for name in ("candidates", "context_candidates")]
        candidates = [candidate for _, items in groups for candidate in items]
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
            registered = register_candidates(current["evidence"], candidates)
            current["evidence"] = registered
            for original in candidates:
                from aird_agent.evidence.registry import canonical_candidate

                candidate = canonical_candidate(original)
                eid = candidate["evidence_id"]
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
            if run.get("profile") == "fast_qa":
                current["answer_context"] = answer_context(result, self.tool_character_budget * 2)
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
                    if args["outcome"] == "COMPLETE":
                        executions = await (
                            await conn.execute(
                                "SELECT status,result FROM agent_runtime.harness_calls WHERE run_id=%s AND tool_name='sandbox_execute'",
                                (run["id"],),
                            )
                        ).fetchall()
                        if executions and not any(
                            item["status"] == "SUCCEEDED" and item["result"].get("exit_code") == 0
                            for item in executions
                        ):
                            raise HTTPException(
                                422, "SANDBOX_EXECUTION_NOT_SUCCEEDED: cannot claim completed computation"
                            )
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
            execution_receipt = None
            async with self.store.pool.connection() as conn:
                receipts = await (
                    await conn.execute(
                        "SELECT call_id,payload FROM agent_runtime.harness_receipts WHERE run_id=%s AND organization_id=%s AND actor_id=%s ORDER BY created_at DESC",
                        (run["id"], *principal.key),
                    )
                ).fetchall()
            for item in receipts:
                payload = item["payload"]
                if payload.get("receipt", {}).get("status") != "SUCCEEDED" or payload.get("exit_code") != 0:
                    continue
                if any(
                    a.get("path") == args["path"] and a.get("sha256") == hashlib.sha256(content).hexdigest()
                    for a in payload.get("artifacts", [])
                ):
                    execution_receipt = {
                        "call_id": item["call_id"],
                        **payload["receipt"],
                        "inputs": payload.get("inputs"),
                        "artifact_sha256": hashlib.sha256(content).hexdigest(),
                    }
                    break
            source_quotes = []
            if execution_receipt and (
                args.get("source_quotes")
                or any(
                    c.get("source", {}).get("kind") in {"KB_PUBLICATION", "RUN_UPLOAD", "EXTERNAL_FULLTEXT"}
                    for c in run.get("evidence", {}).values()
                )
            ):
                from aird_agent.evidence.quotes import quotes

                available = {
                    q["quote_id"]: {**q, "source": c["source"]}
                    for c in run["evidence"].values()
                    for q in quotes(c)
                }
                mentioned = set(ref["quote_id"] for ref in args.get("source_quotes", []))
                mentioned.update((execution_receipt.get("inputs") or {}).get("quote_ids", []))
                for entry in (execution_receipt.get("inputs") or {}).get("files", []):
                    original = workspace.resolve(entry["path"]).read_bytes()
                    if hashlib.sha256(original).hexdigest() != entry["sha256"]:
                        continue
                    mentioned.update(
                        re.findall(
                            r"(?<![a-zA-Z0-9_-])q[0-9a-f]{24}(?![a-zA-Z0-9_-])",
                            original.decode("utf8", errors="ignore"),
                        )
                    )
                if not mentioned or not mentioned <= available.keys():
                    raise HTTPException(
                        422,
                        "ANALYSIS_SOURCE_QUOTES_REQUIRED: preserve exact quote IDs in selected extraction inputs or source_quotes",
                    )
                for ref in args.get("source_quotes", []):
                    if available[ref["quote_id"]]["evidence_id"] != ref["evidence_id"]:
                        raise HTTPException(422, "QUOTE_HANDLE_INVALID_OR_WRONG_EVIDENCE")
                source_quotes = [available[qid] for qid in sorted(mentioned)]
            return await self.java(
                principal,
                "artifact_save",
                {
                    "topic_id": run.get("topic_id"),
                    "conversation_id": run["conversation_id"],
                    "source_refs": list({digest(q["source"]): q["source"] for q in source_quotes}.values())
                    if source_quotes
                    else run.get("source_refs", []),
                    "source_quotes": source_quotes,
                    "run_id": run["id"],
                    "name": path.name,
                    **({"execution_receipt": execution_receipt} if execution_receipt else {}),
                    "base64": base64.b64encode(content).decode(),
                    "key": digest([run["id"], call_id]),
                },
                run["id"],
            )
        if name == "sandbox_prepare":
            return self.sandbox.prepare(workspace, args["input_paths"])
        if name == "sandbox_execute":
            return await self.sandbox.execute(
                workspace,
                args["script"],
                args["language"],
                digest([run["id"], run["epoch"], call_id])[:32],
                args["input_paths"],
            )
        return await self.literature.dispatch(principal, run, topic, name, args, call_id)

    async def prepare_fast_qa(self, principal, run):
        conversation = await self.store.get(principal, "conversation", run["conversation_id"])
        await self.authorize_history(principal, conversation)
        # Recovery reuses the persisted retrieval after reauthorization, without
        # charging query understanding again or silently retrieving a new snapshot.
        if "answer_context" in run:
            await self.authorized_topic(principal, run)
            return run
        if run.get("prepared_qa"):
            await self.callback(
                principal,
                run["id"],
                "auxiliary",
                {"epoch": run["epoch"], "purpose": "host_query_understanding"},
            )
            await self.java(
                principal, "authorize_sources", {"sources": run.get("source_refs", [])}, run["id"]
            )
            await self.remember_sources(
                principal,
                run,
                {
                    "context": "",
                    "candidates": run["prepared_qa"]["candidates"],
                    "trace": {"prepared_by_host": True},
                },
            )
            return await self.store.get(principal, "run", run["id"])
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
            if run.get("prepared_qa"):
                return (
                    run["prepared_qa"]["instructions"]
                    + "\n固定生成配置：仅回答本轮问题，不使用工具或执行操作。"
                )
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
            if "private_source_ids" not in request.model_fields_set and existing.get("private_source_ids"):
                payload["private_source_ids"] = existing.get("private_source_ids", [])
            elif set(payload.get("private_source_ids", [])) - set(existing.get("private_source_ids", [])):
                raise HTTPException(403, "PRIVATE_SOURCE_NOT_IN_CONVERSATION")
            if existing.get("pack_versions"):
                legacy = False
                for name in ("topic_id", "context_refs", "pack_ids", "retrieval_scope"):
                    if name not in request.model_fields_set:
                        payload[name] = existing.get(name)
        if legacy:
            topic = await self.java(principal, "topic", {"id": str(request.topic_id)})
        else:
            topic = await self.java(principal, "runtime_context", self.context_request(payload))
            if topic.get("private_sources_ready") is False:
                raise HTTPException(409, "PRIVATE_SOURCES_NOT_READY")
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
                    (item["document_id"], item["publication_id"])
                    for item in [*topic.get("documents", []), *topic.get("private_documents", [])]
                }
                if any(
                    (item["document_id"], item["publication_id"]) not in authorized
                    for item in requirements["fulltext_documents"]
                ):
                    raise HTTPException(403, "FULLTEXT_REQUIREMENTS_OUTSIDE_SELECTED_CONTEXT")

        return payload, topic, legacy

    def conversation_metadata(self, payload):
        return {
            name: payload.get(name)
            for name in (
                "topic_id",
                "profile",
                "context_refs",
                "retrieval_scope",
                "pack_ids",
                "pack_versions",
                "private_source_ids",
            )
        }

    def validate_conversation_scope(self, conversation, payload):
        if conversation.get("topic_id") != payload.get("topic_id"):
            raise HTTPException(409, "Conversation belongs to another research topic")

    def scope_digest(self, scope):
        return scope_digest(scope)

    def inherit(self, previous):
        return {
            key: previous[key]
            for key in (
                "read_coverage",
                "evidence_contexts",
                "research_matrix",
                "navigation_cache",
                "ranking_snapshots",
                "retrieval_policy",
            )
            if key in previous
        }

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
        registered_output = None
        if name in {"evidence_save", "report_save", "artifact_save"} and result.get("id"):
            registered_output = {
                "id": result["id"],
                "kind": {"evidence_save": "card", "report_save": "report", "artifact_save": "artifact"}[name],
                "title": result.get("title", result.get("name", result.get("instance", "研究成果"))),
            }
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
        detail = exc.detail if isinstance(exc, HTTPException) else ""
        if (
            isinstance(exc, HTTPException)
            and exc.status_code == 422
            and str(detail).startswith("EVIDENCE_FIELD_")
        ):
            return {
                "correctable": True,
                "hint": "No card was saved. Read the original evidence and correct the fields, then submit a new tool call; do not replay the unchanged invalid arguments.",
            }
        return {}

    def gate_state(self, run):
        return {
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
        }

    def evidence_summaries(self, run):
        return [
            {
                "evidence_id": item["evidence_id"],
                "question": item["question"],
                "summary": item["summary"],
                "score": item["score"],
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
            **{key: topic.get(key) for key in ("id", "title", "goal", "documents", "scope_mode")},
            "evidence_catalog": [
                {"evidence_id": c["evidence_id"], "source": c["source"], "preview": c["text"][:160]}
                for c in list(run.get("evidence", {}).values())[-20:]
            ],
            "private_documents": topic.get("private_documents", []),
            "sandbox_enabled": self.settings.sandbox_enabled,
            "previous_request": run.get("continuation_request") or run.get("original_request"),
        }
        if run.get("profile") == "fast_qa":
            value.update(
                answer_context=run.get("answer_context", "（没有找到已授权的检索内容）"),
                history=qa_history(
                    await self.store.get(principal, "conversation", run["conversation_id"]), run
                ),
            )
        return value

    def initial_tools(self, run):
        if run.get("profile") == "fast_qa":
            return []
        names = self.packs.initial_tools(run["pack_ids"]) if run.get("pack_versions") else INITIAL_TOOLS
        return [
            name
            for name in names
            if run.get("private_source_ids") or not name.startswith("private_document_")
        ]

    def normalize_output(self, text, evidence):
        return normalize_citations(text, evidence)

    def validate_output(self, run, text):
        text = normalize_citations(text, run["evidence"])
        if run.get("prepared_qa"):
            from aird_agent.evidence.citations import project_aliases

            text = project_aliases(text, run.get("evidence_aliases", {}))
        try:
            cited = validate_generated(text, run["evidence"], run.get("prepared_qa", {}).get("allowed_refs"))
            if run.get("completion", {}).get("outcome") == "COMPLETE" and run.get("evidence") and not cited:
                raise HTTPException(422, "SOURCE_CITATIONS_REQUIRED")
            return text, True
        except HTTPException:
            return text, False

    def default_completion(self, run):
        unread = [
            f"原文读取范围：{sum(right - left for left, right in item.get('ranges', []))}/{item.get('total_nodes', 0)} 个节点；未覆盖全文"
            for item in run.get("read_coverage", {}).values()
            if sum(right - left for left, right in item.get("ranges", [])) < item.get("total_nodes", 0)
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
        return run["completion"]
