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
async def test_history_reuses_source_checks_only_within_the_same_request_and_purpose():
    source = {"kind": "BUSINESS_RECORD", "resource_id": "record", "revision": "revision"}
    class Bindings:
        source_authorization = AsyncMock(return_value={"sources": [source]})
        java = AsyncMock(return_value={"allowed": True})
    bindings, checked = Bindings(), set()
    for rid in ("run-one", "run-two"):
        run = {"id": rid, "source_refs": [source]}
        assert await authorize_history_snapshot(bindings, None, run, authorized=checked) is run
    assert bindings.java.await_count == 1
    bindings.source_authorization.return_value = {"sources": [source], "purpose": "briefing-history"}
    await authorize_history_snapshot(bindings, None, run, authorized=checked)
    assert bindings.java.await_count == 2
    await authorize_history_snapshot(bindings, None, run, authorized=set())
    assert bindings.java.await_count == 3
    bindings.java.side_effect = HostToolError(403, "DATA_SCOPE_DENIED", "denied")
    with pytest.raises(HTTPException):
        await authorize_history_snapshot(bindings, None, run, authorized=set())


@pytest.mark.asyncio
@pytest.mark.parametrize("purpose,checks", [("briefing-history", 1), (None, 2), ("export", 2)])
async def test_history_pages_share_only_the_same_historical_permission_target(purpose, checks):
    source={"kind":"BUSINESS_RECORD", "resource_type":"EXPERIMENT", "resource_id":"record",
            "project_id":"project", "version_id":"fixed", "content_sha256":"page-one",
            "anchor":{"page":{"section":"summary"}}}
    class Bindings:
        source_authorization=AsyncMock()
        java=AsyncMock(return_value={"allowed":True})
    bindings,checked=Bindings(),set()
    for item in (source,{**source,"content_sha256":"page-two","anchor":{"page":{"section":"record"}}}):
        bindings.source_authorization.return_value={"sources":[item],**({"purpose":purpose} if purpose else {})}
        await authorize_history_snapshot(bindings,None,{"id":"run"},authorized=checked)
    assert bindings.java.await_count == checks
    for change in ({"version_id":"other"},{"project_id":"other"}):
        bindings.source_authorization.return_value={"sources":[{**source,**change}],**({"purpose":purpose} if purpose else {})}
        await authorize_history_snapshot(bindings,None,{"id":"run"},authorized=checked)
    assert bindings.java.await_count == checks+2
    bindings.java.side_effect=HostToolError(403,"DATA_SCOPE_DENIED","denied")
    bindings.source_authorization.return_value={"sources":[source],**({"purpose":purpose} if purpose else {})}
    with pytest.raises(HTTPException):
        await authorize_history_snapshot(bindings,None,{"id":"run"},authorized=set())


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
