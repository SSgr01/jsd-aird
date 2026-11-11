from aird_agent.evidence.navigation import search_preview
from aird_agent.hosts.jsd.bindings import JsdBindings


def test_reading_does_not_reinject_growing_catalogs_into_every_native_step():
    bindings = JsdBindings()
    run = {"source_documents": [{"document_id": "paper", "publication_id": "fixed"}],
           "evidence": {}, "read_coverage": {"paper:fixed": {"total_nodes": 180, "ranges": [[0, 20]]}}}
    first = bindings.gate_state(run)
    run["read_coverage"]["paper:fixed"]["ranges"] = [[0, 80]]
    assert bindings.gate_state(run) == first == {}
    run["registered_outputs"] = [{"kind": "experiment_draft", "id": "created-once"}]
    assert bindings.gate_state(run) == {"registered_outputs": run["registered_outputs"]}
    run["research_requirements"] = {"fulltext_documents": run["source_documents"]}
    assert bindings.gate_state(run)["read_coverage"]["paper:fixed"]["read_nodes"] == 80


def test_search_preview_preserves_partial_coverage_even_when_no_hits_were_delivered():
    result = search_preview({"candidates": [], "retrieval_status": "PARTIAL",
        "retrieval_notice": "向量检索未完成，覆盖不完整。", "trace": {"candidate_count": 0}}, 5000)
    assert result["hits"] == []
    assert result["retrieval_status"] == "PARTIAL"
    assert result["retrieval_notice"] == "向量检索未完成，覆盖不完整。"
