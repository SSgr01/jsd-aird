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
    missing = mentioned - available.keys()
    if missing:
        locations = []
        files = inputs.get("files", [])
        for index, content in enumerate(input_contents):
            text = content.decode("utf8", errors="ignore")
            path = files[index].get("path", "input") if index < len(files) else "output"
            for match in re.finditer(r"(?<![a-zA-Z0-9_-])q[0-9a-f]{24}(?![a-zA-Z0-9_-])", text):
                if match[0] in missing and len(locations) < 8:
                    line = text.count("\n", 0, match.start()) + 1
                    locations.append(f"{path}:{line} {match[0]}")
        detail = "; ".join(locations) or ", ".join(sorted(missing)[:8])
        raise HTTPException(422, "ANALYSIS_SOURCE_QUOTES_REQUIRED: unknown quote handles: " + detail
                            + ". Replace with supporting quotes registered in this run. "
                            "Remove obsolete handles even from change notes or code spans; "
                            "describe removed citations in plain words. Then save and present the corrected file.")
    for ref in explicit_quotes:
        if available[ref["quote_id"]]["evidence_id"] != ref["evidence_id"]:
            raise HTTPException(422, "QUOTE_HANDLE_INVALID_OR_WRONG_EVIDENCE")
    # Selecting a complete evidence input preserves its source even without a separate quote handle.
    for eid in selected:
        candidate = evidence[eid]
        if candidate["source"].get("kind") in LITERATURE_KINDS:
            # A cited evidence/quote pair selects that span. Its evidence identity
            # is not an instruction to export every other span in the source.
            if any(available[qid]["evidence_id"] == eid for qid in mentioned):
                continue
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
