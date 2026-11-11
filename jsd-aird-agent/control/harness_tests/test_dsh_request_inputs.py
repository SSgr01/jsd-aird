import base64
import hashlib
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock
from uuid import uuid4

import pytest
from fastapi import HTTPException

from aird_agent.capabilities.registry import default_registry
from aird_agent.hosts.jsd.bindings import JsdBindings
from aird_agent.hosts.jsd.request_inputs import native_documents, request_inputs
from aird_agent.hosts.jsd.session_views import SessionViews
from aird_agent.server.contracts import RunRequest


def mention(ref, label="文献"):
    token = base64.urlsafe_b64encode(json.dumps(ref).encode()).decode().rstrip("=")
    return f"[{label}](jsd-resource:{token})"


def publication(**extra):
    return {"kind": "KB_PUBLICATION", "resource_id": str(uuid4()), "publication_id": str(uuid4()), **extra}


def test_reference_and_explicit_request_filter_have_separate_meanings():
    ref = publication()
    normal = request_inputs("解释 " + mention(ref))
    filtered = request_inputs("比较 " + mention({**ref, "only": True}))
    assert normal["context_refs"] == [ref]
    assert normal["retrieval_scope"] == {}
    assert filtered["retrieval_scope"]["knowledge_documents"] == [
        {"document_id": ref["resource_id"], "publication_id": ref["publication_id"]}
    ]
    assert "jsd-resource:" not in normal["request"]
    assert request_inputs("继续讨论") == {"request": "继续讨论", "context_refs": [], "retrieval_scope": {}}


@pytest.mark.parametrize("ref", [
    {"kind": "EXPERIMENT", "resource_id": str(uuid4())},
    {"kind": "EXPERIMENT", "resource_id": str(uuid4()), "version_id": "other-version"},
    publication(actor_id=str(uuid4())),
    publication(only="true"),
    {"kind": "PROJECT", "resource_id": "../other", "version_id": "1"},
    {"kind": "PROJECT", "resource_id": str(uuid4()), "version_id": "1", "only": True},
])
def test_resource_tokens_cannot_carry_identity_or_unpinned_experiment_versions(ref):
    with pytest.raises(HTTPException) as denied:
        request_inputs(mention(ref))
    assert denied.value.status_code == 422


@pytest.mark.asyncio
async def test_next_request_does_not_inherit_conversation_refs_filters_or_attachments(principal):
    binding = JsdBindings()
    binding.store = SimpleNamespace(get=AsyncMock(return_value={
        "context_refs": [publication()], "retrieval_scope": {"knowledge_category_ids": [str(uuid4())]},
        "pack_versions": {"files": "old"}, "private_source_ids": [str(uuid4())],
    }))
    binding.packs = default_registry()
    binding.java = AsyncMock(return_value={"pack_ids": ["core", "retrieval"]})
    payload, _ = await binding.prepare_request(principal, RunRequest(
        request="下一轮问题", profile="agent", conversation_id=uuid4()))
    assert payload["context_refs"] == []
    assert payload["retrieval_scope"]["knowledge_documents"] is None
    assert not payload.get("private_source_ids")
    assert binding.conversation_metadata(payload) == {"profile": "agent", "private_source_ids": []}


@pytest.mark.asyncio
async def test_discovery_and_preview_do_not_create_or_mutate_conversation(principal):
    service = object.__new__(SessionViews)
    service.ensure_conversation = AsyncMock(side_effect=AssertionError("must not create conversation"))
    service.java = AsyncMock(return_value={"choices": []})
    await service.session_view(principal, "context", {"query": "", "session_id": "native"})
    service.java.assert_awaited_once_with(principal, "context_choices", {"query": ""})
    ref = publication()
    service.java.reset_mock()
    await service.session_view(principal, "resource", {"ref": ref})
    service.java.assert_awaited_once_with(principal, "context_resource", {"ref": ref, "offset": 0})
    with pytest.raises(HTTPException) as removed:
        await service.session_view(principal, "attach", {"session_id": "native", "profile": "agent"})
    assert removed.value.status_code == 404


@pytest.mark.asyncio
async def test_native_document_ownership_does_not_activate_past_attachments(principal, store):
    async with store.transaction(principal, "native-attachment-test") as conn:
        conversations = [await store.create(principal, "conversation", {"profile": "agent", "private_source_ids": []}, conn) for _ in range(2)]
    ids = [str(uuid4()), str(uuid4())]
    host = AsyncMock(side_effect=[{"id": sid, "status": "READY"} for sid in ids])
    service = SimpleNamespace(store=store, host_call=host)
    data = b"test document"
    attachment = {"name": "sample.txt", "base64": base64.b64encode(data).decode(), "attachment_id": "sha256:" + hashlib.sha256(data).hexdigest()}
    for conversation, sid in zip(conversations, ids):
        assert await native_documents(service, principal, conversation, [attachment]) == [sid]
    keys = [call.args[2]["key"] for call in host.await_args_list]
    assert keys[0] != keys[1]  # identical bytes in another session cannot alias ownership
    current = await store.get(principal, "conversation", conversations[0]["id"])
    assert current["private_source_ids"] == [ids[0]]
    assert not {"context_refs", "retrieval_scope"}.intersection(current)
    assert await native_documents(service, principal, current, []) == []
    for invalid_id in ["sha256:" + "0" * 64, hashlib.sha256(data).hexdigest()]:
        with pytest.raises(HTTPException, match="NATIVE_ATTACHMENT_MISMATCH"):
            await native_documents(service, principal, current, [{**attachment, "attachment_id": invalid_id}])


@pytest.mark.asyncio
async def test_tool_filter_cannot_expand_explicit_user_publications(principal, tmp_path):
    from aird_agent.capabilities.literature.handler import LiteratureTools
    selected = {"document_id": str(uuid4()), "publication_id": str(uuid4())}
    tool = object.__new__(LiteratureTools)
    tool.settings = SimpleNamespace(storage_root=tmp_path)
    tool.context_request = lambda run: {"retrieval_scope": run["retrieval_scope"]}
    # It must fail before a host retrieval, independently of the model's arguments.
    run = {"id": str(uuid4()), "retrieval_scope": {"knowledge_documents": [selected]}}
    with pytest.raises(HTTPException, match="RETRIEVAL_FILTER_CANNOT_EXPAND"):
        await tool.dispatch(principal, run, {}, "knowledge_search", {
            "query": "test", "documents": [{"document_id": str(uuid4()), "publication_id": str(uuid4())}],
        }, "call")
