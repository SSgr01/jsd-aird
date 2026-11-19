"""Merged role projections preserve legacy sessions and preferences without a database."""
import copy
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock
from uuid import uuid4

import pytest
from fastapi import HTTPException

from aird_agent.hosts.jsd.bindings import JsdBindings
from aird_agent.hosts.jsd.experts import expert_action

CATALOG = json.loads((Path(__file__).parents[3] / "jsd-aird-api/src/main/resources/ai/expert-presets.json")
                     .read_text(encoding="utf-8"))
MATERIALS = next(row for row in CATALOG["experts"] if row["id"] == "materials-advisor")
PROJECT = next(row for row in CATALOG["experts"] if row["id"] == "project-assistant")


def service_for_merge():
    prefs = {"pinned": ["technical-assistant", "materials-advisor", "project-assistant"]}
    conversation = {"id": "conversation", "expert": {"id": "technical-assistant"},
                    "messages": [{"text": "previous research"}]}

    async def get(principal, kind, rid, conn):
        return copy.deepcopy(prefs if kind == "expert-preferences" else conversation)

    async def update(principal, kind, value, conn):
        if kind == "expert-preferences":
            prefs.update(copy.deepcopy(value))

    async def java(principal, action, args):
        assert action == "expert_catalog"
        if args["action"] == "list":
            return copy.deepcopy(CATALOG)
        definitions = {"technical-assistant": MATERIALS, "materials-advisor": MATERIALS,
                       "project-assistant": PROJECT}
        return copy.deepcopy(definitions[args["expert_id"]])

    store = SimpleNamespace(transaction=MagicMock(), get=AsyncMock(side_effect=get),
                            update=AsyncMock(side_effect=update), list=AsyncMock(return_value=[
                                {"expert": {"id": "technical-assistant"}},
                                {"expert": {"id": "materials-advisor"}},
                                {"expert": {"id": "removed-expert"}},
                                {"expert": None}, {"expert": {"id": "project-assistant"}},
                            ]))
    store.transaction.return_value.__aenter__.return_value = object()
    return SimpleNamespace(store=store, java=AsyncMock(side_effect=java),
                           session_conversation=AsyncMock(return_value=conversation),
                           ensure_conversation=AsyncMock(return_value=conversation))


async def test_legacy_preferences_and_recent_sessions_show_one_merged_expert(principal):
    service = service_for_merge()
    result = await expert_action(service, principal, {"action": "list"})
    assert len(result["experts"]) == 7
    assert result["pinned"] == ["materials-advisor", "project-assistant"]
    assert result["recent"] == ["materials-advisor", "project-assistant"]
    service.store.update.assert_not_called()  # Listing only projects old preferences.
    for identity in ["materials-advisor", "technical-assistant"]:
        result = await expert_action(service, principal,
                                    {"action": "pin", "expert_id": identity, "pinned": False})
        assert result["pinned"] == ["project-assistant"]
        result = await expert_action(service, principal,
                                    {"action": "pin", "expert_id": identity, "pinned": True})
        assert result["pinned"] == ["project-assistant", "materials-advisor"]


async def test_legacy_session_read_fork_and_idempotent_bind_use_current_role(principal):
    service = service_for_merge()
    result = await expert_action(service, principal, {"action": "session", "session_id": "old"})
    assert result["expert"] == MATERIALS
    service.java.assert_awaited_with(principal, "expert_catalog",
                                    {"action": "history", "expert_id": "technical-assistant"})
    # Existing alias is the same role, even when this conversation has messages.
    result = await expert_action(service, principal,
                                {"action": "bind", "session_id": "old", "expert_id": "materials-advisor"})
    assert result["expert"] == MATERIALS
    with pytest.raises(HTTPException, match="EXPERT_REQUIRES_NEW_SESSION"):
        await expert_action(service, principal,
                            {"action": "bind", "session_id": "old", "expert_id": "project-assistant"})
    service.store.get.side_effect = None
    service.store.get.return_value = {"id": "child"}
    await expert_action(service, principal, {"action": "fork", "source_session_id": "old", "session_id": "child"})
    assert service.store.update.call_args.args[2]["expert"] == {"id": "materials-advisor"}


async def test_legacy_followup_prepares_current_definition_without_new_permissions(principal):
    service = service_for_merge()
    bindings = JsdBindings()
    bindings.runtime = service
    bindings.store = service.store
    service.store.get.side_effect = None
    service.store.get.return_value = {"expert": {"id": "technical-assistant"}}
    bindings.java = AsyncMock(return_value={"pack_ids": ["core"], "documents": []})
    bindings.packs = SimpleNamespace(pin=lambda ids: {value: {"version": "test"} for value in ids})
    # Use the actual request preparation path that resolves a stored session role.
    from aird_agent.server.contracts import RunRequest
    request = RunRequest(request="继续比较技术路线", conversation_id=uuid4())
    prepared, _ = await bindings.prepare_request(principal, request)
    assert prepared["expert"] == MATERIALS
    assert prepared["pack_ids"] == ["core"]
    with pytest.raises(HTTPException, match="CAPABILITY_NOT_AUTHORIZED"):
        await bindings.prepare_request(principal, request.model_copy(update={"pack_ids": ["literature"]}))
