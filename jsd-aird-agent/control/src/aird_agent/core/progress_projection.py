"""Advance native progress cursors without transferring or rewriting the Run ledger."""

from fastapi import HTTPException
from psycopg.types.json import Jsonb

from aird_agent.core.stage_timing import measure

BUSINESS_EVENTS = frozenset({"assistant/message", "user/message", "turn/end", "agent/inbox/spliced"})


async def stream_binding(store, principal, body):
    async with store.connection() as conn:
        with measure("db_read_ms"):
            run = await (await conn.execute(
                "SELECT payload->>'engine_version' AS engine,payload->>'dsh_session_id' AS session,"
                "payload->'dsh_turn' AS turn FROM agent_runtime.harness_records "
                "WHERE kind='run' AND id=%s AND organization_id=%s AND actor_id=%s", (body["run_id"], *principal.key),
            )).fetchone()
    if not run:
        raise HTTPException(404, "Resource not found")
    if run['engine'] != 'dsh-v1' or run['session'] != body['session_id']:
        raise HTTPException(403, "SESSION_NOT_OWNED")
    if body.get('turn') is not None and run['turn'] != body['turn']:
        raise HTTPException(409, "TURN_BINDING_CONFLICT")
    return {"id":body['run_id']}


async def project_progress(store, principal, body):
    async def conversation(conn):
        with measure("db_read_ms"):
            return await (await conn.execute(
                "SELECT id,payload->>'active_run' AS active_run,payload->>'last_run' AS last_run,"
                "payload->'dsh_seq' AS dsh_seq FROM agent_runtime.harness_records "
                "WHERE kind='conversation' AND organization_id=%s AND actor_id=%s "
                "AND payload->>'dsh_session_id'=%s", (*principal.key, body["session_id"]),
            )).fetchone()

    async with store.connection() as conn:
        current = await conversation(conn)
    if not current:
        return {"accepted": True}
    async with store.transaction(principal, str(current["id"])) as conn:
        current = await conversation(conn)
        seq = body["event"]["seq"]
        if not current or seq <= (current["dsh_seq"] if current["dsh_seq"] is not None else -1):
            return {"accepted": True}
        rid = current["active_run"] or current["last_run"]
        if not rid:
            return {"accepted": True}
        turn = body["event"].get("data", {}).get("turn")
        with measure("db_read_ms"):
            run = await (await conn.execute(
                "SELECT payload->'dsh_turn' AS turn FROM agent_runtime.harness_records "
                "WHERE kind='run' AND id=%s AND organization_id=%s AND actor_id=%s",
                (rid, *principal.key),
            )).fetchone()
            if not run or (turn is not None and run["turn"] not in {None, turn}):
                historical = await (await conn.execute(
                    "SELECT id FROM agent_runtime.harness_records WHERE kind='run' "
                    "AND organization_id=%s AND actor_id=%s AND payload->>'dsh_session_id'=%s "
                    "AND (payload->>'dsh_turn')::int=%s", (*principal.key, body["session_id"], turn),
                )).fetchone() if run else None
                if not historical:
                    return {"accepted": True, "pending_registration": True}
        # The owner transaction also protects message and terminal projections.
        # This cursor is diagnostic progress only; it never admits work or settles receipts.
        with measure("db_write_ms"):
            await conn.execute(
                "UPDATE agent_runtime.harness_records SET payload=jsonb_set(payload,'{dsh_seq}',%s),"
                "revision=revision+1,updated_at=now() WHERE kind='conversation' AND id=%s "
                "AND organization_id=%s AND actor_id=%s", (Jsonb(seq), current["id"], *principal.key),
            )
    return {"accepted": True}
