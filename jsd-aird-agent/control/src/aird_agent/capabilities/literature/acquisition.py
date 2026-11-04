"""Attach only host-acquired, ready full text to the active run under its lease."""

from fastapi import HTTPException
from psycopg.types.json import Jsonb

from aird_agent.core.receipts import receipt
from aird_agent.hosts.jsd.task_state import scope_digest

READERS = ["private_document_outline", "private_document_search", "private_document_read"]


async def acquired_source(runtime, principal, run, result, work_id):
    if result.get("status") == "NOT_STARTED":
        return result
    if result.get("conversation_id") != run["conversation_id"] or result.get("work_id") != work_id:
        raise HTTPException(502, "ACQUIRED_SOURCE_IDENTITY_MISMATCH")
    async with runtime.store.transaction(principal, run["id"]) as conn:
        current = await runtime.active(principal, run["id"], run["epoch"], conn)
        # A host reservation proves that the tool committed, even if its HTTP response was lost.
        if result.get("status") != "UNKNOWN":
            rows = await (
                await conn.execute(
                    "UPDATE agent_runtime.harness_calls SET status='SUCCEEDED',result=%s,updated_at=now() "
                    "WHERE run_id=%s AND tool_name='literature_acquire' AND status='UNKNOWN' "
                    "AND arguments->>'work_id'=%s RETURNING call_id",
                    (Jsonb(result), run["id"], work_id),
                )
            ).fetchall()
            for row in rows:
                await receipt(runtime.store, principal, run["id"], row["call_id"], "tool-result", result, conn)
        if result.get("status") == "READY" and result["id"] not in (current.get("private_source_ids") or []):
            if result.get("kind") != "EXTERNAL_FULLTEXT" or not result.get("sha256"):
                raise HTTPException(502, "ACQUIRED_SOURCE_NOT_VERIFIED")
            conversation = await runtime.store.get(principal, "conversation", run["conversation_id"], conn)
            if conversation.get("active_run") != run["id"]:
                raise HTTPException(409, "RUN_INACTIVE_OR_LEASE_LOST")
            source_ids = [*(current.get("private_source_ids") or []), result["id"]]
            if len(source_ids) > 20:
                raise HTTPException(413, "PRIVATE_SOURCE_COUNT_EXCEEDED")
            proposed = {**current, "private_source_ids": source_ids}
            scope = await runtime.java(principal, "runtime_context", runtime.context_request(proposed), run["id"])
            if scope.get("private_sources_ready") is False:
                raise HTTPException(409, "ACQUIRED_SOURCE_NOT_READY")
            if {"document_id": result["id"], "publication_id": result.get("version_id")} not in scope.get("private_documents", []):
                raise HTTPException(502, "ACQUIRED_SOURCE_VERSION_MISMATCH")
            current.update(
                private_source_ids=source_ids,
                private_documents=scope.get("private_documents", []),
                scope_hash=scope_digest(scope),
            )
            for key in ("navigation_cache", "ranking_snapshots", "navigation_stalls", "completion"):
                current.pop(key, None)
            conversation["private_source_ids"] = list(
                dict.fromkeys([*(conversation.get("private_source_ids") or []), result["id"]])
            )
            await runtime.store.update(principal, "run", current, conn)
            await runtime.store.update(principal, "conversation", conversation, conn)
            run.update(current)
    if result.get("status") == "READY":
        return {**result, "activate_tools": READERS, "document_id": result["id"]}
    return {
        **result,
        "instruction": (
            "结果还在确认，请先不要重复提交。"
            if result.get("status") == "UNKNOWN"
            else "尚未取得可读全文；获取和解析中只检查结果，不重复执行或声称已读原文。"
        ),
    }
