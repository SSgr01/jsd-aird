import hashlib
import json

from aird_agent.evidence.navigation import search_preview
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
        {"text": text, "source": source, "evidence_id": "e" + str(i) * 24, "node_offset": 31 + i}
        for i in range(8)
    ]
    result = search_preview(
        {
            "query": "frequency sweeps",
            "candidates": candidates,
            "trace": {"offset": 0, "candidate_count": 8},
            "next_offset": None,
        },
        4000,
    )
    assert len(json.dumps(result, ensure_ascii=False)) < 4000
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
    assert hit["source_text"] == text


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
