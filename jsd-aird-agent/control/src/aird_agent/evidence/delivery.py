"""Run-local receipts of source text returned to the model, separate from reading coverage."""

import copy

from aird_agent.evidence.quotes import quotes

READ_TOOLS = frozenset({
    "knowledge_search", "private_document_search", "retrieve_context", "document_read", "private_document_read",
    "table_read", "evidence_read", "evidence_context",
})
DOCUMENT_KINDS = frozenset({"KB_PUBLICATION", "RUN_UPLOAD", "EXTERNAL_FULLTEXT"})


def covered(ranges, start, end):
    return any(left <= start and right >= end for left, right in ranges)


def merge_ranges(ranges, start, end):
    merged = []
    for left, right in sorted([*ranges, [start, end]]):
        if merged and left <= merged[-1][1]:
            merged[-1][1] = max(right, merged[-1][1])
        else:
            merged.append([left, right])
    return merged


def delivery_snapshot(run, result):
    """Preview compaction without recording delivery or duplicating source bodies."""
    return {**run, "evidence": {**run.get("evidence", {}), **{
        item["evidence_id"]: item for group in ("candidates", "context_candidates")
        for item in result.get(group, []) if item.get("evidence_id") and item.get("text") is not None
    }}}


def delivery_result(run, name, arguments, result):
    """Suppress only previously returned exact spans of the same immutable evidence ID.

    Called under the run transaction when committing a successful tool result.
    The evidence ledger and authoritative result remain untouched. Search windows
    stay atomic so a newly returned value retains its sample and test conditions.
    """
    if name not in READ_TOOLS:
        return result, False
    output = copy.deepcopy(result)
    receipts = copy.deepcopy(run.get("evidence_delivery", {}))
    evidence = run.get("evidence", {})
    refresh = arguments.get("refresh") is True
    reused, omitted = set(), 0

    def candidate(eid):
        item = evidence.get(eid)
        return item if item and item.get("source", {}).get("kind") in DOCUMENT_KINDS else None

    def seen(eid, start, end):
        return not refresh and covered(receipts.get(eid, []), start, end)

    def record(eid, start, end):
        if end > start:
            receipts[eid] = merge_ranges(receipts.get(eid, []), start, end)

    def spans_for(eid, spans):
        item = candidate(eid)
        if not item:
            return []
        return [span for span in spans if isinstance(span, dict)
                and isinstance(span.get("start"), int) and isinstance(span.get("end"), int)
                and 0 <= span["start"] < span["end"] <= len(item["text"])
                and item["text"][span["start"]:span["end"]] == span.get("text")]

    def omit(eid, text):
        nonlocal omitted
        reused.add(eid)
        omitted += len(text)

    def whole_node(item):
        eid = item.get("evidence_id")
        known = candidate(eid)
        # Alternate business/model summaries are not canonical source bodies.
        if not known or item.get("text") != known["text"] or "model_text" in item:
            return item
        text = item["text"]
        if text and seen(eid, 0, len(text)):
            omit(eid, text)
            return {**{key: value for key, value in item.items() if key != "text"},
                    "already_retrieved": True, "quote_count": len(quotes(known))}
        record(eid, 0, len(text))
        return item

    # The joint retrieval endpoint includes both labelled context and the same
    # canonical candidate bodies. Replace only exact repetitions in their own
    # labelled block; retain lookup status, formula checks, images and ambiguity.
    if name == "retrieve_context" and isinstance(output.get("context"), str):
        context = output["context"]
        for item in output.get("candidates", []):
            eid, label = item.get("evidence_id"), item.get("label")
            known = evidence.get(eid)
            if (not known or not label or "model_text" in item
                    or item.get("text") != known.get("text")):
                continue
            marker = "[evidenceRef=" + label + "]"
            start = context.find(marker)
            if start < 0:
                continue
            stop = context.find("[evidenceRef=", start + len(marker))
            stop = len(context) if stop < 0 else stop
            body = item["text"].replace("\r\n", "\n").replace("\r", "\n").strip()
            offset = context.find(body, start + len(marker), stop) if body else -1
            if offset >= 0:
                context = context[:offset] + "[E:" + eid + "]" + context[offset + len(body):]
                omit(eid, body)
        output["context"] = context

    for group in ("candidates", "context_candidates"):
        if group in output:
            output[group] = [whole_node(item) for item in output[group]]

    # Parents have a body and a handle, but no reading-coverage authority.
    if "parent_context" in output:
        output["parent_context"] = [whole_node(item) for item in output["parent_context"]]
        old = [item["evidence_id"] for item in output["parent_context"] if item.get("already_retrieved")]
        if old:
            output["parent_evidence_ids"] = list(dict.fromkeys([*output.get("parent_evidence_ids", []), *old]))
            output["parent_context"] = [item for item in output["parent_context"] if not item.get("already_retrieved")]

    for hit in output.get("hits", []):
        eid = hit.get("evidence_id")
        window = [*hit.get("context_quotes", []), *([hit["quote"]] if hit.get("quote") else [])]
        spans = spans_for(eid, window)
        if not spans or len(spans) != len(window):
            continue
        if all(seen(eid, span["start"], span["end"]) for span in spans):
            if hit.get("quote"):
                hit["quote_id"] = hit["quote"]["quote_id"]
            omit(eid, "".join(span["text"] for span in spans))
            for key in ("quote", "context_quotes", "source_text"):
                hit.pop(key, None)
            hit["already_retrieved"] = True
        else:
            for span in spans:
                record(eid, span["start"], span["end"])

    # Preserve quote IDs/offsets even when a prior document read supplied the text.
    if "quotes" in output:
        eid = output.get("evidence_id")
        headers = output.get("table_context", {}).get("headers", [])
        new_headers = any(not seen(header.get("evidence_id"), span["start"], span["end"])
                          for header in headers
                          for span in spans_for(header.get("evidence_id"), header.get("quotes", [])))
        new_quotes = 0
        for span in spans_for(eid, output["quotes"]):
            was_seen = seen(eid, span["start"], span["end"])
            if was_seen and not new_headers:
                omit(eid, span.pop("text"))
                span["already_retrieved"] = True
            else:
                record(eid, span["start"], span["end"])
                new_quotes += int(not was_seen)
        output["new_quote_count"] = new_quotes
        # New table rows must keep their confirmed column headers, even if a
        # previous row used them. When the row is reused, headers may be handles.
        for header in headers:
            for span in spans_for(header.get("evidence_id"), header.get("quotes", [])):
                header_id = header["evidence_id"]
                if not new_quotes and not new_headers and seen(header_id, span["start"], span["end"]):
                    omit(header_id, span.pop("text"))
                    span["already_retrieved"] = True
                else:
                    record(header_id, span["start"], span["end"])

    # A preview exposes only this prefix, not the complete registered node.
    contexts = [output, output.get("context")]
    for context in contexts:
        if not isinstance(context, dict):
            continue
        for node in context.get("nodes", []):
            eid, text = node.get("evidence_id"), node.get("preview")
            known = candidate(eid)
            if not known or not text or not known["text"].startswith(text):
                continue
            if seen(eid, 0, len(text)):
                omit(eid, node.pop("preview"))
                node["already_retrieved"] = True
            else:
                record(eid, 0, len(text))

    changed = receipts != run.get("evidence_delivery", {})
    if changed:
        run["evidence_delivery"] = receipts
    if reused:
        output.setdefault("trace", {}).update(reused_evidence_count=len(reused), omitted_characters=omitted)
    return output, changed
