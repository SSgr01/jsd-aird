from types import SimpleNamespace
from contextlib import asynccontextmanager
from unittest.mock import AsyncMock
from uuid import uuid4

import pytest
from fastapi import HTTPException

from aird_agent.capabilities.business_data import business_pack
from aird_agent.core.capabilities import ExecutionContext
from aird_agent.core.contracts import digest
from aird_agent.evidence.links import citation_links
from aird_agent.evidence.presentation import model_result
from aird_agent.evidence.registry import canonical_candidate
from aird_agent.capabilities.literature.approval import native_literature_approval


async def test_compute_identity_is_server_owned_and_missing_identity_does_not_submit():
    calls = []

    async def host(principal, action, arguments, run_id):
        calls.append((principal, action, arguments, run_id))
        return {"id": "isolated-computation", "executionStatus": "QUEUED"}

    pack = business_pack()
    context = ExecutionContext(SimpleNamespace(user_id=uuid4()), {"id": "isolated-run"}, {}, host, call_id="native-tool-call")
    await pack.handlers["run_prediction"](context, {"request": {"inputs": {"UV_ENERGY": 0}}, "operation_key": "model-forgery"})
    assert calls[0][2]["operation_key"] == digest(["isolated-run", "native-tool-call"])
    assert calls[0][2]["request"]["inputs"]["UV_ENERGY"] == 0
    context = ExecutionContext(context.principal, context.run, {}, host)
    with pytest.raises(HTTPException, match="BUSINESS_CALL_ID_REQUIRED"):
        await pack.handlers["run_prediction"](context, {"request": {}})
    assert len(calls) == 1


async def test_research_sources_resolve_from_retrieved_handles_and_never_accept_unknown_handles():
    calls = []
    source = {"kind": "KB_PUBLICATION", "document_id": "paper", "version_id": "v2"}

    async def host(principal, action, arguments, run_id):
        calls.append(arguments)
        return {}

    pack = business_pack()
    run = {"id": "research-run", "evidence": {"e-real": {"source": source}}}
    context = ExecutionContext(SimpleNamespace(user_id=uuid4()), run, {}, host, call_id="recommendation")
    args = {"request": {}, "source_refs": [{"evidence_id": "e-real"}],
            "research_basis": [{"source_ref": {"evidence_id": "e-real"}, "conclusion": "conditions matter"}]}
    await pack.handlers["run_experiment_optimization"](context, args)
    assert calls[0]["source_refs"] == [source]
    assert calls[0]["research_basis"][0]["source_ref"] == source
    assert args["research_basis"][0]["source_ref"] == {"evidence_id": "e-real"}
    args["source_refs"] = [{"evidence_id": "e-invented"}]
    with pytest.raises(HTTPException, match="RESEARCH_SOURCE_NOT_READ"):
        await pack.handlers["run_experiment_optimization"](context, args)
    assert len(calls) == 1
    args["source_refs"] = []
    with pytest.raises(HTTPException, match="RESEARCH_BASIS_SOURCE_NOT_LINKED"):
        await pack.handlers["run_experiment_optimization"](context, args)
    assert len(calls) == 1


async def test_native_literature_decision_is_scoped_to_the_exact_call_and_rejects_background_or_unknown_tools():
    run = {"id": "run", "active_tools": ["literature_search"], "approvals": {}}

    @asynccontextmanager
    async def transaction(*args):
        yield object()

    service = SimpleNamespace(session_run=AsyncMock(return_value=run), authorized_topic=AsyncMock(),
        tools_for=lambda _: [{"name": "literature_search", "parameters": {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"], "additionalProperties": False}}],
        bindings=SimpleNamespace(authorize_tool=AsyncMock()), active=AsyncMock(return_value=run),
        store=SimpleNamespace(transaction=transaction, update=AsyncMock(), event=AsyncMock()))
    body = {"run_id": "run", "session_id": "session", "turn": 1, "epoch": 3, "call_id": "call-a", "name": "literature_search",
            "arguments": {"query": "acrylic film gloss"}, "outcome": "allowed-once"}
    await native_literature_approval(service, object(), body)
    key = digest({"tool": body["name"], "arguments": body["arguments"], "call_id": "call-a"})
    assert run["approvals"][key]["call_id"] == "call-a"
    assert digest({"tool": body["name"], "arguments": body["arguments"], "call_id": "call-b"}) not in run["approvals"]
    service.bindings.authorize_tool.assert_awaited_once()
    run["automation"] = {"task": "weekly"}
    with pytest.raises(HTTPException, match="BACKGROUND_READ_ONLY"):
        await native_literature_approval(service, object(), body)
    with pytest.raises(HTTPException, match="INVALID_LITERATURE_APPROVAL"):
        await native_literature_approval(service, object(), {**body, "name": "run_model_training"})
    with pytest.raises(HTTPException, match="INVALID_LITERATURE_APPROVAL"):
        await native_literature_approval(service, object(), {**body, "outcome": "rejected"})


def test_query_proof_stays_in_ledger_and_model_receives_compact_typed_results():
    import hashlib

    text = "隔离库存样本：12.50 kg"
    candidate = {"text": text, "model_text": "业务查询结果见rows", "ref": {"type": "INVENTORY_BALANCE", "id": "sample"},
                 "source": {"kind": "BUSINESS_RECORD", "resource_type": "BUSINESS_QUERY", "resource_id": "query",
                            "document_id": "query", "version_id": "snapshot", "source_node_key": "query:sample",
                            "title": "库存", "content_sha256": hashlib.sha256(text.encode()).hexdigest(),
                            "anchor": {"business_adapter": "v1", "read_path": "/api/v1/agents/business/query?q=sample&snapshot_hash=fixed"}}}
    proof = canonical_candidate(candidate)
    result = model_result({"candidates": [proof], "rows": [{"kg": 12.5, "missing": None}],
                           "dataset": {"content_base64": "private-payload"}})
    assert proof["text"] == text
    assert result["candidates"][0]["text"] == "业务查询结果见rows"
    assert result["candidates"][0]["ref"] == candidate["ref"]
    assert result["rows"][0] == {"kg": 12.5, "missing": None}
    assert "private-payload" not in str(result)
    assert citation_links({proof["evidence_id"]: proof}, "https://example.test")[proof["evidence_id"]]["url"].startswith("https://example.test/api/v1/agents/business/")


def test_mutating_compute_tools_are_discoverable_and_not_initially_activated():
    pack = business_pack()
    assert pack.initial_tools == ("business_search",)
    assert pack.mutations == {"run_prediction", "run_formula_design", "run_experiment_optimization",
                              "run_model_training", "activate_trained_model", "continue_experiment_optimization"}
    for spec in pack.tools:
        assert "operation_key" not in spec["parameters"].get("properties", {})


def test_compute_candidates_keep_inputs_readings_and_material_source_positions():
    candidate = {"candidateId": "recipe-2", "inputs": {"resin": 0, "temperature": 80},
                 "predictedResults": {"value": 84.2}, "actualInputs": {"temperature": 81}}
    dossier = {"facts": {"properties": [{"name": "density", "value": 1.02, "unit": "g/mL",
        "sources": [{"documentId": "document-1", "versionNo": 2, "lineStart": 8, "lineEnd": 8}]}]}}
    result = model_result({"runs": [{"candidates": [candidate], "materialDossiers": [dossier]}]})
    assert result["runs"][0]["candidates"][0] == candidate
    assert result["runs"][0]["materialDossiers"][0] == dossier


def test_outline_model_projection_keeps_reading_ranges_and_omits_geometry():
    page = {"entries": [{"node_offset": 58, "end_offset_exclusive": 63, "kind": "HEADING_CANDIDATE",
                        "title": "3.9. Evaluation of Haze", "source_node_key": "ledger-node",
                        "anchor": {"page": 7, "polygon": [0, 0, 1, 1], "provider": "MINERU"}}],
            "trace": {"total_nodes": 100, "fulltext_coverage": False}, "next_offset": 96}
    result = model_result(page)
    assert result["entries"] == [{"node_offset": 58, "end_offset_exclusive": 63, "kind": "HEADING_CANDIDATE",
                                  "title": "3.9. Evaluation of Haze", "page": 7}]
    assert result["next_offset"] == 96
    assert page["entries"][0]["anchor"]["polygon"]
    assert all(field not in str(result) for field in ("MINERU", "polygon", "source_node_key"))


@pytest.mark.parametrize("status", [401, 403])
async def test_host_permission_refusal_keeps_status_but_never_forwards_internal_fields(settings, principal, status):
    import httpx

    from aird_agent.hosts.jsd.host import JsdHostGateway

    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda request: httpx.Response(
        status, json={"code": "DATA_SCOPE_DENIED", "message": "SHIPMENT internal-org private-field"}
    ))) as client:
        with pytest.raises(HTTPException) as caught:
            await JsdHostGateway(settings, client).call(principal, "business_search", {"type": "SHIPMENT"})
    assert caught.value.status_code == status
    assert ("登录状态已失效" if status == 401 else "无权读取") in caught.value.detail
    assert all(field not in caught.value.detail for field in ("SHIPMENT", "DATA_SCOPE_DENIED", "private-field", "internal-org"))
