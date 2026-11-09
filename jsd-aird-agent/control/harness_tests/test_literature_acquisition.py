"""Acquired full text changes scope only after host validation and under the active lease."""

import copy
from contextlib import asynccontextmanager
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from fastapi import HTTPException

from aird_agent.capabilities.literature.acquisition import READERS, acquired_source
from aird_agent.capabilities.registry import default_registry
from aird_agent.hosts.jsd.task_state import scope_digest


@pytest.fixture
def acquisition():
    run = {"id": "run", "epoch": 1, "conversation_id": "conversation", "private_source_ids": []}
    current = {**run, "navigation_cache": {"old": True}, "completion": {"outcome": "COMPLETE"}}
    conversation = {"active_run": "run", "private_source_ids": None}
    conn = SimpleNamespace(execute=AsyncMock(return_value=SimpleNamespace(fetchall=AsyncMock(return_value=[]))))

    @asynccontextmanager
    async def transaction(*args):
        yield conn

    scope = {"private_source_ids": ["source"], "private_documents": [{"document_id": "source", "publication_id": "version"}]}
    runtime = SimpleNamespace(
        store=SimpleNamespace(transaction=transaction, get=AsyncMock(return_value=conversation), update=AsyncMock()),
        active=AsyncMock(return_value=current), java=AsyncMock(return_value=scope), context_request=lambda proposed: proposed,
    )
    result = {"id": "source", "conversation_id": "conversation", "work_id": "W123", "version_id": "version",
              "kind": "EXTERNAL_FULLTEXT", "sha256": "a" * 64, "status": "READY"}
    return runtime, run, current, conversation, result, scope, conn


async def test_ready_source_is_authorized_attached_once_and_activates_existing_readers(acquisition):
    runtime, run, current, conversation, result, scope, conn = acquisition
    first = await acquired_source(runtime, "owner", run, result, "W123")
    assert first["activate_tools"] == READERS
    assert first["document_id"] == "source"
    assert current["scope_hash"] == scope_digest(scope)
    assert run["private_source_ids"] == conversation["private_source_ids"] == ["source"]
    assert "navigation_cache" not in current and "completion" not in current
    await acquired_source(runtime, "owner", run, result, "W123")
    runtime.java.assert_awaited_once()
    assert run["private_source_ids"] == ["source"]


@pytest.mark.parametrize("status", ["ACQUIRING", "PARSING", "FAILED", "UNKNOWN"])
async def test_nonready_sources_do_not_expand_scope_or_activate_readers(acquisition, status):
    runtime, run, current, conversation, result, scope, conn = acquisition
    answer = await acquired_source(runtime, "owner", run, {**result, "status": status}, "W123")
    assert "activate_tools" not in answer
    assert run["private_source_ids"] == []
    runtime.java.assert_not_awaited()
    runtime.store.update.assert_not_awaited()
    if status == "UNKNOWN":
        conn.execute.assert_not_awaited()
        assert "不要重复提交" in answer["instruction"]


@pytest.mark.parametrize("change", [{"conversation_id": "other"}, {"work_id": "W456"}, {"kind": "RUN_UPLOAD"}, {"sha256": ""}])
async def test_cross_conversation_or_unverified_results_are_rejected(acquisition, change):
    runtime, run, current, conversation, result, scope, conn = acquisition
    with pytest.raises(HTTPException):
        await acquired_source(runtime, "owner", run, {**result, **change}, "W123")
    runtime.java.assert_not_awaited()
    runtime.store.update.assert_not_awaited()


async def test_host_denial_version_change_and_stopped_run_cannot_attach_cached_text(acquisition):
    runtime, run, current, conversation, result, scope, conn = acquisition
    runtime.java.side_effect = HTTPException(403, "ACCESS_REVOKED")
    with pytest.raises(HTTPException, match="ACCESS_REVOKED"):
        await acquired_source(runtime, "owner", run, result, "W123")
    runtime.java.side_effect = None
    runtime.java.return_value = {**scope, "private_documents": [{"document_id": "source", "publication_id": "other-version"}]}
    with pytest.raises(HTTPException, match="ACQUIRED_SOURCE_VERSION_MISMATCH"):
        await acquired_source(runtime, "owner", run, result, "W123")
    conversation["active_run"] = None
    with pytest.raises(HTTPException, match="LEASE_LOST"):
        await acquired_source(runtime, "owner", run, result, "W123")
    runtime.store.update.assert_not_awaited()


async def test_lost_epoch_prevents_scope_changes(acquisition):
    runtime, run, current, conversation, result, scope, conn = acquisition
    runtime.active.side_effect = HTTPException(409, "RUN_INACTIVE_OR_LEASE_LOST")
    before = copy.deepcopy(run)
    with pytest.raises(HTTPException, match="LEASE_LOST"):
        await acquired_source(runtime, "owner", run, result, "W123")
    assert run == before
    conn.execute.assert_not_awaited()


def test_new_tools_are_discovered_from_literature_and_acquisition_is_a_mutation():
    registry = default_registry()
    names = {item["name"] for item in registry.tools(["core", "literature"])}
    assert {"literature_search", "literature_expand", "literature_acquire", "literature_acquire_status"} <= names
    assert "literature_acquire" in registry.mutations(["literature"])
    assert "literature_acquire" not in registry.initial_tools(["literature"])
    assert not {"literature_search", "literature_acquire"} & {item["name"] for item in registry.tools(["core", "fast-qa"])}
