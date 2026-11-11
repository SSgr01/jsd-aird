"""Immutable exact source spans; existing quote identity remains unchanged."""

import hashlib
import re

from fastapi import HTTPException

from aird_agent.core.contracts import digest


def quotes(candidate):
    """Deterministic contiguous source spans, with no punctuation/unit rewriting."""
    text = candidate["text"]
    if hashlib.sha256(text.encode()).hexdigest() != candidate["source"]["content_sha256"]:
        raise HTTPException(422, "QUOTE_SOURCE_HASH_MISMATCH")
    boundaries = [
        0,
        *[m.end() for m in re.finditer(r"\n+|(?<=[.!?。；;])\s+(?=[A-Z\u4e00-\u9fff])", text)],
        len(text),
    ]
    result = []
    for left, right in zip(boundaries, boundaries[1:]):
        while left < right:
            end = min(right, left + 900)
            if end < right:
                space = text.rfind(" ", left + 400, end)
                if space > left:
                    end = space + 1
            start = left
            while start < end and text[start].isspace():
                start += 1
            stop = end
            while stop > start and text[stop - 1].isspace():
                stop -= 1
            if stop > start:
                qid = "q" + digest([candidate["evidence_id"], candidate["source"], start, stop])[:24]
                result.append(
                    {
                        "quote_id": qid,
                        "evidence_id": candidate["evidence_id"],
                        "start": start,
                        "end": stop,
                        "text": text[start:stop],
                    }
                )
            left = end
    return result


def resolve_quote(evidence, ref):
    candidate = evidence.get(ref.get("evidence_id"))
    if candidate:
        for quote in quotes(candidate):
            if quote["quote_id"] == ref.get("quote_id"):
                return {**quote, "source": candidate["source"]}
    raise HTTPException(422, "QUOTE_HANDLE_INVALID_OR_WRONG_EVIDENCE")


def table_headers(evidence, candidate):
    """Only the trusted host's confirmed table relation can join independent rows."""
    table = candidate.get("table_context") or {}
    wanted = table.get("header_node_keys", [])
    if not wanted:
        return [], []
    source = candidate["source"]
    identity = ("kind", "document_id", "publication_id", "version_id")
    headers = {
        item["source"]["source_node_key"]: item
        for item in evidence.values()
        if all(item["source"].get(k) == source.get(k) for k in identity)
        and item["source"].get("source_node_key") in wanted
        and (item.get("table_context") or {}).get("table_key") == table.get("table_key")
    }
    return [headers[key] for key in wanted if key in headers], [key for key in wanted if key not in headers]


def review_quotes(evidence, candidate):
    headers, missing = table_headers(evidence, candidate)
    if missing:
        raise HTTPException(422, "REPORT_TABLE_HEADER_REQUIRED: read evidence_context for " + candidate["evidence_id"])
    result = {}
    for item in [*headers, candidate]:
        table = item.get("table_context")
        for span in quotes(item):
            value = {**span, "source": item["source"]}
            if table:
                value["table_row"] = {
                    "table_key": table["table_key"], "row_index": table["row_index"],
                    "is_header": item["source"]["source_node_key"] in table["header_node_keys"],
                }
            result[span["quote_id"]] = value
    return list(result.values())
