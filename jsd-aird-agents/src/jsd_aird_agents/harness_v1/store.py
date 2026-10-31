"""Owned records and ordered durable events; all changes use database transactions."""

from contextlib import asynccontextmanager
from pathlib import Path
from uuid import uuid4

from fastapi import HTTPException
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb
from psycopg_pool import AsyncConnectionPool

from .contracts import Principal, digest


class Store:
    def __init__(self, dsn: str):
        self.pool = AsyncConnectionPool(
            dsn, open=False, min_size=1, max_size=12, kwargs={"autocommit": True, "row_factory": dict_row}
        )

    async def migrate(self):
        async with self.pool.connection() as conn, conn.transaction():
            await conn.execute("SELECT pg_advisory_xact_lock(1847091010)")
            await conn.execute(Path(__file__).with_name("schema.sql").read_text(encoding="utf-8"))

    @asynccontextmanager
    async def transaction(self, principal: Principal, key: str):
        async with self.pool.connection() as conn, conn.transaction():
            # Serialize short mutations for an owner, even when operations use different
            # idempotency/run keys. No remote work is performed while holding this lock.
            await conn.execute(
                "SELECT pg_advisory_xact_lock(hashtextextended(%s,16))", (":".join(principal.key),)
            )
            await conn.execute(
                "SELECT pg_advisory_xact_lock(hashtextextended(%s,17))", (":".join((*principal.key, key)),)
            )
            yield conn

    async def get(self, principal: Principal, kind: str, rid: str, conn=None):
        if conn is None:
            async with self.pool.connection() as conn:
                return await self.get(principal, kind, rid, conn)
        row = await (
            await conn.execute(
                "SELECT id,revision,payload,created_at,updated_at FROM agent_runtime.harness_records "
                "WHERE kind=%s AND id=%s AND organization_id=%s AND actor_id=%s",
                (kind, rid, *principal.key),
            )
        ).fetchone()
        if not row:
            raise HTTPException(404, "Resource not found")
        return {
            **row["payload"],
            "id": str(row["id"]),
            "revision": row["revision"],
            "created_at": row["created_at"].isoformat(),
            "updated_at": row["updated_at"].isoformat(),
        }

    async def list(self, principal: Principal, kind: str, limit: int = 100):
        async with self.pool.connection() as conn:
            rows = await (
                await conn.execute(
                    "SELECT id FROM agent_runtime.harness_records WHERE kind=%s AND organization_id=%s "
                    "AND actor_id=%s ORDER BY updated_at DESC LIMIT %s",
                    (kind, *principal.key, min(limit, 200)),
                )
            ).fetchall()
            return [await self.get(principal, kind, str(row["id"]), conn) for row in rows]

    async def create(self, principal: Principal, kind: str, payload: dict, conn, rid=None):
        rid = rid or str(uuid4())
        await conn.execute(
            "INSERT INTO agent_runtime.harness_records(kind,id,organization_id,actor_id,payload) "
            "VALUES(%s,%s,%s,%s,%s)",
            (kind, rid, *principal.key, Jsonb(payload)),
        )
        return await self.get(principal, kind, rid, conn)

    async def update(self, principal: Principal, kind: str, record: dict, conn):
        payload = {k: v for k, v in record.items() if k not in {"id", "revision", "created_at", "updated_at"}}
        result = await conn.execute(
            "UPDATE agent_runtime.harness_records SET payload=%s,revision=revision+1,updated_at=now() "
            "WHERE kind=%s AND id=%s AND organization_id=%s AND actor_id=%s AND revision=%s",
            (Jsonb(payload), kind, record["id"], *principal.key, record["revision"]),
        )
        if result.rowcount != 1:
            raise HTTPException(409, "Resource revision changed")
        return await self.get(principal, kind, record["id"], conn)

    async def once(self, principal, operation, key, request, callback):
        if not key or len(key) > 200:
            raise HTTPException(400, "Idempotency-Key is required")
        async with self.transaction(principal, f"{operation}:{key}") as conn:
            row = await (
                await conn.execute(
                    "SELECT request_hash,response FROM agent_runtime.harness_idempotency "
                    "WHERE organization_id=%s AND actor_id=%s AND operation=%s AND key=%s",
                    (*principal.key, operation, key),
                )
            ).fetchone()
            if row:
                if row["request_hash"] != digest(request):
                    raise HTTPException(409, "Idempotency key was used with different input")
                return row["response"]
            response = await callback(conn)
            await conn.execute(
                "INSERT INTO agent_runtime.harness_idempotency VALUES(%s,%s,%s,%s,%s,%s,now())",
                (*principal.key, operation, key, digest(request), Jsonb(response)),
            )
            return response

    async def event(self, principal, run_id, kind, payload, conn, key=None):
        await conn.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s,18))", (str(run_id),))
        await conn.execute(
            "INSERT INTO agent_runtime.harness_events(run_id,organization_id,actor_id,event_key,kind,payload) "
            "VALUES(%s,%s,%s,%s,%s,%s) ON CONFLICT(run_id,event_key) DO NOTHING",
            (run_id, *principal.key, key or str(uuid4()), kind, Jsonb(payload)),
        )

    async def checkpoint(self, principal, reference):
        if reference is None or isinstance(reference, str):
            return reference
        async with self.pool.connection() as conn:
            row = await (
                await conn.execute(
                    "SELECT content FROM agent_runtime.harness_checkpoints "
                    "WHERE organization_id=%s AND actor_id=%s AND sha256=%s",
                    (*principal.key, reference["sha256"]),
                )
            ).fetchone()
        if not row:
            raise HTTPException(409, "CHECKPOINT_UNAVAILABLE")
        return row["content"]

    async def events(self, principal, run_id, after=0):
        await self.get(principal, "run", run_id)
        async with self.pool.connection() as conn:
            if after:
                cursor = await (
                    await conn.execute(
                        "SELECT run_id FROM agent_runtime.harness_events WHERE seq=%s", (after,)
                    )
                ).fetchone()
                if not cursor or str(cursor["run_id"]) != str(run_id):
                    raise HTTPException(409, "Event cursor belongs to a different run")
            rows = await (
                await conn.execute(
                    "SELECT * FROM agent_runtime.harness_events WHERE run_id=%s "
                    "AND organization_id=%s AND actor_id=%s AND seq>%s ORDER BY seq LIMIT 200",
                    (run_id, *principal.key, after),
                )
            ).fetchall()
        return [
            {
                "schemaVersion": 1,
                "seq": row["seq"],
                "runId": str(row["run_id"]),
                "type": row["kind"],
                "timestamp": row["created_at"].isoformat(),
                "payload": row["payload"],
            }
            for row in rows
        ]
