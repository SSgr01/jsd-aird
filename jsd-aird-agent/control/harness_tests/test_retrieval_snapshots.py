import copy
import hashlib
from contextlib import asynccontextmanager
from types import SimpleNamespace
from unittest.mock import AsyncMock
from uuid import uuid4

import pytest
from fastapi import HTTPException

from aird_agent.evidence.navigation import cached_host, context_for
from aird_agent.evidence.registry import register_candidates


def candidate(text, role="MATCH"):
    return {
        "text": text,
        "source": {
            "kind": "KB_PUBLICATION",
            "document_id": str(uuid4()),
            "version_id": str(uuid4()),
            "publication_id": str(uuid4()),
            "source_node_key": str(uuid4()),
            "anchor": {},
            "content_sha256": hashlib.sha256(text.encode()).hexdigest(),
            "title": "test.pdf",
        },
        "role": role,
    }


def runtime_for(run):
    @asynccontextmanager
    async def transaction(*args):
        yield None

    async def remember(principal, state, result):
        run["evidence"] = register_candidates(
            run["evidence"], result.get("candidates", []) + result.get("context_candidates", [])
        )

    return SimpleNamespace(
        store=SimpleNamespace(get=AsyncMock(return_value=run), update=AsyncMock(), transaction=transaction),
        active=AsyncMock(return_value=run),
        remember_sources=remember,
        callback=AsyncMock(),
    )


async def test_snapshot_is_controlled_and_reuse_reauthorizes_parent_and_child(principal):
    run = {
        "id": str(uuid4()),
        "epoch": 1,
        "scope_hash": "scope",
        "evidence": {},
        "retrieval_policy": {"fingerprint": "one", "research": {"rewrite": True}},
    }
    runtime = runtime_for(run)
    child, parent = candidate("result 12 kPa"), candidate("Conditions 25 °C, 24 h", "PARENT_CONTEXT")
    runtime.host_call = AsyncMock(
        return_value={
            "candidates": [child],
            "context_candidates": [parent],
            "trace": {"profile": "research"},
            "ranking_snapshot": {"handles": [{"source": child["source"]}], "policy_hash": "one"},
        }
    )
    first = await cached_host(runtime, principal, run, "knowledge_search", {"query": "result"})
    assert "ranking_snapshot" not in first
    assert len(run["evidence"]) == 2 and "read_coverage" not in run
    assert first["trace"]["new_evidence_count"] == 2
    assert first["trace"]["new_source_versions"] == 2
    assert runtime.callback.await_count == 1
    again = await cached_host(runtime, principal, run, "knowledge_search", {"query": "result"})
    assert again["trace"]["new_evidence_count"] == 0
    assert again["trace"]["new_source_versions"] == 0
    assert again["context_candidates"][0]["text"] == parent["text"]
    assert runtime.host_call.call_args.args[1] == "validate_cached_evidence"
    assert len(runtime.host_call.call_args.args[2]["candidates"]) == 2
    await cached_host(runtime, principal, run, "knowledge_search", {"query": "result", "offset": 12})
    assert runtime.host_call.call_args.args[2]["ranking_snapshot"]["policy_hash"] == "one"
    assert runtime.callback.await_count == 1
    # Snapshot metadata must not contain another body store.
    assert all(
        "text" not in handle
        for snapshot in run["ranking_snapshots"].values()
        for handle in snapshot["handles"]
    )
    run["retrieval_policy"]["fingerprint"] = "two"
    await cached_host(runtime, principal, run, "knowledge_search", {"query": "result", "offset": 24})
    assert "ranking_snapshot" not in runtime.host_call.call_args.args[2]
    assert runtime.callback.await_count == 2
    runtime.host_call.side_effect = HTTPException(403, "revoked")
    with pytest.raises(HTTPException, match="revoked"):
        await cached_host(runtime, principal, run, "knowledge_search", {"query": "result", "offset": 24})


async def test_parent_cursor_and_neighbor_calls_have_independent_progress(principal):
    run = {"id": str(uuid4()), "epoch": 1, "scope_hash": "scope", "evidence": {}}
    runtime = runtime_for(run)
    value = candidate("parent span", "PARENT_CONTEXT")
    runtime.host_call = AsyncMock(
        side_effect=lambda *args: {
            "candidates": [copy.deepcopy(value)],
            "trace": {"fulltext_coverage": False},
        }
    )
    for cursor in (0, 2000, 4000, 6000):
        result = await context_for(runtime, principal, run, value, cursor=cursor, relation="parent")
        assert not result["trace"]["fulltext_coverage"]
    assert runtime.host_call.await_count == 4
    assert runtime.callback.await_count == 0


async def test_stalled_search_allows_new_evidence_but_rejects_fruitless_replays(principal):
    run = {"id": str(uuid4()), "epoch": 1, "scope_hash": "scope", "evidence": {}}
    runtime = runtime_for(run)
    old, new = candidate("known mechanical result"), candidate("new healing time and conditions")
    runtime.host_call = AsyncMock(return_value={"candidates": [old], "trace": {}})
    await cached_host(runtime, principal, run, "knowledge_search", {"query": "mechanical result"})
    for _ in range(3):
        await cached_host(runtime, principal, run, "knowledge_search", {"query": "mechanical result"})
    count = runtime.host_call.await_count
    with pytest.raises(HTTPException, match="READ_NO_PROGRESS"):
        await cached_host(runtime, principal, run, "knowledge_search", {"query": "mechanical result"})
    assert runtime.host_call.await_count == count
    # The full body has already been returned; renaming the query adds no text.
    run["evidence_delivery"] = {eid: [[0, len(item["text"])]] for eid, item in run["evidence"].items()}
    with pytest.raises(HTTPException, match="READ_NO_PROGRESS"):
        await cached_host(runtime, principal, run, "knowledge_search", {"query": "same result renamed"})
    runtime.host_call.return_value = {"candidates": [new], "trace": {}}
    result = await cached_host(runtime, principal, run, "knowledge_search", {"query": "healing time"})
    assert result["candidates"][0]["text"] == new["text"]
    assert len(run["evidence"]) == 2
    # The new search resets the shared no-progress count.
    await cached_host(runtime, principal, run, "knowledge_search", {"query": "healing time"})


async def test_stale_replay_clears_only_navigation_indexes_and_can_restart(principal):
    run = {"id": str(uuid4()), "epoch": 1, "scope_hash": "scope", "evidence": {}}
    runtime = runtime_for(run)
    value = candidate("preserved source")
    runtime.host_call = AsyncMock(
        return_value={"candidates": [value], "ranking_snapshot": {"handles": []}, "trace": {}}
    )
    await cached_host(runtime, principal, run, "knowledge_search", {"query": "frequency"})
    runtime.host_call.side_effect = HTTPException(409, "source changed")
    with pytest.raises(HTTPException, match="SNAPSHOT_CHANGED"):
        await cached_host(runtime, principal, run, "knowledge_search", {"query": "frequency"})
    assert run["evidence"] and not run.get("ranking_snapshots") and not run.get("navigation_cache")
    runtime.host_call.side_effect = None
    runtime.host_call.return_value = {"candidates": [value], "trace": {}}
    await cached_host(runtime, principal, run, "knowledge_search", {"query": "frequency"})
    assert "ranking_snapshot" not in runtime.host_call.call_args.args[2]



async def test_host_forwards_complete_titles_without_a_term_limit(settings, principal):
    import httpx

    from aird_agent.hosts.jsd.host import JsdHostGateway

    query = "Optimization of UV-Curable Polyurethane Acrylate Coatings with Hexagonal Boron Nitride hBN"

    def respond(request):
        import json

        assert json.loads(request.content)["arguments"]["query"] == query
        return httpx.Response(200, json={"candidates": []})

    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        host = JsdHostGateway(settings, client)
        assert await host.call(principal, "knowledge_search", {"query": query}) == {"candidates": []}
