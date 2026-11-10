from types import SimpleNamespace
from uuid import uuid4

import pytest
from fastapi import HTTPException

from aird_agent.capabilities.business_data import business_pack
from aird_agent.core.capabilities import ExecutionContext
from aird_agent.core.contracts import digest
from aird_agent.evidence.links import citation_links
from aird_agent.evidence.presentation import model_result
from aird_agent.evidence.registry import canonical_candidate


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
    assert pack.mutations == {"run_prediction", "run_formula_design", "run_experiment_optimization"}
    for spec in pack.tools:
        assert "operation_key" not in spec["parameters"].get("properties", {})


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
