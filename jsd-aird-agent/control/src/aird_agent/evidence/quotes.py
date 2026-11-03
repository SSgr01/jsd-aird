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
