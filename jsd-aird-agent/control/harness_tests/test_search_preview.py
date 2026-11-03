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
        quote = hit["quote"]
        assert text[quote["start"] : quote["end"]] == quote["text"]


def test_complete_source_answer_requires_a_real_citation():
    binding = JsdBindings()
    run = {"completion": {"outcome": "COMPLETE"}, "evidence": {"e1": {}}}
    assert binding.validate_output(run, "An uncited result")[1] is False
    assert binding.validate_output(run, "Result [E:e1]")[1] is True
    assert binding.validate_output(run, "Result [E:forged]")[1] is False
