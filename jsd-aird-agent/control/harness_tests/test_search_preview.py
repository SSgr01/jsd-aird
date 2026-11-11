import hashlib
import json

import pytest
from fastapi import HTTPException

from aird_agent.evidence.delivery import delivery_result
from aird_agent.evidence.navigation import search_preview
from aird_agent.evidence.presentation import model_result
from aird_agent.hosts.jsd.bindings import JsdBindings


def test_ranked_search_returns_bounded_exact_quotes_and_real_continuation():
    text = "Background. " * 500 + "Frequency sweeps from 0.1 to 100 rad/s. " * 200
    source = {
        "kind": "RUN_UPLOAD",
        "document_id": "doc",
        "version_id": "version",
        "publication_id": "version",
        "source_node_key": "node",
        "content_sha256": hashlib.sha256(text.encode()).hexdigest(),
        "anchor": {"page": 3},
        "title": "paper.pdf",
    }
    candidates = [
        {"text": text, "source": source, "evidence_id": f"e{i:024x}", "node_offset": 31 + i}
        for i in range(32)
    ]
    result = search_preview(
        {
            "query": "frequency sweeps",
            "candidates": candidates,
            "trace": {"offset": 0, "candidate_count": 32},
            "next_offset": None,
        },
        4000,
    )
    assert len(json.dumps(model_result(result), ensure_ascii=False)) < 4000
    assert result["hits"] and "frequency" in result["hits"][0]["quote"]["text"].lower()
    assert result["next_offset"] == len(result["hits"])
    for hit in result["hits"]:
        if hit.get("requires_read"):
            assert "quote" not in hit and "context_quotes" not in hit
            continue
        quote = hit["quote"]
        assert text[quote["start"] : quote["end"]] == quote["text"]


def test_complete_source_answer_requires_a_real_citation():
    binding = JsdBindings()
    run = {"research_requirements": {"require_report": True}, "evidence": {"e1": {}}}
    assert binding.validate_output(run, "An uncited result")[1] is False
    assert binding.validate_output(run, "Result [E:e1]")[1] is True
    assert binding.validate_output(run, "Result [E:forged]")[1] is False


def test_search_keeps_sample_and_method_next_to_the_matching_value():
    from test_retrieval_snapshots import candidate

    text = "Sample B, measured after abrasion at 20 °C.\nThe remaining contact angle is 95 degrees.\nThis test used 100 cycles, not the initial measurement."
    item = {**candidate(text), "evidence_id": "e" + "a" * 24}
    hit = search_preview({"query": "remaining contact angle", "candidates": [item]}, 9000)["hits"][0]
    window = [*hit["context_quotes"], hit["quote"]]
    assert any("Sample B" in quote["text"] for quote in window)
    assert any("100 cycles" in quote["text"] for quote in window)
    assert all(text[q["start"]:q["end"]] == q["text"] for q in window)
    assert "source_text" not in hit
    assert text == item["text"]


def test_tight_budget_never_returns_a_value_after_discarding_its_conditions():
    from test_retrieval_snapshots import candidate

    text = "Sample A, initial measurement. " + "A long method description " * 22 + ".\n"
    text += "Sample B after abrasion: contact angle 95 degrees.\n"
    text += "Measured after 100 abrasion cycles. " + "Additional method details " * 22 + "."
    item = {**candidate(text), "evidence_id": "e" + "a" * 24}
    result = search_preview({"query": "contact angle", "candidates": [item]}, 1800)
    assert len(json.dumps(result, ensure_ascii=False)) < 1800
    hit = result["hits"][0]
    assert hit["requires_read"]
    assert "quote" not in hit and "context_quotes" not in hit
    assert hit["evidence_id"] == item["evidence_id"]


def test_search_preserves_average_and_typical_device_as_separate_source_statements():
    from test_retrieval_snapshots import candidate

    text = "Devices were prepared under the stated conditions.\n"
    text += "The average PCE was 17.9% across devices.\nThe distribution is shown in the figure.\n"
    text += "A typical device had a PCE of 18.0%.\nThe stability test used another sample."
    item = {**candidate(text), "evidence_id": "e" + "b" * 24}
    hit = search_preview({"query": "typical device PCE", "candidates": [item]}, 6000)["hits"][0]
    window = "\n".join(q["text"] for q in [hit["quote"], *hit["context_quotes"]])
    assert "average PCE was 17.9%" in window and "typical device had a PCE of 18.0%" in window


async def test_repeated_search_reauthorizes_sources_and_returns_handles_without_repeating_bodies(principal):
    from unittest.mock import AsyncMock

    from test_retrieval_snapshots import candidate, runtime_for

    from aird_agent.evidence.navigation import cached_host

    run = {"id": "run", "epoch": 1, "scope_hash": "scope", "evidence": {}}
    runtime = runtime_for(run)
    text = "Sample A, after 2000 abrasion cycles. Haze was 12.38 HZ."
    parent = "The abrasion test used a load of 1 kg."
    runtime.host_call = AsyncMock(return_value={
        "candidates": [candidate(text)], "context_candidates": [candidate(parent, "PARENT_CONTEXT")],
        "trace": {"profile": "research"},
    })
    first = search_preview(await cached_host(runtime, principal, run, "knowledge_search", {"query": "haze"}), 9000)
    delivery_result(run, "knowledge_search", {}, first)
    second = search_preview(await cached_host(runtime, principal, run, "knowledge_search", {"query": "  HAZE  "}), 9000, delivered=run)
    assert runtime.host_call.call_args.args[1] == "validate_cached_evidence"
    assert second["trace"]["cache_hit"] and second["trace"]["new_evidence_count"] == 0
    assert second["hits"][0]["evidence_id"] == first["hits"][0]["evidence_id"]
    assert second["hits"][0]["already_retrieved"]
    assert second["hits"][0]["quote_count"] > 0
    assert second["parent_evidence_ids"] == [first["parent_context"][0]["evidence_id"]]
    assert second["parent_context"] == []
    assert text not in json.dumps(second) and parent not in json.dumps(second)
    assert len(json.dumps(second)) < len(json.dumps(first))
    runtime.host_call.side_effect = HTTPException(403, "revoked")
    with pytest.raises(HTTPException, match="revoked"):
        await cached_host(runtime, principal, run, "knowledge_search", {"query": "haze"})


async def test_new_query_keeps_unseen_matching_spans_in_a_registered_node(principal):
    import copy
    from unittest.mock import AsyncMock

    from test_retrieval_snapshots import candidate, runtime_for

    from aird_agent.evidence.navigation import cached_host

    run = {"id": "run", "epoch": 1, "scope_hash": "scope", "evidence": {}}
    runtime = runtime_for(run)
    text = "Initial transmittance was 95%.\n" + "Background sample preparation.\n" * 6
    text += "After 2000 abrasion cycles.\nHaze was 12.38 HZ."
    item = candidate(text)

    def respond(principal, action, args, run_id):
        return {"query": args["query"], "candidates": [copy.deepcopy(item)], "trace": {"profile": "research"}}

    runtime.host_call = AsyncMock(side_effect=respond)
    first = search_preview(await cached_host(runtime, principal, run, "knowledge_search", {"query": "transmittance"}), 9000)
    second = search_preview(await cached_host(runtime, principal, run, "knowledge_search", {"query": "haze"}), 9000)
    assert "12.38" not in json.dumps(first)
    assert second["trace"]["new_evidence_count"] == 0
    assert second["hits"][0]["evidence_id"] == first["hits"][0]["evidence_id"]
    assert not second["hits"][0].get("already_retrieved")
    assert "12.38" in second["hits"][0]["quote"]["text"]
    assert "2000 abrasion cycles" in json.dumps(second["hits"][0]["context_quotes"])
