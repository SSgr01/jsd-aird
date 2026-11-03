import copy

import pytest
from fastapi import HTTPException

from aird_agent.capabilities.fast_qa.history import answer_context, qa_history
from aird_agent.capabilities.literature.rcs import normalize_citations as legacy_normalize
from aird_agent.capabilities.literature.rcs import validate_report as legacy_validate
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


def test_context_projects_only_registered_labels_without_mutating_retrieval():
    result = {
        "context": "[evidenceRef=K1] [evidenceRef=D2] [evidenceRef=W3] [evidenceRef=K9]",
        "candidates": [{"label": label, "evidence_id": EID} for label in ("K1", "D2", "W3")],
    }
    original = copy.deepcopy(result)
    assert answer_context(result, 1000) == (f"[E:{EID}] " * 3) + "[evidenceRef=K9]"
    assert result == original


@pytest.mark.parametrize("context", [None, {}, "x" * 101])
def test_context_rejects_invalid_or_oversized_host_output(context):
    with pytest.raises(HTTPException, match="INVALID_ANSWER_CONTEXT"):
        answer_context({"context": context}, 100)


def test_ambiguous_labels_cannot_silently_cite_the_first_source():
    with pytest.raises(HTTPException, match="AMBIGUOUS_ANSWER_CONTEXT_LABEL"):
        answer_context(
            {
                "context": "[evidenceRef=K1]",
                "candidates": [
                    {"label": "K1", "evidence_id": EID},
                    {"label": "K1", "evidence_id": "e" + "b" * 24},
                ],
            },
            100,
        )


def test_citations_share_the_legacy_contract_and_reject_unregistered_ids():
    evidence = {EID: {}}
    text = f"[{EID}] [E:{EID}]"
    assert normalize_citations(text, evidence) == f"[E:{EID}] [E:{EID}]"
    assert validate_report(text, evidence) == [EID]
    assert legacy_normalize is normalize_citations
    assert legacy_validate is validate_report
    assert normalize_citations("[e" + "b" * 24 + "]", evidence) == "[e" + "b" * 24 + "]"
    with pytest.raises(HTTPException, match="unknown evidence"):
        validate_report("[E:unknown]", evidence)
