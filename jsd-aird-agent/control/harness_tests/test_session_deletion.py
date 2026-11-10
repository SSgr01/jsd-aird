import pytest
from uuid import uuid4
from fastapi import HTTPException
from aird_agent.core.session_deletion import delete_session, require_session_available


async def test_delete_removes_readable_projection_but_retains_operation_receipts(store, principal):
    other_principal = principal.model_copy(update={"actor_id": uuid4(), "organization_id": uuid4()})
    sid = "session-deletion-boundary"
    async with store.transaction(principal, sid) as conn:
        conversation = await store.create(principal, "conversation", {"dsh_session_id": sid, "messages": [{"text":"正文"}]}, conn)
        run = await store.create(principal, "run", {"dsh_session_id":sid, "status":"FAILED", "request":"正文", "last_text":"正文"}, conn)
        await store.event(principal, run["id"], "message.delta", {"delta":"正文"}, conn)
        await conn.execute("INSERT INTO agent_runtime.harness_calls(run_id,call_id,request_hash,tool_name,status) VALUES(%s,'write','hash','create_experiment_draft','UNKNOWN')", (run["id"],))
    await delete_session(store, principal, {"session_id":sid, "phase":"prepare"})
    with pytest.raises(HTTPException) as error:
        await require_session_available(store, principal, sid)
    assert error.value.status_code == 410
    await require_session_available(store, other_principal, sid)
    for _ in range(2):
        assert (await delete_session(store, principal, {"session_id":sid, "phase":"commit"}))["deleted"]
    with pytest.raises(HTTPException):
        await store.get(principal, "conversation", conversation["id"])
    saved = await store.get(principal, "run", run["id"])
    assert saved["session_deleted"] and "last_text" not in saved
    assert not any(item["id"] == run["id"] for item in await store.list(principal,"run"))
    async with store.pool.connection() as conn:
        call = await (await conn.execute("SELECT status FROM agent_runtime.harness_calls WHERE run_id=%s", (run["id"],))).fetchone()
        assert call["status"] == "UNKNOWN"
        assert not await (await conn.execute("SELECT 1 FROM agent_runtime.harness_events WHERE run_id=%s AND kind='message.delta'", (run["id"],))).fetchone()
