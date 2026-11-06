from unittest.mock import AsyncMock
from types import MethodType
from uuid import uuid4

import pytest
from fastapi import HTTPException

from aird_agent.core.business_service import BusinessService
from aird_agent.hosts.jsd.session_views import SessionViews
from aird_agent.capabilities.registry import default_registry


@pytest.mark.asyncio
async def test_native_profile_cannot_rebind_an_existing_business_conversation(principal):
    service = object.__new__(BusinessService)
    service.session_conversation = AsyncMock(return_value={"profile": "fast_qa"})
    service.authorize_conversation = AsyncMock()
    with pytest.raises(HTTPException) as refused:
        await service.register_step(principal, {"session_id": "native", "turn": 1, "profile": "agent"})
    assert refused.value.status_code == 403
    assert refused.value.detail == "SESSION_PROFILE_CHANGED"
    service.authorize_conversation.assert_not_called()


@pytest.mark.asyncio
async def test_native_prefixed_session_ids_keep_exact_owner_scoped_mapping(principal, store):
    service = object.__new__(SessionViews)
    service.store = store
    service.packs = default_registry()
    service.java = AsyncMock(return_value={"pack_ids": ["files"]})
    service.authorize_conversation = AsyncMock()
    service.session_conversation = MethodType(BusinessService.session_conversation, service)
    sid = "session-" + str(uuid4())
    first = await service.ensure_conversation(principal, sid, "agent")
    second = await service.ensure_conversation(principal, sid, "agent")
    assert first["id"] == second["id"]
    assert first["dsh_session_id"] == sid
    assert first["id"] != sid
    foreign = principal.model_copy(update={"actor_id": uuid4()})
    assert await service.session_conversation(foreign, sid) is None
    with pytest.raises(HTTPException, match="SESSION_PROFILE_CHANGED"):
        await service.ensure_conversation(principal, sid, "fast_qa")


@pytest.mark.asyncio
@pytest.mark.parametrize("sid", [None, "", "../session", "session\ninvalid", "a" * 201])
async def test_invalid_native_session_id_is_a_client_error(principal, sid):
    service = object.__new__(SessionViews)
    with pytest.raises(HTTPException) as refused:
        await service.ensure_conversation(principal, sid, "agent")
    assert refused.value.status_code == 422
