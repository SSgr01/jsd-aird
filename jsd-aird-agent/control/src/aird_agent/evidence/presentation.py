"""Readable model projection; authoritative source identity stays in the business ledger."""


def source_label(source):
    anchor = source.get("anchor") or {}
    original = anchor.get("original") or {}
    return {key: value for key, value in {
        "title": source.get("title"),
        **{key: source[key] for key in ("document_id", "publication_id", "version_id", "resource_type", "resource_id", "project_id") if key in source},
        "page": anchor.get("page_no") or anchor.get("page") or original.get("page_no") or original.get("page"),
        "section": anchor.get("section_title") or anchor.get("section"),
        "sheet": anchor.get("sheetName"), "cell": anchor.get("cellAddress"),
    }.items() if value is not None}


def model_candidate(candidate):
    return {key: value for key, value in {
        "evidence_id": candidate.get("evidence_id"),
        **source_label(candidate.get("source") or {}),
        "offset": candidate.get("node_offset"), "text": candidate.get("text"),
        "quote_id": candidate.get("quote_id"),
        **{key: candidate[key] for key in ("record_key", "field", "value", "unit", "value_type", "condition", "source_status") if key in candidate},
    }.items() if value is not None}


def model_result(result):
    if isinstance(result, list):
        return [model_result(item) for item in result]
    if not isinstance(result, dict):
        return result
    output = {}
    for key, value in result.items():
        if key in {"workspace_path", "content_sha256", "retrieval_trace", "source_refs", "citation_links"}:
            continue
        if key in {"candidates", "context_candidates"} and isinstance(value, list):
            output[key] = [model_candidate(item) for item in value]
        elif key == "source" and isinstance(value, dict):
            output[key] = source_label(value)
        elif key == "trace" and isinstance(value, dict):
            output[key] = {name: value[name] for name in (
                "mode", "total_nodes", "offset", "read_at", "status", "next_cursor", "total", "section",
            ) if name in value}
        else:
            output[key] = model_result(value)
    return output
