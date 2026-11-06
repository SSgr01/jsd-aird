"""Business delivery, source identity and reading coverage."""

import json

from fastapi import HTTPException

from aird_agent.capabilities.files.completion import check_file_completion
from aird_agent.core.contracts import digest


def scope_digest(topic):
    documents = list(
        {(item["document_id"], item["publication_id"]): item for item in topic.get("documents", [])}.values()
    )
    value = {"documents": documents, "mode": topic.get("scope_mode", "SELECTED")}
    if "context_refs" in topic:
        value.update(context_refs=topic["context_refs"], retrieval_scope=topic.get("retrieval_scope", {}))
    if topic.get("private_source_ids"):
        value["private_source_ids"] = sorted(topic["private_source_ids"])
    return digest(value)


def selected_sources(run):
    """Explicit document scope is host input, independent of the model's plan type."""
    packs = run.get("pack_ids")
    if packs is not None and not {"literature", "private-literature"}.intersection(packs):
        return set()
    sources = {
        (ref["resource_id"], ref.get("publication_id"))
        for ref in run.get("context_refs", [])
        if ref.get("kind") == "KB_PUBLICATION"
    }
    sources.update(
        (item["document_id"], item.get("publication_id"))
        for item in run.get("private_documents", [])
    )
    if run.get("topic_id") and not run.get("context_refs"):
        sources.update((item["document_id"], item.get("publication_id")) for item in run.get("source_documents", []))
    return sources


def selected_evidence(run):
    sources = selected_sources(run)
    return {
        eid for eid, candidate in run.get("evidence", {}).items()
        if candidate.get("text", "").strip()
        and candidate.get("source", {}).get("kind") in {"KB_PUBLICATION", "RUN_UPLOAD", "EXTERNAL_FULLTEXT"}
        and any(
            candidate["source"].get("document_id") == doc
            and (publication is None or candidate["source"].get("publication_id") == publication)
            for doc, publication in sources
        )
    }


def check_delivery(run):
    """Check business receipts and explicit requirements; dsh owns plans and turn completion."""
    if selected_sources(run) and not selected_evidence(run):
        raise HTTPException(422, "SELECTED_SOURCE_READING_REQUIRED")
    requirements = run.get("research_requirements") or {}
    required_sources = requirements.get("fulltext_documents", [])
    questions = set(requirements.get("question_ids", [])) | requirements.get("question_targets", {}).keys()
    if questions - run.get("research_matrix", {}).get("questions", {}).keys():
        raise HTTPException(422, "MATRIX_QUESTIONS_INCOMPLETE")
    if required_sources:
        coverage = run.get("read_coverage", {})
        if any(not fully_read(coverage.get(item["document_id"] + ":" + item["publication_id"], {})) for item in required_sources):
            raise HTTPException(422, "FULLTEXT_COVERAGE_INCOMPLETE")
        if requirements.get("require_rcs"):
            scored = {(item["source"]["document_id"], item["source"].get("publication_id")) for item in run.get("evidence_contexts", [])}
            if any((item["document_id"], item["publication_id"]) not in scored for item in required_sources):
                raise HTTPException(422, "RCS_COVERAGE_INCOMPLETE")
    outputs = run.get("registered_outputs", [])
    for option, kind in (("require_report", "report"), ("require_cards", "card")):
        if not requirements.get(option):
            continue
        covered = {(ref["document_id"], ref["publication_id"]) for output in outputs if output["kind"] == kind for ref in output.get("publications", [])}
        missing = [ref for ref in required_sources if (ref["document_id"], ref["publication_id"]) not in covered]
        if missing or not any(output["kind"] == kind for output in outputs):
            raise HTTPException(422, "USER_DELIVERABLES_MISSING: " + kind + " for " + json.dumps(missing))
    check_file_completion(run)
    if (requirements.get("require_report") or run.get("report_requested")) and run.get("pack_versions", {}).get("literature", {}).get("version") == "4":
        checks = run.get("report_checks", {})
        if not any(
            output.get("kind") == "report"
            and (review := output.get("quality_review", {})).get("passed")
            and review.get("matrix_revision") == run.get("research_matrix", {}).get("revision", 0)
            and checks.get(review.get("report_hash"), {}).get("passed")
            and checks.get(review.get("report_hash"), {}).get("quality_check_version") == 3
            for output in outputs
        ):
            raise HTTPException(422, "CURRENT_MATRIX_CHECKED_REPORT_REQUIRED")


def bound_document_page(result, args, max_characters, total_key="total_nodes"):
    """Deliver complete nodes, never count a workspace preview as reading them."""
    from aird_agent.evidence.metadata import attach_metadata
    from aird_agent.evidence.presentation import model_result

    metadata = result.get("document_metadata", [])
    result = {key: value for key, value in result.items() if key != "document_metadata"}
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
        if len(json.dumps(model_result(proposed), ensure_ascii=False, default=str)) > max_characters - 256:
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
    return attach_metadata(page, metadata, max_characters, projection=model_result)


def fully_read(value):
    total = value.get("total_nodes", 0)
    ranges = value.get("ranges", [])
    return total > 0 and bool(ranges) and ranges[0][0] == 0 and ranges[0][1] >= total


def source_catalog(run):
    """Recovery handles only; canonical text and permission checks stay in the ledger."""
    groups = {}
    for item in run.get("evidence", {}).values():
        source = item["source"]
        key = tuple(source.get(field) for field in ("kind", "document_id", "publication_id", "version_id"))
        groups.setdefault(key, []).append(item)
    return [
        {
            **{
                field: items[0]["source"].get(field)
                for field in ("kind", "document_id", "publication_id", "version_id")
            },
            "title": items[0]["source"].get("title", "")[:160],
            "evidence_count": len(items),
            "reading_handles": [
                {"evidence_id": item["evidence_id"], "node_offset": item.get("node_offset")}
                for item in sorted(items, key=lambda value: len(value.get("text", "")), reverse=True)[:3]
            ],
        }
        for items in list(groups.values())[-6:]
    ]


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
    "dsh_execute": "执行隔离数据分析",
    "external_search": "发现外部资料",
    "bibliography_lookup": "查询文献与 DOI",
    "literature_search": "查找相关论文",
    "literature_expand": "查找相关引用文献",
    "literature_acquire": "获取论文全文",
    "literature_acquire_status": "检查全文是否准备好",
}
