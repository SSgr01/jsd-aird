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


def model_candidate(candidate, links=None):
    return {key: value for key, value in {
        "evidence_id": candidate.get("evidence_id"),
        **source_label(candidate.get("source") or {}),
        "offset": candidate.get("node_offset"),
        "text": candidate.get("model_text") if candidate.get("model_text") is not None else candidate.get("text"),
        "ref": candidate.get("ref"), "relation": candidate.get("relation"),
        "quote_id": candidate.get("quote_id"),
        **({"url": links[candidate["evidence_id"]]["url"]}
           if links and candidate.get("evidence_id") in links
           and not document_identity(source_label(candidate.get("source") or {})) else {}),
        **{key: candidate[key] for key in ("already_retrieved", "quote_count", "table_context") if key in candidate},
        **{key: candidate[key] for key in ("record_key", "field", "value", "unit", "value_type", "condition", "source_status") if key in candidate},
    }.items() if value is not None}


def model_hit(hit):
    if not hit.get("source") or not any(key in hit for key in (
        "source_text", "quote", "context_quotes", "already_retrieved", "requires_read",
    )):
        return _model_result(hit)
    quotes = sorted([*hit.get("context_quotes", []), *([hit["quote"]] if hit.get("quote") else [])],
                    key=lambda quote: quote.get("start", 0))
    return {
        "evidence_id": hit.get("evidence_id"), **source_label(hit["source"]),
        **{key: hit[key] for key in (
            "node_offset", "quote_offset", "quote_count", "table_context", "requires_read", "already_retrieved",
        ) if key in hit},
        **({"text": hit.get("source_text") or "\n".join(quote["text"] for quote in quotes)}
           if hit.get("source_text") or quotes else {}),
        **({"quote_id": hit.get("quote_id") or hit["quote"]["quote_id"]}
           if hit.get("quote_id") or (hit.get("quote") or {}).get("quote_id") else {}),
    }


def document_identity(value):
    if (value.get("document_id") and not value.get("resource_type")
            and (value.get("publication_id") or value.get("version_id"))):
        return tuple(value.get(key) for key in ("document_id", "publication_id", "version_id"))
    return None


def compact_document_sources(value):
    """Declare each fixed document once per result; fragment locations stay beside their text.

    References are local to this result so replay, restored sessions and native
    compaction never depend on a hidden source-declaration cache.
    """
    if isinstance(value, list):
        return [compact_document_sources(item) for item in value]
    if not isinstance(value, dict):
        return value
    sources = {}
    shared = {"title", "document_id", "publication_id", "version_id"}

    def visit(item):
        if isinstance(item, list):
            return [visit(child) for child in item]
        if not isinstance(item, dict):
            return item
        key = document_identity(item)
        if key:
            if key not in sources:
                sources[key] = {"ref": "d" + str(len(sources) + 1),
                                **{name: field for name, field in item.items() if name in shared}}
            return {"document_ref": sources[key]["ref"],
                    **{name: visit(field) for name, field in item.items() if name not in shared}}
        output = {name: visit(field) for name, field in item.items() if name != "document_metadata"}
        for metadata in item.get("document_metadata", []):
            key = document_identity(metadata)
            if key:
                if key not in sources:
                    sources[key] = {"ref": "d" + str(len(sources) + 1)}
                sources[key].update({name: field for name, field in metadata.items() if name != "ref"})
            else:
                output.setdefault("document_metadata", []).append(visit(metadata))
        return output

    output = visit(value)
    if sources:
        output["document_sources"] = list(sources.values())
    return output


def model_result(result):
    return compact_document_sources(_model_result(result))


def _model_result(result):
    if isinstance(result, list):
        return [_model_result(item) for item in result]
    if not isinstance(result, dict):
        return result
    output = {}
    for key, value in result.items():
        if key in {"workspace_path", "content_sha256", "content_base64", "retrieval_trace", "source_refs", "citation_links"}:
            continue
        if key in {"candidates", "context_candidates"} and isinstance(value, list):
            output[key] = [model_candidate(item, result.get("citation_links")) for item in value]
        elif key == "hits" and isinstance(value, list):
            output[key] = [model_hit(item) for item in value]
        elif key == "entries" and isinstance(value, list) and "total_nodes" in (result.get("trace") or {}):
            output[key] = [{
                **{name: entry[name] for name in ("node_offset", "end_offset_exclusive", "kind", "title") if name in entry},
                **{name: item for name, item in source_label({"anchor": entry.get("anchor", {})}).items()
                   if name in {"page", "section"}},
            } for entry in value]
        elif key == "source" and isinstance(value, dict):
            output[key] = source_label(value)
        elif key == "trace" and isinstance(value, dict):
            output[key] = {name: value[name] for name in (
                "mode", "total_nodes", "offset", "read_at", "status", "next_cursor", "total", "section",
                "ranking_scope", "fulltext_coverage", "coverage_note",
                "new_evidence_count", "new_source_versions", "cache_hit", "reused_evidence_count", "omitted_characters",
            ) if name in value}
        else:
            output[key] = _model_result(value)
    return output
