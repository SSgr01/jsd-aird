"""Append-only execution receipts and owner-audited reconciliation without replay."""

from fastapi import HTTPException
from psycopg.types.json import Jsonb

from aird_agent.core.contracts import digest
from aird_agent.core.receipts import receipt as receipt


async def list_operations(service, principal, rid):
    run = await service.store.get(principal, "run", rid)
    await service.authorized_topic(principal, run, execution=False)
    async with service.store.pool.connection() as conn:
        rows = await (
            await conn.execute(
                "SELECT c.call_id,c.tool_name,c.status,c.request_hash,c.execution_epoch,c.updated_at,"
                "r.receipt_id,r.authority,r.created_at AS receipt_at FROM agent_runtime.harness_calls c "
                "LEFT JOIN agent_runtime.harness_receipts r ON r.run_id=c.run_id AND r.call_id=c.call_id "
                "WHERE c.run_id=%s ORDER BY c.updated_at,c.call_id",
                (rid,),
            )
        ).fetchall()
    return [
        {
            **{k: v for k, v in row.items() if k not in {"updated_at", "receipt_at", "receipt_id"}},
            "updated_at": row["updated_at"].isoformat(),
            "receipt_id": str(row["receipt_id"]) if row["receipt_id"] else None,
            "receipt_at": row["receipt_at"].isoformat() if row["receipt_at"] else None,
        }
        for row in rows
    ]


async def reconcile(service, principal, rid, call_id):
    run = await service.store.get(principal, "run", rid)
    await service.authorized_topic(principal, run, execution=False)
    async with service.store.pool.connection() as conn:
        operation = await (
            await conn.execute(
                "SELECT * FROM agent_runtime.harness_calls WHERE run_id=%s AND call_id=%s",
                (rid, call_id),
            )
        ).fetchone()
        local_receipt = await (
            await conn.execute(
                "SELECT payload FROM agent_runtime.harness_receipts WHERE run_id=%s AND call_id=%s "
                "AND organization_id=%s AND actor_id=%s AND authority='control-transaction'",
                (rid, call_id, *principal.key),
            )
        ).fetchone()
    if not operation:
        raise HTTPException(404, "OPERATION_NOT_FOUND")
    if operation["status"] != "UNKNOWN":
        raise HTTPException(409, "Only operations with unknown outcomes require reconciliation")
    # A missing authority record is not proof of nonexecution. It never unlocks a retry.
    if local_receipt:
        authority = {
            "status": "SUCCEEDED",
            "operation_key": digest([rid, call_id]),
            "result": local_receipt["payload"],
        }
    elif operation["tool_name"] == "dsh_execute":
        from aird_agent.hosts.jsd.native_files import reconcile_native
        authority = await reconcile_native(service, principal, run, operation)
        authority["operation_key"] = digest([rid, call_id])
    elif operation["tool_name"] == "literature_acquire":
        result = await service.java(
            principal, "literature_acquire_status",
            {"conversation_id": run["conversation_id"], "work_id": operation["arguments"]["work_id"]}, rid,
        )
        known = (
            result.get("status") in {"ACQUIRING", "PARSING", "READY", "FAILED"}
            and result.get("conversation_id") == run["conversation_id"]
            and result.get("work_id") == operation["arguments"]["work_id"]
            and result.get("kind") == "EXTERNAL_FULLTEXT"
        )
        # The durable reservation confirms submission, not successful reading of the PDF.
        authority = {"status": "SUCCEEDED" if known else "UNKNOWN", "operation_key": digest([rid, call_id]), "result": result}
    else:
        authority = await service.java(
            principal,
            "operation_receipt",
            {"tool": operation["tool_name"], "operation_key": digest([rid, call_id]),
             "arguments": operation["arguments"],
             **({"close_uncommitted": True} if operation["tool_name"] == "create_experiment_draft" else {})},
            rid,
        )
    confirmed = (
        authority.get("status") in {"SUCCEEDED", "FAILED"}
        and authority.get("operation_key") == digest([rid, call_id])
        and isinstance(authority.get("result"), dict)
        and (local_receipt or authority["result"].get("id"))
    )
    output = (
        service.bindings.output_receipt(
            run, operation["tool_name"], operation["arguments"], authority["result"]
        )
        if confirmed and "error" not in authority["result"]
        else None
    )
    async with service.store.transaction(principal, rid) as conn:
        record = await (
            await conn.execute(
                "SELECT status FROM agent_runtime.harness_calls WHERE run_id=%s AND call_id=%s FOR UPDATE",
                (rid, call_id),
            )
        ).fetchone()
        if record["status"] != "UNKNOWN":
            raise HTTPException(409, "Operation outcome already changed")
        rid_receipt = None
        if confirmed:
            await receipt(service.store, principal, rid, call_id, "host-reconciled", authority["result"], conn)
            rid_receipt = (
                await (
                    await conn.execute(
                        "SELECT receipt_id FROM agent_runtime.harness_receipts WHERE run_id=%s AND call_id=%s",
                        (rid, call_id),
                    )
                ).fetchone()
            )["receipt_id"]
            await conn.execute(
                "UPDATE agent_runtime.harness_calls SET status=%s,result=%s,updated_at=now() "
                "WHERE run_id=%s AND call_id=%s",
                ("FAILED" if "error" in authority["result"] else "SUCCEEDED", Jsonb(authority["result"]), rid, call_id),
            )
            if output:
                current = await service.store.get(principal, "run", rid, conn)
                outputs = current.setdefault("registered_outputs", [])
                if not any(
                    value.get("id") == output["id"] and value["kind"] == output["kind"] for value in outputs
                ):
                    outputs.append(output)
                    current.pop("completion", None)
                    await service.store.update(principal, "run", current, conn)
        outcome = ("CONFIRMED_FAILED" if "error" in authority["result"] else "CONFIRMED_SUCCEEDED") if confirmed else "STILL_UNKNOWN"
        await conn.execute(
            "INSERT INTO agent_runtime.harness_reconciliation_audit(run_id,call_id,organization_id,actor_id,result,receipt_id) "
            "VALUES(%s,%s,%s,%s,%s,%s)",
            (rid, call_id, *principal.key, outcome, rid_receipt),
        )
        await service.store.event(
            principal, rid, "operation.reconciled", {"call_id": call_id, "outcome": outcome}, conn
        )
    return {"outcome": outcome, "replayed": False, "receipt_id": str(rid_receipt) if rid_receipt else None}


async def monitor(service, principal):
    await service.java(principal, "runtime_context", {"profile": "agent", "context_refs": []})
    async with service.store.pool.connection() as conn:
        rows = await (
            await conn.execute(
                "SELECT payload->>'status' AS status,count(*) AS count FROM agent_runtime.harness_records "
                "WHERE kind='run' AND organization_id=%s AND actor_id=%s GROUP BY payload->>'status'",
                principal.key,
            )
        ).fetchall()
        unknown = await (
            await conn.execute(
                "SELECT count(*) AS count FROM agent_runtime.harness_calls c JOIN agent_runtime.harness_records r "
                "ON r.kind='run' AND r.id=c.run_id WHERE r.organization_id=%s AND r.actor_id=%s AND c.status='UNKNOWN'",
                principal.key,
            )
        ).fetchone()
    return {"runs": {row["status"]: row["count"] for row in rows}, "unknown_operations": unknown["count"]}
