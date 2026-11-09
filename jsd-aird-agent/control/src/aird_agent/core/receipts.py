"""Durable receipts for completed tool results, independent of the business authority."""

from uuid import uuid4

from psycopg.types.json import Jsonb


async def receipt(store, principal, run_id, call_id, authority, result, conn):
    await conn.execute(
        "INSERT INTO agent_runtime.harness_receipts(receipt_id,run_id,call_id,organization_id,actor_id,authority,payload) "
        "VALUES(%s,%s,%s,%s,%s,%s,%s) ON CONFLICT(run_id,call_id) DO NOTHING",
        (str(uuid4()), run_id, call_id, *principal.key, authority, Jsonb(result)),
    )
