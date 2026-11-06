"""Reconstruct the business adapter over a persisted native Session/Turn mapping."""

from uuid import uuid4

import pytest
from fastapi import HTTPException
from psycopg.types.json import Jsonb

from aird_agent.core.contracts import digest
from aird_agent.hosts.jsd.composition import Service


async def test_reconstruction_keeps_native_mapping_deduplicates_events_and_fences_unknown_writes(settings, store, principal):
    class Host:
        denied = False

        async def call(self, user, action, args, run_id=None):
            if self.denied:
                raise HTTPException(403, "ACCESS_REVOKED")
            if action == "runtime_context":
                return {"pack_ids": ["core", "literature"], "context_refs": [],
                        "documents": [], "scope_mode": "AUTOMATIC"}
            return {"authorized": True}

    host = Host()
    first = Service(settings, store, host=host)
    second = None
    sid = "session-" + str(uuid4())
    try:
        run = await first.register_step(principal, {
            "session_id": sid, "turn": 1, "step": 1, "profile": "agent", "request": "记录研究决定",
        })
        rid, epoch = run["id"], run["epoch"]
        saved = await first.tool(principal, rid, epoch, "committed-note", "note_save", {"text": "已保存"})
        event = {"seq": 11, "type": "assistant/message", "data": {"turn": 1,
                 "message": {"id": "reply-one", "content": [{"type": "text", "text": "已记录。"}]}}}
        await first.project_event(principal, {"session_id": sid, "event": event})
        args = {"text": "结果未知，禁止重放"}
        signature = digest({"tool": "note_save", "arguments": args,
                            "scope": run["scope_hash"], "pack_versions": run["pack_versions"]})
        async with store.transaction(principal, rid) as conn:
            await conn.execute(
                "INSERT INTO agent_runtime.harness_calls(run_id,call_id,request_hash,status,tool_name,arguments,execution_epoch) "
                "VALUES(%s,'uncertain-note',%s,'UNKNOWN','note_save',%s,%s)",
                (rid, signature, Jsonb(args), epoch),
            )
        await first.http.aclose()

        # New process instance has no in-memory model, queue, lease or checkpoint state.
        second = Service(settings, store, host=host)
        restored = await second.session_run(principal, {"run_id": rid, "session_id": sid, "turn": 1})
        assert restored["dsh_session_id"] == sid and restored["dsh_turn"] == 1
        assert await second.session_conversation(principal.model_copy(update={"actor_id": uuid4()}), sid) is None
        assert await second.tool(principal, rid, epoch, "committed-note", "note_save", {"text": "已保存"}) == saved
        await second.project_event(principal, {"session_id": sid, "event": event})
        conversation = await second.session_conversation(principal, sid)
        assert len([item for item in conversation["messages"] if item["id"] == "reply-one"]) == 1
        assert len(conversation["notes"]) == 1
        assert len([item for item in await store.events(principal, rid) if item["type"] == "message.completed"]) == 1
        with pytest.raises(HTTPException, match="TOOL_RESULT_UNKNOWN"):
            await second.tool(principal, rid, epoch, "uncertain-note", "note_save", args)
        gate = {"run_id": rid, "session_id": sid, "turn": 1, "kind": "model"}
        with pytest.raises(HTTPException, match="SIDE_EFFECT_RECONCILIATION_REQUIRED"):
            await second.gate_dsh(principal, gate)
        host.denied = True
        with pytest.raises(HTTPException, match="ACCESS_REVOKED"):
            await second.gate_dsh(principal, gate)
        assert (await store.get(principal, "run", rid))["model_calls"] == 0
        assert len((await second.session_conversation(principal, sid))["notes"]) == 1
    finally:
        await first.http.aclose()
        if second:
            await second.http.aclose()
