import copy
import json

from test_retrieval_snapshots import candidate

from aird_agent.evidence.delivery import delivery_result
from aird_agent.evidence.navigation import search_preview
from aird_agent.evidence.presentation import model_result
from aird_agent.evidence.quotes import quotes, resolve_quote
from aird_agent.evidence.registry import register_candidates
from aird_agent.hosts.jsd.task_state import bound_document_page


def state(*items):
    evidence = register_candidates({}, list(items))
    return {"evidence": evidence}, list(evidence.values())


def search(run, items, query, budget=9000):
    page = search_preview({"query": query, "candidates": items}, budget, delivered=run)
    return delivery_result(run, "knowledge_search", {"query": query}, page)[0]


def test_different_queries_reuse_the_same_returned_window_but_keep_unseen_spans():
    text = "Initial transmittance was 95%.\n" + "Background preparation.\n" * 7
    text += "Sample B after 2000 abrasion cycles.\nHaze was 12.38 HZ."
    run, items = state(candidate(text))
    first = search(run, items, "transmittance")
    assert "12.38" not in json.dumps(first)
    second = search(run, items, "haze")
    assert "12.38" in second["hits"][0]["quote"]["text"]
    assert "2000 abrasion cycles" in json.dumps(second)
    third = search(run, items, "optical haze")
    assert third["hits"][0]["already_retrieved"]
    assert "12.38" not in json.dumps(third)
    assert third["trace"]["reused_evidence_count"] == 1
    assert model_result(third)["hits"][0]["quote_id"] == second["hits"][0]["quote"]["quote_id"]
    assert run["evidence"][items[0]["evidence_id"]]["text"] == text
    assert "read_coverage" not in run


def test_preview_budget_does_not_record_omitted_quotes_as_delivered():
    text = "Sample A. " + "Detailed preparation " * 35 + ".\n"
    text += "Sample B haze 12.38 HZ.\n" + "Detailed abrasion conditions " * 35 + "."
    run, items = state(candidate(text))
    small = search(run, items, "haze", budget=1800)
    assert small["hits"][0]["requires_read"]
    assert "quote" not in small["hits"][0]
    assert not run.get("evidence_delivery")
    large = search(run, items, "haze", budget=9000)
    assert large["hits"][0]["quote"]["text"] == "Sample B haze 12.38 HZ."
    assert not large["hits"][0].get("already_retrieved")


def test_registered_sources_and_other_runs_or_versions_are_not_delivery_receipts():
    run, items = state(candidate("Sample A haze was 12.38 HZ."))
    first = search(run, items, "haze")
    assert "quote" in first["hits"][0]
    other_run = {"evidence": copy.deepcopy(run["evidence"])}
    assert "quote" in search(other_run, items, "haze")["hits"][0]
    revised = copy.deepcopy(items[0])
    revised.pop("evidence_id")
    revised["source"]["version_id"] += "-revised"
    run["evidence"] = register_candidates(run["evidence"], [revised])
    revised = list(run["evidence"].values())[-1]
    assert "quote" in search(run, [revised], "haze")["hits"][0]


def test_overlapping_document_pages_keep_new_nodes_and_reuse_only_delivered_bodies():
    run, items = state(*(candidate(f"Node {i}: sample and test conditions.") for i in range(3)))
    original = copy.deepcopy(run["evidence"])
    first = {"candidates": items[:2], "trace": {"total_nodes": 3}, "next_offset": 2}
    delivery_result(run, "document_read", {}, first)
    page = {"candidates": items[1:], "trace": {"total_nodes": 3}, "next_offset": None}
    result, _ = delivery_result(run, "document_read", {"offset": 1}, page)
    assert result["candidates"][0]["already_retrieved"]
    assert "text" not in result["candidates"][0]
    assert result["candidates"][1]["text"] == items[2]["text"]
    assert result["next_offset"] is None
    assert model_result(result)["candidates"][0]["already_retrieved"]
    assert run["evidence"] == original
    restored, _ = delivery_result(run, "document_read", {"refresh": True}, page)
    assert restored["candidates"] == items[1:]


def test_document_delivery_reuses_quote_text_without_losing_citation_handles():
    run, (item,) = state(candidate("Sample A.\nAfter abrasion, haze was 12.38 HZ."))
    delivery_result(run, "document_read", {}, {"candidates": [item]})
    original = {"evidence_id": item["evidence_id"], "source": item["source"], "quotes": quotes(item),
                "next_quote_offset": None}
    result, _ = delivery_result(run, "evidence_read", {}, original)
    assert result["new_quote_count"] == 0
    assert all("text" not in span and span["already_retrieved"] for span in result["quotes"])
    for span in result["quotes"]:
        assert resolve_quote(run["evidence"], span)["text"] in item["text"]
    restored, _ = delivery_result(run, "evidence_read", {"refresh": True}, original)
    assert restored["quotes"] == original["quotes"] and restored["new_quote_count"] == 2


def test_new_table_rows_keep_their_column_headers_even_after_an_earlier_row():
    run, (header, row1, row2) = state(candidate("Sample | Haze after 2000 cycles"),
                                    candidate("A | 12.38 HZ"), candidate("B | 8.20 HZ"))
    for row in (row1, row2):
        result = {"evidence_id": row["evidence_id"], "quotes": quotes(row),
                  "table_context": {"headers": [{"evidence_id": header["evidence_id"], "quotes": quotes(header)}]}}
        projected, _ = delivery_result(run, "evidence_read", {}, result)
        assert projected["table_context"]["headers"][0]["quotes"][0]["text"] == header["text"]
        assert projected["quotes"][0]["text"] == row["text"]
    repeated, _ = delivery_result(run, "evidence_read", {}, result)
    assert repeated["quotes"][0]["already_retrieved"]
    assert repeated["table_context"]["headers"][0]["quotes"][0]["already_retrieved"]


def test_new_headers_repeat_their_previous_row_to_keep_column_meaning_together():
    run, (header, row) = state(candidate("Sample | Haze after 2000 cycles"), candidate("A | 12.38 HZ"))
    delivery_result(run, "document_read", {}, {"candidates": [row]})
    result = {"evidence_id": row["evidence_id"], "quotes": quotes(row),
              "table_context": {"headers": [{"evidence_id": header["evidence_id"], "quotes": quotes(header)}]}}
    projected, _ = delivery_result(run, "evidence_read", {}, result)
    assert projected["quotes"] == quotes(row)
    assert projected["table_context"]["headers"][0]["quotes"] == quotes(header)


def test_overlapping_nodes_do_not_consume_the_budget_for_new_nodes():
    run, items = state(*(candidate(f"Node {i}: " + "Test conditions. " * 30) for i in range(3)))
    delivery_result(run, "document_read", {}, {"candidates": items[:2]})
    page = {"candidates": items[1:], "trace": {"total_nodes": 3}}
    bounded = bound_document_page(page, {"offset": 1}, 1600, delivered=run)
    assert len(bounded["candidates"]) == 2 and bounded["next_offset"] is None
    projected, _ = delivery_result(run, "document_read", {}, bounded)
    assert len(json.dumps(model_result(projected), ensure_ascii=False)) < 1600


def test_search_can_fit_a_new_hit_after_compacting_a_previously_returned_hit():
    run, items = state(*(candidate(f"Sample {i}.\nHaze was 12.38 HZ.\n" + "Abrasion conditions. " * 10)
                         for i in range(2)))
    search(run, items[:1], "haze")
    result = search(run, items, "abrasion haze", budget=2600)
    assert len(result["hits"]) == 2
    assert result["hits"][0]["already_retrieved"]
    assert "quote" in result["hits"][1]
    assert len(json.dumps(result, ensure_ascii=False)) < 2600


def test_joint_retrieval_does_not_deliver_candidate_bodies_twice_or_drop_lookup_checks():
    run, (item,) = state(candidate("Sample B.\nHaze after 2000 cycles was 12.38 HZ."))
    item = {**item, "label": "K1"}
    context = "[source=knowledge,page=7] [evidenceRef=K1] " + item["text"]
    context += "\n\nStructured lookup: PARTIAL.\nFormula check: 75 + 10 + 10 + 5 = 100."
    raw = {"context": context, "candidates": [item], "needs_input": True}
    first, _ = delivery_result(run, "retrieve_context", {}, raw)
    assert first["candidates"][0]["text"] == item["text"]
    assert first["context"].count("12.38") == 0
    assert "[E:" + item["evidence_id"] + "]" in first["context"]
    assert first["context"].endswith("Structured lookup: PARTIAL.\nFormula check: 75 + 10 + 10 + 5 = 100.")
    assert first["needs_input"] is True
    assert raw["context"] == context and raw["candidates"][0]["text"] == item["text"]
    second, _ = delivery_result(run, "retrieve_context", {}, raw)
    assert second["candidates"][0]["already_retrieved"]
    assert "12.38" not in json.dumps(second)


def test_joint_retrieval_keeps_partial_or_unmatched_context_and_same_value_in_other_checks():
    run, (item,) = state(candidate("12.38 HZ"))
    item = {**item, "label": "K1"}
    context = "[evidenceRef=K1] 12.38 HZ\n[evidenceRef=K2] Other source: 12.38 HZ."
    result, _ = delivery_result(run, "retrieve_context", {}, {"context": context, "candidates": [item]})
    assert result["context"].endswith("[evidenceRef=K2] Other source: 12.38 HZ.")
    partial = "[evidenceRef=K1] 12.38…\nField ambiguity: choose a measurement."
    result, _ = delivery_result(run, "retrieve_context", {}, {"context": partial, "candidates": [item]})
    assert result["context"] == partial
