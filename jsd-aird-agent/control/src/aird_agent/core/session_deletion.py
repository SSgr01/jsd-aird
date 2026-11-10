"""Deletion fences for business projections; native plugins own the session files."""
import re
from fastapi import HTTPException
from psycopg.types.json import Jsonb


async def deletion_state(store, principal, sid, conn=None):
    if conn is None:
        async with store.pool.connection() as conn:
            return await deletion_state(store, principal, sid, conn)
    return await (await conn.execute(
        "SELECT id,payload FROM agent_runtime.harness_records WHERE kind='session_deletion' "
        "AND organization_id=%s AND actor_id=%s AND payload->>'dsh_session_id'=%s",
        (*principal.key, sid))).fetchone()


async def require_session_available(store, principal, sid, conn=None):
    if await deletion_state(store, principal, sid, conn):
        raise HTTPException(410, "SESSION_DELETED")


async def delete_session(store, principal, body):
    sid, phase = body.get("session_id"), body.get("phase")
    if principal.automation_grant_id:
        raise HTTPException(403, "INTERACTIVE_AUTHORIZATION_REQUIRED")
    if not isinstance(sid, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,200}", sid) or phase not in {"prepare", "commit"}:
        raise HTTPException(422, "INVALID_SESSION_DELETION")
    async with store.transaction(principal, "session:" + sid) as conn:
        state = await deletion_state(store, principal, sid, conn)
        if state and state["payload"]["phase"] == "commit":
            return {"deleted": True}
        if state is None:
            await store.create(principal, "session_deletion", {"dsh_session_id": sid, "phase": phase}, conn)
        else:
            await conn.execute("UPDATE agent_runtime.harness_records SET payload=payload || %s,revision=revision+1,updated_at=now() "
                "WHERE kind='session_deletion' AND id=%s AND organization_id=%s AND actor_id=%s",
                (Jsonb({"phase": phase}), state["id"], *principal.key))
        if phase == "commit":
            # Keep operation identities and receipts for reconciliation, while
            # removing readable conversations, output text and event projections.
            rows = await (await conn.execute(
                "SELECT id FROM agent_runtime.harness_records WHERE kind='run' AND organization_id=%s "
                "AND actor_id=%s AND payload->>'dsh_session_id'=%s", (*principal.key, sid))).fetchall()
            ids = [row["id"] for row in rows]
            if ids:
                # Native deletion commits only after the Agent has stopped.
                # A deleted session cannot be queried again to release a lease.
                await conn.execute("UPDATE agent_runtime.harness_admission SET state='RELEASED' "
                    "WHERE run_id=ANY(%s) AND organization_id=%s AND actor_id=%s", (ids,*principal.key))
                await conn.execute("DELETE FROM agent_runtime.harness_events WHERE run_id=ANY(%s) "
                    "AND organization_id=%s AND actor_id=%s AND (kind LIKE 'message.%%' OR kind LIKE 'conversation.%%')",
                    (ids, *principal.key))
                await conn.execute("UPDATE agent_runtime.harness_records SET "
                    "payload=(payload-'last_text'-'result'-'request') || %s,revision=revision+1,updated_at=now() "
                    "WHERE kind='run' AND id=ANY(%s) AND organization_id=%s AND actor_id=%s",
                    (Jsonb({"session_deleted": True}), ids, *principal.key))
            await conn.execute("DELETE FROM agent_runtime.harness_records WHERE kind='conversation' "
                "AND organization_id=%s AND actor_id=%s AND payload->>'dsh_session_id'=%s", (*principal.key, sid))
    return {"deleted": phase == "commit", "prepared": True}
