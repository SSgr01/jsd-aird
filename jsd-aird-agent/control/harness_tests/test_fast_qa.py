import copy

import pytest
from fastapi import HTTPException

from aird_agent.evidence.retrieval_context import qa_history
from aird_agent.evidence.citations import normalize_citations, validate_report
from aird_agent.hosts.jsd.composition import Service

EID = "e" + "a" * 24


def test_history_reconstructs_turns_and_excludes_current_and_queued_questions():
    conversation = {
        "messages": [
            {"run_id": "prior", "role": "user", "text": "previous question"},
            {"run_id": "future", "role": "user", "text": "queued question", "status": "QUEUED"},
            {"run_id": "pending", "role": "user", "text": "pending steer", "status": "PENDING"},
            {"run_id": "current", "role": "user", "text": "current question"},
            {"run_id": "prior", "role": "assistant", "text": "previous answer"},
            {"run_id": "current", "role": "assistant", "text": "partial answer"},
        ]
    }
    original = copy.deepcopy(conversation)
    expected = [
        {"role": "user", "content": "previous question"},
        {"role": "assistant", "content": "previous answer"},
    ]
    assert qa_history(conversation, {"id": "current"}) == expected
    assert Service.qa_history(conversation, {"id": "current"}) == expected
    assert conversation == original


def test_history_is_bounded_without_including_tool_messages():
    messages = [
        {"run_id": str(i), "role": role, "text": "x" * 5000}
        for i in range(6)
        for role in ("user", "assistant", "tool")
    ]
    history = qa_history({"messages": messages}, {"id": "next"})
    assert len(history) == 8
    assert all(item["role"] in {"user", "assistant"} and len(item["content"]) == 4000 for item in history)


def test_citations_normalize_registered_ids_and_reject_unregistered_ids():
    evidence = {EID: {}}
    text = f"[{EID}] [E:{EID}]"
    assert normalize_citations(text, evidence) == f"[E:{EID}] [E:{EID}]"
    assert validate_report(text, evidence) == [EID]
    assert normalize_citations("[e" + "b" * 24 + "]", evidence) == "[e" + "b" * 24 + "]"
    with pytest.raises(HTTPException, match="unknown evidence"):
        validate_report("[E:unknown]", evidence)


def test_citation_spacing_preserves_exact_identity_and_rejects_unknown_sources():
    evidence = {EID: {}}
    text = f"A [ E:{EID} ] B [E: {EID}] C [ {EID} ] D 〔E:{EID}〕"
    assert normalize_citations(text, evidence) == f"A [E:{EID}] B [E:{EID}] C [E:{EID}] D [E:{EID}]"
    assert validate_report(text, evidence) == [EID]
    unknown = "e" + "b" * 24
    for value in (f"[ E:{unknown} ]", f"[ {unknown} ]", f"〔E:{unknown}〕"):
        assert normalize_citations(value, evidence) == value
        with pytest.raises(HTTPException, match="unknown evidence"):
            validate_report(value, evidence)
