"""Resolve export provenance from selected inputs, never the entire run's sources."""

import re

from fastapi import HTTPException

from aird_agent.core.contracts import digest
from aird_agent.evidence.quotes import quotes

LITERATURE_KINDS = {"KB_PUBLICATION", "RUN_UPLOAD", "EXTERNAL_FULLTEXT"}


def export_sources(run, explicit_quotes, inputs, input_contents):
    evidence = run.get("evidence", {})
    selected = set()
    mentioned = set(inputs.get("quote_ids", [])) | {ref["quote_id"] for ref in explicit_quotes}
    for content in input_contents:
        text = content.decode("utf8", errors="ignore")
        mentioned.update(re.findall(r"(?<![a-zA-Z0-9_-])q[0-9a-f]{24}(?![a-zA-Z0-9_-])", text))
        selected.update(re.findall(r"(?<![a-zA-Z0-9_-])e[0-9a-f]{24}(?![a-zA-Z0-9_-])", text))
    if selected - evidence.keys():
        raise HTTPException(422, "EXPORT_SOURCE_INVALID: selected evidence is not registered in this run")
    if not selected and not mentioned:
        return [], []
    available = {
        q["quote_id"]: {**q, "source": candidate["source"]}
        for candidate in evidence.values()
        for q in quotes(candidate)
    }
    if mentioned - available.keys():
        raise HTTPException(422, "ANALYSIS_SOURCE_QUOTES_REQUIRED: use registered quotes from selected inputs")
    for ref in explicit_quotes:
        if available[ref["quote_id"]]["evidence_id"] != ref["evidence_id"]:
            raise HTTPException(422, "QUOTE_HANDLE_INVALID_OR_WRONG_EVIDENCE")
    # Selecting a complete evidence input preserves its source even without a separate quote handle.
    for eid in selected:
        candidate = evidence[eid]
        if candidate["source"].get("kind") in LITERATURE_KINDS:
            candidate_quotes = quotes(candidate)
            if not candidate_quotes:
                raise HTTPException(422, "ANALYSIS_SOURCE_QUOTES_REQUIRED: selected source has no original quotes")
            mentioned.update(q["quote_id"] for q in candidate_quotes)
    resolved = [available[qid] for qid in sorted(mentioned)]
    sources = {digest(q["source"]): q["source"] for q in resolved}
    for eid in selected:
        source = evidence[eid]["source"]
        sources[digest(source)] = source
    return resolved, list(sources.values())
