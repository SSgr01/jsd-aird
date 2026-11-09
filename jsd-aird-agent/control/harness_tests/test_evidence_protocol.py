import copy
import json
from pathlib import Path

import pytest
from fastapi import HTTPException

from aird_agent.capabilities.fast_qa.history import answer_context
from aird_agent.evidence.quotes import quotes, resolve_quote
from aird_agent.evidence.registry import alias_map, canonical_candidate, register_candidates


def test_known_canonical_answer_references_project_to_display_aliases_without_guessing():
    from aird_agent.evidence.citations import project_aliases, validate_generated

    eid = "e" + "a" * 24
    text = '{"answer":"12 kPa [E:' + eid + ']", "usedEvidenceRefs":["' + eid + '"]}'
    result = project_aliases(text, {"K1": eid})
    value = json.loads(result)
    assert value["answer"] == "12 kPa [[K1]]"
    assert value["usedEvidenceRefs"] == ["K1"]
    validate_generated(result, {eid: {}}, ["K1"])
    unknown = "[E:e" + "b" * 24 + "]"
    assert project_aliases(unknown, {"K1": eid}) == unknown
    with pytest.raises(HTTPException):
        validate_generated(unknown, {eid: {}}, ["K1"])


def test_external_display_aliases_map_to_canonical_ids_without_breaking_legacy_web_labels():
    eid = "e" + "a" * 24
    for label in ("E1", "W1"):
        projected = answer_context(
            {
                "context": "[evidenceRef=" + label + "] original",
                "candidates": [{"label": label, "evidence_id": eid}],
            },
            1000,
        )
        assert projected == "[E:" + eid + "] original"


def test_shared_protocol_vectors_preserve_ids_original_spans_and_aliases():
    fixture = Path(__file__).parents[2] / "contracts/evidence.json"
    for case in json.loads(fixture.read_text(encoding="utf8"))["cases"]:
        candidate = {key: value for key, value in case.items() if key != "quotes"}
        assert canonical_candidate(candidate) == candidate
        assert quotes(candidate) == case["quotes"]
        assert alias_map([candidate]) == {case["label"]: case["evidence_id"]}
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
