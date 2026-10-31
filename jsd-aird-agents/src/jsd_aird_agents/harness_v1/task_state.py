"""Observable task state, not a fixed workflow or scientific verification."""

import json

from fastapi import HTTPException

from .contracts import digest


def check_plan(plan):
    """Keep legacy logs readable; new research plans carry explicit scope and method."""
    steps = plan["steps"]
    if (
        len({item["id"] for item in steps}) != len(steps)
        or sum(item["status"] == "in_progress" for item in steps) > 1
    ):
        raise HTTPException(422, "Plan requires unique steps and at most one active step")
    if plan.get("presentation") == "research_plan_v1" and (
        plan["task_type"] != "RESEARCH"
        or not plan.get("scope", "").strip()
        or not plan["goal"].strip()
        or not plan["summary"].strip()
        or any(not step["title"].strip() or not step.get("description", "").strip() for step in steps)
    ):
        raise HTTPException(422, "RESEARCH_PLAN: provide research goal, scope and a method for each step")
    return plan


def scope_digest(topic):
    documents = list(
        {(item["document_id"], item["publication_id"]): item for item in topic.get("documents", [])}.values()
    )
    value = {"documents": documents, "mode": topic.get("scope_mode", "SELECTED")}
    if "context_refs" in topic:
        value.update(context_refs=topic["context_refs"], retrieval_scope=topic.get("retrieval_scope", {}))
    return digest(value)


def check_completion(run, body):
    if body["outcome"] == "COMPLETE":
        requirements = run.get("research_requirements") or {}
        required_sources = requirements.get("fulltext_documents", [])
        questions = (
            set(requirements.get("question_ids", [])) | requirements.get("question_targets", {}).keys()
        )
        if questions - run.get("research_matrix", {}).get("questions", {}).keys():
            raise HTTPException(422, "MATRIX_QUESTIONS_INCOMPLETE")
        if required_sources:
            coverage = run.get("read_coverage", {})
            if any(
                not fully_read(coverage.get(item["document_id"] + ":" + item["publication_id"], {}))
                for item in required_sources
            ):
                raise HTTPException(
                    422,
                    "FULLTEXT_COVERAGE_INCOMPLETE: user-required documents cannot be removed by changing the plan",
                )
            if requirements.get("require_rcs"):
                contexts = run.get("evidence_contexts", [])
                scored = {
                    (item["source"]["document_id"], item["source"].get("publication_id")) for item in contexts
                }
                if any(
                    (item["document_id"], item["publication_id"]) not in scored for item in required_sources
                ):
                    raise HTTPException(
                        422,
                        "RCS_COVERAGE_INCOMPLETE: score relevant evidence from every required publication",
                    )
            outputs = run.get("registered_outputs", [])
            for option, kind in (("require_report", "report"), ("require_cards", "card")):
                if not requirements.get(option):
                    continue
                covered = {
                    (ref["document_id"], ref["publication_id"])
                    for output in outputs
                    if output["kind"] == kind
                    for ref in output.get("publications", [])
                }
                missing = [
                    ref
                    for ref in required_sources
                    if (ref["document_id"], ref["publication_id"]) not in covered
                ]
                if missing:
                    raise HTTPException(
                        422, "USER_DELIVERABLES_MISSING: " + kind + " for " + json.dumps(missing)
                    )
        plan = run.get("plan")
        if not plan and body.get("task_type") == "GENERAL":
            plan = {"task_type": "GENERAL", "steps": [], "deliverables": ["answer"]}
        if not plan:
            raise HTTPException(422, "PLAN_REQUIRED: record task and expected deliverables first")
        if run.get("pack_versions", {}).get("literature", {}).get("version") == "4" and (
            requirements.get("require_report") or "report" in plan["deliverables"]
        ):
            # A saved older snapshot remains valid history, not completion of a
            # newer matrix. Revision alone is insufficient: bind the check hash.
            checks = run.get("report_checks", {})
            if not any(
                output.get("kind") == "report"
                and (review := output.get("quality_review", {})).get("passed")
                and review.get("matrix_revision") == run.get("research_matrix", {}).get("revision")
                and checks.get(review.get("report_hash"), {}).get("passed")
                and checks.get(review.get("report_hash"), {}).get("quality_check_version") == 2
                for output in run.get("registered_outputs", [])
            ):
                raise HTTPException(422, "CURRENT_MATRIX_CHECKED_REPORT_REQUIRED")
        if any(step["status"] != "completed" for step in plan["steps"]):
            raise HTTPException(422, "UNFINISHED_PLAN: continue work or declare PARTIAL/NEEDS_INPUT")
        if not body["checks"] or any(item["status"] == "missing" for item in body["checks"]):
            raise HTTPException(
                422, "INCOMPLETE_COVERAGE: disclose missing work instead of claiming completion"
            )
        kinds = {item["kind"] for item in run.get("registered_outputs", [])}
        required = {"report": "report", "comparison": "card", "analysis_file": "artifact"}
        missing = [item for item in plan["deliverables"] if item in required and required[item] not in kinds]
        if missing:
            raise HTTPException(422, "DELIVERABLES_MISSING: " + ", ".join(missing))
        if (
            "comparison" in plan["deliverables"]
            and len({item["id"] for item in run.get("registered_outputs", []) if item["kind"] == "card"}) < 2
        ):
            raise HTTPException(
                422,
                "COMPARISON_REQUIRES_TWO_INSTANCES: save at least two instance evidence cards or declare PARTIAL",
            )
        cited = {eid for item in body["checks"] for eid in item.get("evidence_ids", [])}
        if plan["task_type"] == "RESEARCH" and not cited:
            raise HTTPException(422, "RESEARCH_EVIDENCE_REQUIRED: retrieve/read sources or declare PARTIAL")
        if plan.get("fulltext_coverage_required"):
            coverage = run.get("read_coverage", {})
            sources = plan.get("fulltext_documents", run.get("source_documents", []))
            authorized = {
                (item["document_id"], item["publication_id"]) for item in run.get("source_documents", [])
            }
            if not sources or any(
                (source["document_id"], source["publication_id"]) not in authorized
                or not fully_read(coverage.get(source["document_id"] + ":" + source["publication_id"], {}))
                for source in sources
            ):
                raise HTTPException(
                    422, "FULLTEXT_COVERAGE_INCOMPLETE: read remaining nodes or declare PARTIAL"
                )
    for check in body["checks"]:
        if any(eid not in run["evidence"] for eid in check.get("evidence_ids", [])):
            raise HTTPException(422, "Unknown evidence in completion review")
    return {**body, "verification": "AGENT_SELF_CHECK_NOT_HUMAN_VERIFICATION"}


def bound_document_page(result, args, max_characters, total_key="total_nodes"):
    """Deliver complete nodes, never count a workspace preview as reading them."""
    offset = args.get("offset", 0)
    total = result.get("trace", {}).get(total_key)
    if not isinstance(total, int) or total < 0 or offset < 0:
        raise HTTPException(502, "INVALID_DOCUMENT_PAGINATION")
    page = {**result, "candidates": [], "trace": {**result["trace"], "offset": offset}}
    for candidate in result.get("candidates", []):
        proposed = {**page, "candidates": [*page["candidates"], candidate]}
        proposed["next_offset"] = (
            offset + len(proposed["candidates"]) if offset + len(proposed["candidates"]) < total else None
        )
        if len(json.dumps(proposed, ensure_ascii=False, default=str)) > max_characters - 256:
            break
        page = proposed
    if not page["candidates"] and offset < total:
        raise HTTPException(
            413, "DOCUMENT_NODE_TOO_LARGE: cannot claim full-text delivery from a truncated node"
        )
    page["next_offset"] = (
        offset + len(page["candidates"]) if offset + len(page["candidates"]) < total else None
    )
    page["trace"]["delivered_nodes"] = len(page["candidates"])
    return page


def fully_read(value):
    total = value.get("total_nodes", 0)
    ranges = value.get("ranges", [])
    return total > 0 and bool(ranges) and ranges[0][0] == 0 and ranges[0][1] >= total


def read_progress(coverage):
    result = {}
    for key, value in coverage.items():
        total = value.get("total_nodes", 0)
        cursor, missing = 0, []
        for left, right in sorted(value.get("ranges", [])):
            left, right = max(0, min(left, total)), max(0, min(right, total))
            if left > cursor:
                missing.append([cursor, left])
            cursor = max(cursor, right)
        if cursor < total:
            missing.append([cursor, total])
        result[key] = {
            **value,
            "interval_semantics": "HALF_OPEN [start,end)",
            "read_nodes": total - sum(right - left for left, right in missing),
            "missing_ranges": missing,
            "next_unread_offset": missing[0][0] if missing else None,
        }
    return result


def record_read(run, args, result):
    total = result.get("trace", {}).get("total_nodes")
    if not isinstance(total, int) or total < 0:
        return
    key = args["document_id"] + ":" + args["publication_id"]
    coverage = run.setdefault("read_coverage", {}).setdefault(
        key, {"document_id": args["document_id"], "publication_id": args["publication_id"], "ranges": []}
    )
    coverage["total_nodes"] = total
    start = min(args.get("offset", 0), total)
    stop = min(start + len(result.get("candidates", [])), total)
    merged = []
    for left, right in sorted([*coverage["ranges"], [start, stop]]):
        if merged and left <= merged[-1][1]:
            merged[-1][1] = max(right, merged[-1][1])
        else:
            merged.append([left, right])
    coverage["ranges"] = merged


ACTIVITIES = {
    "search_projects": "查找获授权的项目",
    "get_project_snapshot": "读取项目状态快照",
    "list_project_stages": "读取项目阶段",
    "list_project_tasks": "读取项目任务",
    "get_task_detail": "核对任务详情",
    "retrieve_answer_context": "检索问答依据",
    "knowledge_search": "查找相关资料",
    "document_read": "阅读文献原文",
    "table_read": "核对原始表格",
    "evidence_score": "筛选与问题相关的证据",
    "evidence_save": "整理实例与比较依据",
    "matrix_record": "整理子问题证据矩阵",
    "report_check": "逐项核查报告事实与原文",
    "report_save": "保存带引用的研究报告",
    "artifact_save": "登记可下载成果",
    "sandbox_execute": "执行隔离数据分析",
    "external_search": "发现外部资料",
    "bibliography_lookup": "查询文献与 DOI",
}
