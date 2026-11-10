import copy
import hashlib
import json
import time
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from fastapi import HTTPException

from aird_agent.core.business_service import BusinessService
from aird_agent.evidence.citation_repair import authorize_read
from aird_agent.evidence.citations import normalize_citations, validate_report
from aird_agent.evidence.presentation import model_result
from aird_agent.evidence.quotes import quotes
from aird_agent.hosts.jsd.bindings import JsdBindings


def candidate(eid, text="Sample A: 12 mg at 25 °C.", **source):
    return {"evidence_id": eid, "text": text, "source": {
        "kind": "KB_PUBLICATION", "document_id": "doc", "publication_id": "pub", "version_id": "v1",
        "title": "Measured coating properties", "anchor": {"page": 3},
        "content_sha256": hashlib.sha256(text.encode()).hexdigest(), **source,
    }}


def bindings(settings=None):
    value = JsdBindings()
    value.settings = settings or SimpleNamespace(public_url="https://original.test",
        citation_repair_model_calls=2, citation_repair_tool_calls=2)
    return value


def test_compact_document_declarations_keep_body_version_conditions_and_ledger_intact():
    raw = {"candidates": [candidate("e1"), candidate("e2", "Sample B: 0 mg.")],
           "citation_links": {"e1": {"url": "https://original.test/very-long-original#page=3"}}}
    raw["candidates"][0]["table_context"] = {"headers": [{"text": "Mass (mg) at 25 °C"}]}
    raw["document_metadata"] = [{"document_id": "doc", "publication_id": "pub", "version_id": "v1",
                                "title": "Measured coating properties", "doi": "10.123/example"}]
    before = copy.deepcopy(raw)
    projected = model_result(raw)
    assert projected["candidates"][0]["document_ref"] == projected["candidates"][1]["document_ref"]
    assert len(projected["document_sources"]) == 1
    assert projected["document_sources"][0]["doi"] == "10.123/example"
    assert projected["candidates"][0]["table_context"] == raw["candidates"][0]["table_context"]
    assert [c["text"] for c in projected["candidates"]] == [c["text"] for c in raw["candidates"]]
    assert "url" not in projected["candidates"][0]
    assert raw == before
    assert model_result(raw) == projected


def test_private_versions_are_separate_declarations_without_a_publication_id():
    raw = {"candidates": [candidate("a", publication_id=None, kind="RUN_UPLOAD"),
                          candidate("b", publication_id=None, kind="RUN_UPLOAD", version_id="v2")]}
    result = model_result(raw)
    assert len(result["document_sources"]) == 2
    assert result["candidates"][0]["document_ref"] != result["candidates"][1]["document_ref"]


def test_null_model_text_keeps_the_original_body_and_summary_source_is_self_contained():
    item = {**candidate("e1"), "model_text": None}
    assert model_result({"candidates": [item]})["candidates"][0]["text"] == item["text"]
    run = {"evidence": {"e1": item}, "evidence_contexts": [{
        "evidence_id": "e1", "question": "Mass?", "summary": "12 mg", "score": 1}]}
    summary = bindings().evidence_summaries(run)[0]
    assert summary["source"]["document_id"] == "doc"
    assert summary["source"]["publication_id"] == "pub"
    assert "document_ref" not in summary["source"]
    assert "12 mg" in bindings().output_diagnostics(run, "Value [E:missing]")["available_evidence"][0]["excerpt"]


def test_known_grouped_and_annotated_handles_are_repaired_without_a_model_or_source_guess():
    eid = "e" + "a" * 24
    evidence = {eid: candidate(eid)}
    qid = quotes(evidence[eid])[0]["quote_id"]
    for marker in (f"[E:{eid}、E:{qid}]", f"〔 E: {qid}，E:{eid}〕"):
        checked = normalize_citations(marker, evidence)
        assert checked == f"[E:{eid}][E:{eid}]"
        assert validate_report(checked, evidence) == [eid]
    assert normalize_citations(f"[E:{qid} 所在段落]", evidence) == f"[E:{eid}] 所在段落"
    invalid = f"[E:{eid}、E:unknown]"
    assert normalize_citations(invalid, evidence) == invalid
    with pytest.raises(HTTPException, match="MALFORMED_CITATION"):
        validate_report(invalid, evidence)


@pytest.mark.parametrize("marker", ["[E:e123...]", "[E:unknown、E:other]", "[E:unknown", "〔E:unknown", "[E:e" + "a" * 24])
def test_malformed_handles_cannot_bypass_validation_and_diagnostics_locate_them(marker):
    eid = "e" + "a" * 24
    run = {"evidence": {eid: candidate(eid)}}
    text = f"Known [E:{eid}].\nWrong {marker}"
    normalized, valid = bindings().validate_output(run, text)
    assert not valid
    issue = bindings().output_diagnostics(run, text)["issues"][0]
    assert issue["line"] == 2 and normalized[issue["start"]:issue["end"]] == marker


def test_diagnostics_locate_unknown_handles_and_known_formatting_needs_no_model_repair():
    eid = "e" + "a" * 24
    run = {"evidence": {eid: candidate(eid)}, "context_refs": [{"kind": "KB_PUBLICATION", "resource_id": "doc"}],
           "turn_evidence_ids": [eid]}
    binding = bindings()
    text = "Claim.\nMeasured 12 mg [E:unknown]."
    result = binding.output_diagnostics(run, text)
    issue = result["issues"][0]
    assert issue["line"] == 2 and text[issue["start"]:issue["end"]] == "[E:unknown]"
    assert issue["code"] == "UNKNOWN_EVIDENCE_ID"
    assert result["available_evidence"][0]["evidence_id"] == eid
    assert "12 mg" in result["available_evidence"][0]["excerpt"]
    normalized, valid = binding.validate_output(run, f"Measured 12 mg ( E: {eid} ).")
    assert valid and normalized == f"Measured 12 mg [E:{eid}]."
    assert binding.output_diagnostics(run, normalized) == {}


def test_wrong_quote_pair_is_reported_without_guessing_another_source():
    eid = "e" + "a" * 24
    run = {"evidence": {eid: candidate(eid)}}
    result = bindings().output_diagnostics(run, f"Claim [E:{eid}, q{'0' * 24}]")
    assert result["issues"][0]["code"] == "QUOTE_HANDLE_INVALID_OR_WRONG_EVIDENCE"
    assert result["issues"][0]["citation"].endswith("q" + "0" * 24 + "]")


def test_repair_rejects_discovery_new_versions_and_reads_after_the_final_model_call():
    run = {"evidence": {"e1": candidate("e1")}, "output_repair": {
        "allowed_evidence_ids": ["e1"], "model_calls": 1, "max_model_calls": 2}}
    authorize_read(run, "evidence_read", {"evidence_id": "e1"})
    authorize_read(run, "evidence_image_read", {"evidence_id": "e1"})
    authorize_read(run, "document_read", {"document_id": "doc", "publication_id": "pub"})
    for name, args, error in [("knowledge_search", {"query": "new"}, "TARGETED_READ_ONLY"),
                              ("evidence_read", {"evidence_id": "unknown"}, "SOURCE_NOT_READ"),
                              ("document_read", {"document_id": "doc", "publication_id": "new"}, "SOURCE_NOT_READ")]:
        with pytest.raises(HTTPException, match=error):
            authorize_read(run, name, args)
    run["output_repair"]["model_calls"] = 2
    with pytest.raises(HTTPException, match="FINAL_RESPONSE_REQUIRED"):
        authorize_read(run, "evidence_read", {"evidence_id": "e1"})


async def test_repair_model_admission_is_durable_and_repeated_start_does_not_reset_budget(store, principal, settings):
    eid = "e" + "a" * 24
    async with store.transaction(principal, "bounded-repair") as conn:
        run = await store.create(principal, "run", {"status": "RUNNING", "epoch": 1, "dsh_turn": 1,
            "last_text": "Measured 12 mg [E:unknown].", "evidence": {eid: candidate(eid)},
            "steps": 1, "model_calls": 1, "tool_calls": 1, "active_seconds": 0,
            "started_at": time.time(), "dsh_usage": [], "turn_evidence_ids": [eid]}, conn)

    async def current(user, body):
        return await store.get(user, "run", run["id"])

    async def active(user, rid, epoch, conn):
        return await store.get(user, "run", rid, conn)

    service = SimpleNamespace(store=store, settings=settings, bindings=bindings(settings),
        authorized_topic=AsyncMock(), session_run=current, active=active)
    body = {"run_id": run["id"], "session_id": "native", "turn": 1}
    first = await BusinessService.begin_output_repair(service, principal, body)
    assert first["repair"]["diagnostics"]["issues"][0]["line"] == 1
    for i in range(2):
        await BusinessService.gate_dsh(service, principal, {**body, "kind": "model", "call_id": f"repair-{i}"})
    repeated = await BusinessService.begin_output_repair(service, principal, body)
    assert repeated["repair"]["model_calls"] == 2
    with pytest.raises(HTTPException, match="CITATION_REPAIR_BUDGET_EXCEEDED"):
        await BusinessService.gate_dsh(service, principal, {**body, "kind": "model", "call_id": "third"})
    with pytest.raises(HTTPException, match="CITATION_REPAIR_TARGETED_READ_ONLY"):
        await BusinessService.gate_dsh(service, principal, {**body, "kind": "native-tool", "native_name": "bash"})
    current_run = await store.get(principal, "run", run["id"])
    assert current_run["output_repair"]["model_calls"] == 2
    assert len(current_run["dsh_model_attempts"]) == 2
    assert current_run["status"] == "RUNNING"
    assert len(json.dumps(current_run["output_repair"]["diagnostics"], ensure_ascii=False)) < 4000
