"""Canonical evidence registration; never grants access or marks reading coverage."""

import copy
import hashlib

from fastapi import HTTPException

from aird_agent.core.contracts import digest
from aird_agent.evidence.models import SourceRef


def canonical_candidate(candidate):
    source = SourceRef.model_validate(candidate["source"])
    text = candidate["text"]
    if hashlib.sha256(text.encode()).hexdigest() != source.content_sha256:
        raise HTTPException(502, "SOURCE_HASH_MISMATCH")
    eid = "e" + digest(source.model_dump(mode="json"))[:24]
    if candidate.get("evidence_id") not in (None, eid):
        raise HTTPException(422, "EVIDENCE_ID_MISMATCH")
    # Keep the original source shape: existing quote IDs include that exact shape.
    return {**candidate, "evidence_id": eid}


def register_candidates(evidence, candidates):
    updated = dict(evidence)
    for candidate in candidates:
        item = canonical_candidate(candidate)
        previous = updated.get(item["evidence_id"])
        if previous is not None:
            if previous["text"] != item["text"]:
                raise HTTPException(409, "SOURCE_BODY_CHANGED")
            # Quote handles include the original source shape. Missing optional keys
            # and explicit nulls share a canonical ID, but must not rewrite old handles.
            item["source"] = copy.deepcopy(previous["source"])
        updated[item["evidence_id"]] = item
    if len(updated) > 500 or sum(len(c["text"]) for c in updated.values()) > 2_000_000:
        raise HTTPException(409, "EVIDENCE_BUDGET_EXCEEDED")
    return updated


def alias_map(candidates):
    aliases = {}
    for candidate in candidates:
        item = canonical_candidate(candidate)
        label = item["label"]
        if label in aliases:
            raise HTTPException(422, "AMBIGUOUS_ANSWER_CONTEXT_LABEL")
        aliases[label] = item["evidence_id"]
    return aliases
