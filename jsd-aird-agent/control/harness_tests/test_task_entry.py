from unittest.mock import AsyncMock
from uuid import uuid4

import pytest
from fastapi import HTTPException
from pydantic import ValidationError

from aird_agent.hosts.jsd.composition import Service
from aird_agent.server.task_entry import TaskRequest, create_task
from test_business_dispatch import Host


def test_public_task_contract_cannot_inject_a_second_model_prompt():
    values = {"canonical_conversation_id": uuid4(), "canonical_user_message_id": uuid4(), "question": "Explain"}
    for field in ("instructions", "prompt", "candidates", "profile"):
        with pytest.raises(ValidationError):
            TaskRequest(**values, **{field: "untrusted"})


async def test_http_retry_reuses_native_session_and_rejects_changed_input(settings, store, principal):
    host = Host()
    host.scope.update(scope_mode="AUTOMATIC", documents=[])
    service = Service(settings, store, host=host)
    service.dsh_command = AsyncMock(side_effect=lambda actor, action, body:
        {"sessionId": body["session_id"]} if action == "create" else {"accepted": True})
    request = TaskRequest(canonical_conversation_id=uuid4(), canonical_user_message_id=uuid4(), question="Explain")
    try:
        first = await create_task(service, principal, request, "task:test")
        again = await create_task(service, principal, request, "task:test")
        assert first["id"] == again["id"]
        assert first["dsh_session_id"] == again["dsh_session_id"]
        assert [call.args[1] for call in service.dsh_command.await_args_list] == ["create", "prompt"]
        assert again["canonical_user_message_id"] == str(request.canonical_user_message_id)
        with pytest.raises(HTTPException, match="different input"):
            await create_task(service, principal, request.model_copy(update={"question": "Changed"}), "task:test")
        host.denied = True
        with pytest.raises(HTTPException, match="TEST_ACCESS_REVOKED"):
            await create_task(service, principal, request, "task:test")
    finally:
        await service.http.aclose()


async def test_canonical_channel_is_owner_scoped_and_followup_keeps_native_history(settings, store, principal):
    host = Host()
    host.scope.update(scope_mode="AUTOMATIC", documents=[])
    service = Service(settings, store, host=host)
    service.dsh_command = AsyncMock(side_effect=lambda actor, action, body:
        {"sessionId": body["session_id"]} if action == "create" else {"accepted": True})
    request = TaskRequest(canonical_conversation_id=uuid4(), canonical_user_message_id=uuid4(), question="First")
    other = principal.model_copy(update={"actor_id": uuid4()})
    try:
        first = await create_task(service, principal, request, "same-key")
        async with store.transaction(principal, first["id"]) as conn:
            current = await store.get(principal, "run", first["id"], conn)
            current["status"] = "SUCCEEDED"
            await store.update(principal, "run", current, conn)
        followup = request.model_copy(update={"question": "Follow up", "canonical_user_message_id": uuid4()})
        second = await create_task(service, principal, followup, "next-key")
        foreign = await create_task(service, other, request, "same-key")
        assert second["conversation_id"] == first["conversation_id"]
        assert second["dsh_session_id"] == first["dsh_session_id"]
        assert foreign["conversation_id"] != first["conversation_id"]
        assert foreign["dsh_session_id"] != first["dsh_session_id"]
        assert [call.args[1] for call in service.dsh_command.await_args_list] == ["create", "prompt", "prompt", "create", "prompt"]
        assert all("messages" not in call.args[2] and "prompt" not in call.args[2] for call in service.dsh_command.await_args_list)
    finally:
        await service.http.aclose()
