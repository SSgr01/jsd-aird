"""Opt-in real PostgreSQL restart drill; requires a fresh dedicated *_pi_faults_test DB."""

import os
import time
from urllib.parse import urlsplit

import pytest
from fastapi import HTTPException
from psycopg.types.json import Jsonb

from aird_agent.core.store import Store
from aird_agent.hosts.jsd.composition import Service


async def test_process_reconstruction_fences_old_callbacks_and_preserves_unknown_writes(settings, principal):
    dsn = os.environ.get("HARNESS_FAULT_DATABASE_URL")
    if not dsn:
        pytest.skip("Requires a fresh dedicated HARNESS_FAULT_DATABASE_URL")
    assert urlsplit(dsn).path.endswith("_pi_faults_test")
    store = Store(dsn)
    await store.pool.open(wait=True, timeout=10)
    await store.migrate()

    class Host:
        denied = False

        async def call(self, user, action, args, run_id=None):
            if self.denied:
                raise HTTPException(403, "ACCESS_REVOKED")
            if action == "runtime_context":
                return {"pack_ids": ["core"], "context_refs": [], "documents": [], "scope_mode": "AUTOMATIC"}
            return {"authorized": True}

    host = Host()
    first = Service(settings, store, host=host)
    second = None
    rid = None
    try:
        from aird_agent.hosts.jsd.task_state import scope_digest

        context = await host.call(principal, "runtime_context", {})
        async with store.transaction(principal, "seed") as conn:
            conversation = await store.create(principal, "conversation", {"messages": []}, conn)
            run = await store.create(
                principal,
                "run",
                {
                    "conversation_id": conversation["id"],
                    "principal": principal.model_dump(mode="json"),
                    "profile": "agent",
                    "request": "synthetic drill",
                    "pack_ids": ["core"],
                    "pack_versions": first.packs.pin(["core"]),
                    "scope_hash": scope_digest(context),
                    "context_refs": [],
                    "source_documents": [],
                    "source_refs": [],
                    "evidence": {},
                    "messages": [],
                    "registered_outputs": [],
                    "approvals": {},
                    "status": "RUNNING",
                    "epoch": 1,
                    "lease_until": time.time() + 180,
                    "started_at": time.time(),
                    "active_seconds": 0,
                    "steps": 0,
                    "model_calls": 0,
                    "tool_calls": 0,
                },
                conn,
            )
        rid = run["id"]
        content = '{"type":"session","id":"synthetic-checkpoint"}\n'
        await first.callback(principal, rid, "checkpoint", {"epoch": 1, "content": content, "message_seq": 0})
        async with store.transaction(principal, rid) as conn:
            await store.event(principal, rid, "message.delta", {"delta": "first"}, conn)
            await store.event(principal, rid, "message.delta", {"delta": "second"}, conn)
            await conn.execute(
                "INSERT INTO agent_runtime.harness_calls(run_id,call_id,request_hash,status,tool_name,arguments,execution_epoch) "
                "VALUES(%s,'lost-write','synthetic','RUNNING','note_save',%s,1)",
                (rid, Jsonb({"text": "never replay"})),
            )
            current = await store.get(principal, "run", rid, conn)
            current["lease_until"] = time.time() - 1
            await store.update(principal, "run", current, conn)
        initial = await store.events(principal, rid)
        await first.http.aclose()
        # Construct a new coordinator exactly as a restarted process would; no in-memory run state.
        second = Service(settings, store, host=host)
        restored_user, restored = await second._claim()
        assert restored["id"] == rid and restored["epoch"] == 2
        assert restored_user == principal
        assert await store.checkpoint(principal, restored["checkpoint"]) == content
        replay = await store.events(principal, rid, initial[0]["seq"])
        assert [v["payload"]["delta"] for v in replay] == ["second"]
        with pytest.raises(HTTPException, match="RUN_INACTIVE_OR_LEASE_LOST"):
            await second.callback(
                principal, rid, "event", {"epoch": 1, "type": "message.delta", "payload": {"delta": "stale"}}
            )
        async with store.pool.connection() as conn:
            status = await (
                await conn.execute(
                    "SELECT status FROM agent_runtime.harness_calls WHERE run_id=%s AND call_id='lost-write'",
                    (rid,),
                )
            ).fetchone()
        assert status["status"] == "UNKNOWN"
        host.denied = True
        with pytest.raises(HTTPException, match="ACCESS_REVOKED"):
            await second.gate(principal, rid, 2)
        assert len(await store.events(principal, rid)) == len(initial)
    finally:
        if rid:
            async with store.transaction(principal, rid) as conn:
                current = await store.get(principal, "run", rid, conn)
                current["status"] = "CANCELLED"
                await store.update(principal, "run", current, conn)
        await first.http.aclose()
        if second:
            await second.http.aclose()
        await store.pool.close()
