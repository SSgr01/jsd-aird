import copy
import json
from pathlib import Path

import pytest
from fastapi import HTTPException

from aird_agent.evidence.citations import validate_generated
from aird_agent.evidence.quotes import quotes, resolve_quote
from aird_agent.evidence.registry import canonical_candidate, register_candidates


def test_generated_references_validate_canonical_ids_without_guessing():
    eid = "e" + "a" * 24
    text = '{"answer":"12 kPa [E:' + eid + ']", "usedEvidenceRefs":["K1"]}'
    assert validate_generated(text, {eid: {"label": "K1"}}, ["K1"]) == [eid]
    unknown = "[E:e" + "b" * 24 + "]"
    with pytest.raises(HTTPException):
        validate_generated(unknown, {eid: {}}, ["K1"])


def test_shared_protocol_vectors_preserve_ids_original_spans_and_aliases():
    fixture = Path(__file__).parents[2] / "contracts/evidence.json"
    for case in json.loads(fixture.read_text(encoding="utf8"))["cases"]:
        candidate = {key: value for key, value in case.items() if key != "quotes"}
        assert canonical_candidate(candidate) == candidate
        assert quotes(candidate) == case["quotes"]
        registry = register_candidates({}, [candidate, candidate])
        assert len(registry) == 1
        for quote in case["quotes"]:
            assert resolve_quote(registry, quote)["text"] == case["text"][quote["start"] : quote["end"]]
        altered = copy.deepcopy(candidate)
        altered["text"] += " forged"
        with pytest.raises(HTTPException, match="SOURCE_HASH_MISMATCH"):
            canonical_candidate(altered)
        altered = copy.deepcopy(candidate)
        altered["source"]["version_id"] = "changed"
        with pytest.raises(HTTPException, match="EVIDENCE_ID_MISMATCH"):
            canonical_candidate(altered)


def test_optional_source_field_serialization_preserves_existing_quote_handles():
    fixture = Path(__file__).parents[2] / "contracts/evidence.json"
    case = json.loads(fixture.read_text(encoding="utf8"))["cases"][0]
    original = {key: value for key, value in case.items() if key != "quotes"}
    changed = copy.deepcopy(original)
    changed["source"]["project_id"] = None
    assert canonical_candidate(changed)["evidence_id"] == original["evidence_id"]
    registry = register_candidates({original["evidence_id"]: original}, [changed])
    assert registry[original["evidence_id"]]["source"] == original["source"]
    assert quotes(registry[original["evidence_id"]]) == case["quotes"]
    assert resolve_quote(registry, case["quotes"][0])["text"] == case["quotes"][0]["text"]
