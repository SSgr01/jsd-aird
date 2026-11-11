import copy
import hashlib
from unittest.mock import AsyncMock

import pytest
from fastapi import HTTPException

from aird_agent.evidence.registry import canonical_candidate
from aird_agent.hosts.jsd.business_context import authorize_history_snapshot
from aird_agent.hosts.jsd.host import HostToolError
from aird_agent.hosts.jsd.bindings import JsdBindings


@pytest.mark.asyncio
async def test_new_turn_refreshes_changed_experiment_and_preserves_original_ledger():
    source = {"kind": "BUSINESS_RECORD", "resource_type": "EXPERIMENT", "resource_id": "experiment",
              "document_id": "experiment", "version_id": "version", "source_node_key": "summary",
              "content_sha256": hashlib.sha256(b"earlier draft").hexdigest(), "title": "实验", "anchor": {"page": {"section": "summary", "offset": 0, "limit": 8}}}
    original = canonical_candidate({"source": source, "text": "earlier draft"})
    current = canonical_candidate({"source": {**source, "content_sha256": hashlib.sha256(b"formal readings").hexdigest()}, "text": "formal readings"})
    run = {"id": "run", "source_refs": [source], "evidence": {original["evidence_id"]: original}}
    saved = copy.deepcopy(run)

    async def java(principal, action, args, rid):
        if action == "authorize_sources":
            if args["sources"][0]["content_sha256"] == source["content_sha256"]:
                raise HostToolError(409, "VERSION_CONFLICT", "changed")
            return {"allowed": True}
        assert action == "business_read"
        assert args == {"ref": {"type": "EXPERIMENT", "id": "experiment", "version_id": "version"},
                        "section": "summary", "offset": 0, "limit": 8}
        return {"candidates": [current]}

    class Bindings:
        source_authorization = AsyncMock(return_value={"sources": [source]})
    bindings = Bindings()
    bindings.java = java
    inherited = await authorize_history_snapshot(bindings, None, run)
    assert run == saved
    assert set(inherited["evidence"]) == {current["evidence_id"]}
    assert original["evidence_id"] in inherited["superseded_evidence"]
    assert inherited["source_refs"] == [current["source"]]


@pytest.mark.asyncio
@pytest.mark.parametrize("status,code", [(403, "DATA_SCOPE_DENIED"), (404, "NOT_FOUND"), (409, "POLICY_VERSION_CONFLICT")])
async def test_history_refresh_never_suppresses_access_denials(status, code):
    class Bindings:
        source_authorization = AsyncMock(return_value={"sources": []})
        java = AsyncMock(side_effect=HostToolError(status, code, "denied"))
    with pytest.raises(HTTPException) as error:
        await authorize_history_snapshot(Bindings(), None, {"id": "run"})
    assert error.value.status_code == status
    assert Bindings.java.call_count == 1


@pytest.mark.asyncio
async def test_material_version_conflict_requires_explicit_source_reselection():
    source = {"kind": "BUSINESS_RECORD", "resource_type": "RAW_MATERIAL", "resource_id": "material"}
    class Bindings:
        source_authorization = AsyncMock(return_value={"sources": [source]})
        java = AsyncMock(side_effect=HostToolError(409, "VERSION_CONFLICT", "changed"))
    with pytest.raises(HTTPException, match="SOURCE_REFRESH_REQUIRED"):
        await authorize_history_snapshot(Bindings(), None, {"id": "run", "source_refs": [source]})
    assert all(call.args[1] == "authorize_sources" for call in Bindings.java.call_args_list)


@pytest.mark.asyncio
async def test_discovery_prioritizes_the_explicit_tool_name_over_description_words():
    class Catalog:
        def tools_for(self, run):
            return [{"name": "run_experiment_optimization", "description": "发起推荐"}] + [
                {"name": "reader_" + str(index), "description": "参数 说明 候选 数量 提交 实验 优化"}
                for index in range(10)]
    result = await JsdBindings.dispatch(Catalog(), None, {}, {}, "tool_search",
        {"query": "run_experiment_optimization 参数说明 候选数量 提交实验优化"}, "call")
    assert result["activate_tools"][0] == "run_experiment_optimization"
