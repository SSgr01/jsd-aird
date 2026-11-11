from unittest.mock import AsyncMock

import pytest
from fastapi import HTTPException
from test_document_metadata import document

from aird_agent.hosts.jsd.bindings import JsdBindings
from aird_agent.hosts.jsd.task_state import check_delivery


def scoped_run():
    candidate, metadata = document()
    source = candidate["source"]
    return {"id": "run", "epoch": 1, "profile": "agent", "pack_ids": ["core", "literature"],
            "request": "这篇文章对我们的涂层有什么参考价值？",
            "context_refs": [{"kind": "KB_PUBLICATION", "resource_id": source["document_id"],
                              "publication_id": source["publication_id"]}],
            "document_metadata": [metadata], "evidence": {}, "source_documents": [],
            "completion": {"outcome": "COMPLETE"}}, candidate





def test_native_turn_cannot_bypass_selected_source_reading_and_citations():
    run, candidate = scoped_run()
    binding = JsdBindings()
    for evidence in ({}, {candidate["evidence_id"]: {**candidate, "source": {**candidate["source"], "publication_id": "wrong-version"}}}):
        run["evidence"] = evidence
        with pytest.raises(HTTPException, match="SELECTED_SOURCE_READING_REQUIRED"):
            check_delivery(run)
    run["evidence"] = {candidate["evidence_id"]: candidate}
    assert check_delivery(run) is None
    assert not binding.validate_output(run, "A finished analysis")[1]
    assert binding.validate_output(run, f"A bounded finding [E:{candidate['evidence_id']}]")[1]


def test_plain_file_delivery_remains_independent_of_literature_requirements():
    run, _ = scoped_run()
    run["pack_ids"] = ["core", "files"]
    assert check_delivery(run) is None


def test_closing_message_does_not_repeat_a_cited_answer_or_borrow_an_old_reply():
    run, candidate = scoped_run()
    run["evidence"] = {candidate["evidence_id"]: candidate}
    run["assistant_messages"] = [
        {"text": f"The selected source supports this finding [E:{candidate['evidence_id']}]",
         "after_message_seq": 0},
        {"text": "The answer is ready.", "after_message_seq": 0},
    ]
    binding = JsdBindings()
    assert binding.validate_output(run, "The answer is ready.")[1]
    # A new user instruction cannot inherit the previous answer's citations.
    run["assistant_messages"] = [{"text": "The answer is ready."}]
    assert not binding.validate_output(run, "The answer is ready.")[1]
    # Neither an unrecorded answer nor an explicitly forged final citation gets
    # the closing-message fallback.
    assert not binding.validate_output(run, "An unrelated answer.")[1]
    forged = "The answer is ready. [E:e" + "0" * 24 + "]"
    run["assistant_messages"][-1]["text"] = forged
    assert not binding.validate_output(run, forged)[1]


def test_partial_findings_keep_a_source_link_but_an_explicit_reading_gap_needs_none():
    run, candidate = scoped_run()
    eid = candidate["evidence_id"]
    run["evidence"] = {eid: candidate}
    run["completion"] = {"outcome": "PARTIAL", "checks": [{"evidence_ids": [eid]}]}
    binding = JsdBindings()
    assert not binding.validate_output(run, "A finding from the selected source.")[1]
    assert binding.validate_output(run, f"A finding with a limited conclusion [E:{eid}]")[1]
    run["evidence"] = {}
    assert binding.validate_output(run, "The original text could not be read.")[1]


async def test_selected_source_is_prepared_via_normal_tool_and_recovery_does_not_repeat_it():
    from types import SimpleNamespace

    run, candidate = scoped_run()
    binding = JsdBindings()
    binding.store = SimpleNamespace(get=AsyncMock(return_value=run))
    binding.tool = AsyncMock()
    await binding.prepare_run(None, run)
    assert binding.tool.await_args.args[4] == "document_read"
    assert binding.tool.await_args.args[5]["publication_id"] == candidate["source"]["publication_id"]
    run["evidence"] = {candidate["evidence_id"]: candidate}
    await binding.prepare_run(None, run)
    assert binding.tool.await_count == 1
    run["evidence"] = {}
    await binding.prepare_run(None, run)
    assert binding.tool.await_count == 2


async def test_opening_context_keeps_the_result_at_the_end_of_a_source_node():
    import hashlib
    from types import SimpleNamespace

    run, candidate = scoped_run()
    candidate["text"] = "Background and preparation. " * 30 + "Sample B: measured yield 62% after 4 h."
    candidate["source"]["content_sha256"] = hashlib.sha256(candidate["text"].encode()).hexdigest()
    run["evidence"] = {candidate["evidence_id"]: candidate}
    binding = JsdBindings()
    binding.settings = SimpleNamespace(sandbox_enabled=True)
    result = await binding.runtime_topic(None, run, {})
    assert result["selected_source_reading"]["candidates"][0]["text"] == candidate["text"]
    assert "62% after 4 h" in str(result)


async def test_inherited_evidence_does_not_force_a_draft_receipt_but_a_reread_requires_citations(store, principal, settings):
    from aird_agent.evidence.registry import canonical_candidate

    run, candidate = scoped_run()
    candidate = canonical_candidate({key: value for key, value in candidate.items() if key != "evidence_id"})
    run.update(context_refs=[], turn_evidence_ids=[], epoch=1, status="RUNNING",
               evidence={candidate["evidence_id"]: candidate})
    async with store.transaction(principal, "receipt-sources") as conn:
        run = await store.create(principal, "run", run, conn)
    binding = JsdBindings()
    binding.store, binding.settings = store, settings
    binding.active = AsyncMock(return_value=run)
    receipt = "草稿已创建，可在实验页面继续编辑。"
    assert binding.validate_output(run, receipt)[1]
    # Selecting a source explicitly still requires a source citation even when
    # that source was already available from a previous turn.
    selected, _ = scoped_run()
    assert not binding.validate_output({**run, "context_refs": selected["context_refs"]}, receipt)[1]
    await binding.remember_sources(principal, run, {"candidates": [dict(candidate)]})
    current = await store.get(principal, "run", run["id"])
    assert current["turn_evidence_ids"] == [candidate["evidence_id"]]
    assert binding.validate_output(current, "一般性原理说明。")[1]
    current["business_records_read"] = True  # an explicit detail read, rather than search
    assert not binding.validate_output(current, "本轮重新读取的研究结论。")[1]
    assert binding.validate_output(current, f"本轮结论 [E:{candidate['evidence_id']}]")[1]
    assert not binding.validate_output({**run, "turn_evidence_ids": []}, "伪造 [E:e" + "0" * 24 + "]")[1]
