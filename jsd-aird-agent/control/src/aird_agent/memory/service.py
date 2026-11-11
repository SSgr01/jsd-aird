"""Owned memory control plane. Remote work never runs inside a database transaction."""

import asyncio
import contextlib
import json
import logging
import re
import time
from datetime import datetime, timedelta
from uuid import NAMESPACE_URL, UUID, uuid4, uuid5
from zoneinfo import ZoneInfo

from fastapi import HTTPException
from psycopg.types.json import Jsonb

from aird_agent.core.contracts import Principal, digest
from aird_agent.core.session_deletion import deletion_state, require_session_available
from aird_agent.memory.client import Hindsight
from aird_agent.server.security import issue

log = logging.getLogger(__name__)
STABLE = re.compile(r"以后|今后|长期|始终|总是|习惯|偏好|通常|每次|remember|prefer|always|from now on", re.I)
DECISION = re.compile(r"确认|决定|采用|确定|下一步|待跟进|已验证|we decided|confirmed|follow.up", re.I)
INTENT = re.compile(r"记住|记下|更正|改为|忘掉|忘记|删除.{0,12}记忆|不要再|以后|今后|长期|始终|总是|习惯|偏好|每次|remember|prefer|forget|memory", re.I)
SECRET = re.compile(r"(?:api[_ -]?key|password|密码|密钥|token|secret)\s*[:=：]\s*\S+", re.I)
EXTERNAL = re.compile(r"(?:材料|文件|网页|邮件|文献)(?:中|里|原文|写道|要求)|(?:原文|引文|引用|示例|提示词|说明书|quoted|example|instructions?)\s*[:：]", re.I)
TEMPORARY = re.compile(r"^(?:请)?(?:确认|复述|总结|解释|翻译|概括).{0,20}(?:偏好|记忆|决定)|^(?:请)?用一(?:句|段)话", re.I)
DIRECT = re.compile(r"^(?:以后|今后|长期|始终|总是|每次|请|我(?:的|们|习惯|偏好|通常|决定|确认)|确认|决定|采用|确定|下一步|待跟进|已验证|remember|prefer|always|from now on|we decided|confirmed)", re.I)
TERMINAL = {"completed", "succeeded", "success", "done"}


def identity(user):
    return user.model_dump(mode="json", exclude={"automation_grant_id", "scope"})


def owner_id(user):
    return str(uuid5(NAMESPACE_URL, "jsd:personal-memory:" + ":".join(user.key)))


def source_key(source):
    return digest({k: source.get(k) for k in ("kind", "session_id", "event_seq", "offset", "hash")})


def exclusion_id(user, source):
    return str(uuid5(NAMESPACE_URL, ":".join(user.key) + ":" + source_key(source)))


def session_id(user, sid):
    return str(uuid5(NAMESPACE_URL, "jsd:memory-session:" + ":".join(user.key) + ":" + sid))


def public_receipt(job):
    state = job["status"]
    return {"operation_id": job["id"], "entry_id": job["entry_id"], "status": state,
            "summary": {"PENDING": "已提交，正在整理", "SUCCEEDED": "已保存" if job["action"] == "retain" else "已清理",
                        "FAILED": "操作未完成", "UNKNOWN": "结果待核对", "CANCELLED": "已停止处理"}.get(state, "正在处理"),
            "error": job.get("error"), "usage": job.get("usage"),
            "usage_pending": job.get("usage_pending", job["action"] == "retain"), "elapsed_ms": job.get("elapsed_ms")}


class Memory:
    def __init__(self, service, client=None):
        self.service, self.store, self.settings = service, service.store, service.settings
        self.backend = client or Hindsight(self.settings, service.http)
        self.wakeup = asyncio.Event()

    def enabled(self):
        if not self.settings.memory_enabled:
            raise HTTPException(503, "MEMORY_NOT_ENABLED")

    async def optional(self, user, kind, rid, conn=None):
        try:
            return await self.store.get(user, kind, rid, conn)
        except HTTPException as error:
            if error.status_code != 404:
                raise
            return None

    async def records(self, user, kind):
        cursor = None
        while True:
            page = await self.store.page(user, kind, cursor=cursor, limit=100)
            for item in page["items"]:
                yield item
            cursor = page["next_cursor"]
            if not cursor:
                break

    async def preferences(self, user, *, initialize=False):
        self.enabled()
        if initialize:
            if user.automation_grant_id:
                raise HTTPException(403, "INTERACTIVE_AUTHORIZATION_REQUIRED")
            await self.service.java(user, "runtime_context", {"profile": "agent", "context_refs": []})
        current = await self.optional(user, "memory-preferences", owner_id(user))
        if initialize and (not current or current["auth_version"] != user.auth_version):
            async with self.store.transaction(user, "memory-owner") as conn:
                current = await self.optional(user, "memory-preferences", owner_id(user), conn)
                if not current:
                    current = await self.store.create(user, "memory-preferences", {
                        "principal": identity(user), "auth_version": user.auth_version, "read_enabled": True,
                        "auto_enabled": True, "grant_status": "ACTIVE", "grant_version": 1, "generation": 1, "notice_seen": False,
                    }, conn, rid=owner_id(user))
                    await self.store.create(user, "memory-binding", {"bank_id": "jsd-" + digest(user.key)[:40]},
                                            conn, rid=owner_id(user))
                elif current["auth_version"] != user.auth_version:
                    current.update(principal=identity(user), auth_version=user.auth_version,
                                   grant_status="ACTIVE" if current["auto_enabled"] else "REVOKED",
                                   grant_version=current.get("grant_version", 1) + 1,
                                   generation=current["generation"] + 1)
                    current = await self.store.update(user, "memory-preferences", current, conn)
        if not current or current["auth_version"] != user.auth_version:
            raise HTTPException(403, "MEMORY_IDENTITY_CHANGED")
        return current

    async def bank(self, user):
        return (await self.store.get(user, "memory-binding", owner_id(user)))["bank_id"]

    async def check(self, user):
        prefs = await self.preferences(user)
        if user.automation_grant_id and (user.scope != "personal-memory" or user.automation_grant_id != prefs["id"]
                                        or prefs["grant_status"] != "ACTIVE" or not prefs["auto_enabled"]):
            raise HTTPException(403, "MEMORY_BACKGROUND_REVOKED")
        return prefs

    async def live(self, user):
        await self.service.java(user, "runtime_context", {"profile": "agent", "context_refs": []})
        return await self.check(user)

    async def session_settings(self, user, sid, conn=None):
        if not sid:
            return {"read_enabled": True, "participate": True}
        await require_session_available(self.store, user, sid, conn)
        return await self.optional(user, "memory-session", session_id(user, sid), conn) or {
            "session_id": sid, "read_enabled": True, "participate": True}

    async def set_preferences(self, user, body):
        if user.automation_grant_id:
            raise HTTPException(403, "INTERACTIVE_AUTHORIZATION_REQUIRED")
        await self.live(user)
        allowed = {"read_enabled", "auto_enabled", "notice_seen"}
        patch = body.get("patch", {})
        if not isinstance(patch, dict) or not patch or set(patch) - allowed or any(type(v) is not bool for v in patch.values()):
            raise HTTPException(422, "INVALID_MEMORY_SETTINGS")
        async with self.store.transaction(user, "memory-owner") as conn:
            prefs = await self.store.get(user, "memory-preferences", owner_id(user), conn)
            if body.get("revision") != prefs["revision"]:
                raise HTTPException(409, "MEMORY_REVISION_CHANGED")
            if "auto_enabled" in patch and patch["auto_enabled"] != prefs["auto_enabled"]:
                prefs["grant_version"] = prefs.get("grant_version", 1) + 1
            prefs.update(patch, generation=prefs["generation"] + 1)
            prefs["grant_status"] = "ACTIVE" if prefs["auto_enabled"] else "REVOKED"
            await self.store.update(user, "memory-preferences", prefs, conn)
        self.wakeup.set()
        return await self.settings_view(user)

    async def settings_view(self, user):
        prefs = await self.preferences(user)
        return {"enabled": True, **{k: prefs[k] for k in
                ("revision", "read_enabled", "auto_enabled", "notice_seen", "generation", "grant_status")}}

    async def set_session(self, user, body):
        if user.automation_grant_id:
            raise HTTPException(403, "INTERACTIVE_AUTHORIZATION_REQUIRED")
        sid = body.get("session_id")
        if not sid or not await self.service.session_conversation(user, sid):
            raise HTTPException(404, "SESSION_NOT_OWNED")
        patch = body.get("patch", {})
        if not isinstance(patch, dict) or not patch or set(patch) - {"read_enabled", "participate"} or any(type(v) is not bool for v in patch.values()):
            raise HTTPException(422, "INVALID_MEMORY_SETTINGS")
        async with self.store.transaction(user, "memory-owner") as conn:
            current = await self.session_settings(user, sid, conn)
            if "participate" in patch and patch["participate"] != current["participate"]:
                current["participation_version"] = current.get("participation_version", 1) + 1
            current.update(patch)
            if "id" in current:
                current = await self.store.update(user, "memory-session", current, conn)
            else:
                current = await self.store.create(user, "memory-session", current, conn,
                    rid=session_id(user, sid))
            prefs = await self.store.get(user, "memory-preferences", owner_id(user), conn)
            prefs["generation"] += 1
            await self.store.update(user, "memory-preferences", prefs, conn)
        return {"read_enabled": current["read_enabled"], "participate": current["participate"]}

    async def source_allowed(self, user, entry):
        source = entry["source"]
        if await self.optional(user, "memory-exclusion", exclusion_id(user, source)):
            return False
        try:
            if source.get("session_id"):
                await require_session_available(self.store, user, source["session_id"])
                conversation = await self.service.session_conversation(user, source["session_id"])
                if not conversation:
                    return False
            refs = source.get("resource_refs", [])
            if source.get("record_kind"):
                record = await self.store.get(user, source["record_kind"], source["record_id"])
                if record["revision"] != source["record_revision"]:
                    return False
            for offset in range(0, len(refs), 50):
                await self.service.java(user, "authorize_sources", {"sources": refs[offset:offset + 50]})
            return True
        except HTTPException as error:
            if error.status_code in {401, 403, 404, 409, 410}:
                return False
            raise

    async def eligible(self, user, *, category=None):
        items = []
        async for entry in self.records(user, "memory-entry"):
            if entry["status"] == "ACTIVE" and (not category or entry["category"] == category):
                if await self.source_allowed(user, entry):
                    items.append(entry)
                else:
                    await self.forget(user, entry, internal=True)
        return items

    async def validate(self, user, body):
        prefs = await self.live(user)
        session = await self.session_settings(user, body.get("session_id"))
        valid = prefs["read_enabled"] and session["read_enabled"]
        refs = body.get("entries", [])
        if not isinstance(refs, list) or len(refs) > 2000:
            raise HTTPException(422, "INVALID_MEMORY_REFERENCES")
        for ref in refs:
            if not isinstance(ref, dict):
                raise HTTPException(422, "INVALID_MEMORY_REFERENCES")
            self.identifier(ref.get("id"))
            entry = await self.optional(user, "memory-entry", ref.get("id", ""))
            if not entry or entry["status"] != "ACTIVE" or entry["content_revision"] != ref.get("content_revision"):
                valid = False
            elif not await self.source_allowed(user, entry):
                await self.forget(user, entry, internal=True)
                valid = False
        current = await self.preferences(user)
        valid = valid and current["read_enabled"] and current["generation"] == prefs["generation"]
        return {"valid": bool(valid), "generation": current["generation"]}

    async def reserve(self, user, operation_id, purpose, *, estimate=0):
        day = datetime.now(ZoneInfo("Asia/Shanghai")).date().isoformat()
        rid = str(uuid5(NAMESPACE_URL, "jsd:memory-budget:" + day + ":" + ":".join(user.key)))
        async with self.store.transaction(user, "memory-budget") as conn:
            current = await self.optional(user, "memory-budget", rid, conn)
            if not current:
                current = await self.store.create(user, "memory-budget", {"calls": 0, "tokens": 0, "reservations": {}, "day": day}, conn, rid=rid)
            if operation_id in current["reservations"]:
                return rid
            reserved = sum(current["reservations"].values())
            if current["calls"] >= self.settings.memory_daily_calls or current["tokens"] + reserved + estimate > self.settings.memory_daily_tokens:
                raise HTTPException(429, "MEMORY_BUDGET_EXCEEDED")
            current["calls"] += 1
            current["reservations"][operation_id] = estimate
            await self.store.update(user, "memory-budget", current, conn)
        return rid

    async def account(self, user, operation_id, budget_id, purpose, started, usage=None, *, elapsed_ms=None, error=None):
        async with self.store.transaction(user, "memory-budget") as conn:
            rid = str(uuid5(NAMESPACE_URL, "jsd:memory-usage:" + operation_id))
            prior = await self.optional(user, "memory-usage", rid, conn)
            if prior and prior.get("usage") is not None:
                return
            payload = {"operation_id": operation_id, "purpose": purpose, "elapsed_ms": elapsed_ms if elapsed_ms is not None else round((time.monotonic() - started) * 1000),
                       "usage": usage, "usage_pending": usage is None, "status": "FAILED" if error else "SUCCEEDED", "error": error}
            if prior:
                prior.update(payload)
                await self.store.update(user, "memory-usage", prior, conn)
            else:
                await self.store.create(user, "memory-usage", payload, conn, rid=rid)
            if usage is not None:
                budget = await self.store.get(user, "memory-budget", budget_id, conn)
                budget["tokens"] += int(usage.get("total_tokens", 0))
                budget["reservations"].pop(operation_id, None)
                await self.store.update(user, "memory-budget", budget, conn)

    async def release_budget(self, user, job):
        if not job.get("budget_id"):
            return
        async with self.store.transaction(user, "memory-budget") as conn:
            budget = await self.store.get(user, "memory-budget", job["budget_id"], conn)
            budget["reservations"].pop(job["id"], None)
            await self.store.update(user, "memory-budget", budget, conn)

    @staticmethod
    def source_view(entry):
        source = entry["source"]
        return {"kind": source["kind"], "label": "用户明确声明" if source["kind"] == "manual" else "原始会话",
                **{k: source[k] for k in ("session_id", "turn", "event_seq", "hash", "resource_refs", "receipt_id", "call_id", "record_id", "version_id") if k in source}}

    async def search(self, user, body):
        prefs = await self.live(user)
        session = await self.session_settings(user, body.get("session_id"))
        if not prefs["read_enabled"] or not session["read_enabled"]:
            return {"entries": [], "disabled": True, "generation": prefs["generation"]}
        mode = body.get("mode", "recall")
        if mode not in {"recall", "reflect", "history"} or not isinstance(body.get("query", ""), str):
            raise HTTPException(422, "INVALID_MEMORY_QUERY")
        category = "preference" if body.get("preferences_only") else None
        entries = await self.eligible(user, category=category)
        if mode == "history":
            # Only source locations already admitted by this control plane may be unfolded.
            selected = await self.search(user, {**body, "mode": "recall"})
            ids = {entry["id"] for entry in selected.get("entries", [])}
            sources = [entry for entry in entries if entry["id"] in ids and entry["source"].get("session_id")]
            if not sources:
                return {"entries": [], "memory_context": True}
            locators = []
            for entry in sources:
                doc = await self.backend.document(await self.bank(user), entry["document_id"])
                if doc and digest(doc.get("original_text", "")) == entry["source"]["hash"]:
                    locators.append({"entry_id": entry["id"], "expected_text": doc["original_text"], **self.source_view(entry)})
            value = await self.service.dsh_command(user, "memory-history", {"query": body.get("query", ""), "sources": locators})
            allowed = {e["id"]: e for e in sources if await self.source_allowed(user, e)}
            return {"entries": [{**row, "content_revision": allowed[row["id"]]["content_revision"],
                                "revision": allowed[row["id"]]["revision"]}
                                for row in value.get("entries", []) if row.get("id") in allowed], "memory_context": True}
        query = str(body.get("query", ""))[:1000].strip() or "用户的长期工作偏好、表达习惯和稳定约定"
        if not entries:
            return {"entries": [], "generation": prefs["generation"]}
        bank = await self.bank(user)
        operation_id = str(uuid4())
        budget_id = await self.reserve(user, operation_id, mode, estimate=8192 if mode == "reflect" else 0)
        started = time.monotonic()
        response, failure = None, None
        try:
            if category and mode == "recall":
                # Independent preference documents form the startup snapshot;
                # a cold embedding/reranker must not block a new conversation.
                facts = []
                for entry in sorted(entries, key=lambda e: (e["updated_at"], e["id"]), reverse=True)[:5]:
                    doc = await self.backend.document(bank, entry["document_id"])
                    if doc and digest(doc.get("original_text", "")) == entry["source"]["hash"]:
                        facts.append({"document_id": entry["document_id"], "type": "world", "text": doc["original_text"]})
                response = {"results": facts, "usage": {"total_tokens": 0, "input_tokens": 0, "output_tokens": 0}}
            else:
                response = await (self.backend.reflect(bank, query, [e["tag"] for e in entries]) if mode == "reflect"
                                  else self.backend.recall(bank, query, [e["tag"] for e in entries]))
        except Exception as error:
            failure = error.detail if isinstance(error, HTTPException) else "MEMORY_CONNECTION_UNAVAILABLE"
            raise
        finally:
            # Recall does no generative LLM work. Embedding usage is not exposed
            # by 0.8.6, so record it as unknown rather than inventing a count.
            await self.account(user, operation_id, budget_id, mode, started, (response or {}).get("usage"), error=failure)
        by_document = {e["document_id"]: e for e in entries}
        facts = response.get("results", [])
        if mode == "reflect":
            facts = response.get("based_on", {}).get("memories", []) if response.get("based_on") else []
            if response.get("based_on", {}).get("mental_models") or response.get("based_on", {}).get("directives"):
                return {"entries": [], "unverified": True}
            # Reflect facts have IDs but no document_id in 0.8.6. Resolve each fact under the same bank.
            resolved = []
            for fact in facts:
                if not fact.get("id") or fact.get("type") not in {"world", "experience"}:
                    return {"entries": [], "unverified": True}
                full = await self.backend.request("GET", bank, "/memories/" + str(fact["id"]), missing=True)
                if not full or full.get("document_id") not in by_document:
                    return {"entries": [], "unverified": True}
                resolved.append(full)
            facts = resolved
        result, used = [], 0
        for fact in facts:
            entry = by_document.get(fact.get("document_id"))
            if not entry or fact.get("type") not in {"world", "experience"} or not await self.source_allowed(user, entry):
                continue
            text = str(fact.get("text", ""))
            # Hindsight's max_tokens may return an oversized first fact; enforce the entire local projection.
            limit = 1600 if category else 3200
            if not text or used + len(text.encode("utf-8")) > limit or len(result) >= 5:
                continue
            used += len(text.encode("utf-8"))
            result.append({"id": entry["id"], "revision": entry["revision"], "content_revision": entry["content_revision"], "text": text,
                           "category": entry["category"], "source": self.source_view(entry)})
        current = await self.preferences(user)
        if current["generation"] != prefs["generation"] or not current["read_enabled"]:
            return {"entries": [], "generation": current["generation"]}
        value = {"entries": result, "generation": current["generation"], "memory_context": True}
        if mode == "reflect" and result and len(result) == len(facts):
            value["reflection"] = response.get("text", "")[:4800]
        value["operation_id"] = operation_id
        value["elapsed_ms"] = round((time.monotonic() - started) * 1000)
        value["usage"] = response.get("usage")
        value["usage_pending"] = response.get("usage") is None
        return value

    async def save(self, user, text, *, category="preference", source=None, automatic=False, entry=None, key=None):
        prefs = await self.live(user)
        if not isinstance(text, str) or not text or len(text) > 12000 or SECRET.search(text) or category not in {"preference", "decision", "experience"}:
            raise HTTPException(422, "INVALID_MEMORY_CONTENT")
        if automatic and not prefs["auto_enabled"]:
            return {"status": "CANCELLED"}
        source = source or {"kind": "manual", "hash": digest(text)}
        session = await self.session_settings(user, source.get("session_id"))
        if not session["participate"]:
            raise HTTPException(403, "SESSION_MEMORY_DISABLED")
        if not await self.source_allowed(user, {"source": source}):
            raise HTTPException(403, "MEMORY_SOURCE_EXCLUDED")
        entry_id = entry["id"] if entry else str(uuid5(NAMESPACE_URL, "jsd:memory-entry:" + ":".join(user.key) + ":" + (key or source_key(source))))
        operation_id = str(uuid5(NAMESPACE_URL, "jsd:memory-operation:" + entry_id + ":" + str((entry or {}).get("content_revision", 0) + 1)))
        async with self.store.transaction(user, "memory-owner") as conn:
            current_prefs = await self.store.get(user, "memory-preferences", owner_id(user), conn)
            if current_prefs["auth_version"] != user.auth_version:
                raise HTTPException(403, "MEMORY_IDENTITY_CHANGED")
            if source.get("session_id"):
                await require_session_available(self.store, user, source["session_id"], conn)
            existing = await self.optional(user, "memory-entry", entry_id, conn)
            if existing and not entry:
                if existing["source"]["hash"] != source["hash"]:
                    raise HTTPException(409, "MEMORY_IDEMPOTENCY_CHANGED")
                return public_receipt(await self.store.get(user, "memory-job", existing["operation_id"], conn))
            if entry and (not existing or existing["revision"] != entry["revision"] or existing["status"] == "FORGOTTEN"):
                raise HTTPException(409, "MEMORY_REVISION_CHANGED")
            if await self.optional(user, "memory-exclusion", exclusion_id(user, source), conn):
                raise HTTPException(403, "MEMORY_SOURCE_EXCLUDED")
            revision = (existing or {}).get("content_revision", 0) + 1
            document_id = entry_id + "-r" + str(revision)
            tag = "entry:" + document_id
            payload = {"category": category, "source": source, "content_revision": revision, "status": "PENDING",
                       "document_id": document_id, "tag": tag, "operation_id": operation_id, "auth_version": user.auth_version}
            if existing:
                await self.exclude(user, existing, conn)
                await self.delete_job(user, existing, conn)
                existing.update(payload)
                await self.store.update(user, "memory-entry", existing, conn)
            else:
                await self.store.create(user, "memory-entry", payload, conn, rid=entry_id)
            job = await self.store.create(user, "memory-job", {**payload, "entry_id": entry_id, "content": text,
                "action": "retain", "automatic": automatic, "principal": identity(user), "attempts": 0,
                "grant_version": prefs.get("grant_version", 1), "participation_version": session.get("participation_version", 1),
                "status": "PENDING", "retry_at": 0, "lease_until": 0, "usage_pending": True}, conn, rid=operation_id)
            prefs = await self.store.get(user, "memory-preferences", owner_id(user), conn)
            prefs["generation"] += 1
            await self.store.update(user, "memory-preferences", prefs, conn)
        self.wakeup.set()
        return public_receipt(job)

    async def exclude(self, user, entry, conn):
        rid = exclusion_id(user, entry["source"])
        if not await self.optional(user, "memory-exclusion", rid, conn):
            await self.store.create(user, "memory-exclusion", {"entry_id": entry["id"], "source": entry["source"]}, conn, rid=rid)
        # Scrub copied memory text from replayable enterprise tool caches. Keep
        # call/receipt identities so outcome reconciliation remains possible.
        for table, column in (("harness_calls", "result"), ("harness_receipts", "payload")):
            await conn.execute(
                f"UPDATE agent_runtime.{table} c SET {column}=%s "
                "FROM agent_runtime.harness_records r WHERE r.kind='run' AND r.id=c.run_id "
                "AND r.organization_id=%s AND r.actor_id=%s "
                f"AND (c.{column}->>'entry_id'=%s OR c.{column}->'entries' @> %s)",
                (Jsonb({"excluded": True, "entry_id": entry["id"], "summary": "关联记忆已停止使用"}),
                 *user.key, entry["id"], Jsonb([{"id": entry["id"]}])))
        await conn.execute(
            "UPDATE agent_runtime.harness_calls c SET arguments=arguments-'text' "
            "FROM agent_runtime.harness_records r WHERE r.kind='run' AND r.id=c.run_id "
            "AND r.organization_id=%s AND r.actor_id=%s AND c.tool_name='memory_manage' "
            "AND (c.arguments->>'entry_id'=%s OR c.result->>'entry_id'=%s)",
            (*user.key, entry["id"], entry["id"]))

    async def delete_job(self, user, entry, conn):
        rid = str(uuid5(NAMESPACE_URL, "jsd:memory-delete:" + entry["document_id"]))
        job = await self.optional(user, "memory-job", rid, conn)
        if not job:
            job = await self.store.create(user, "memory-job", {
                "action": "delete", "entry_id": entry["id"], "document_id": entry["document_id"],
                "retain_operation_id": entry["operation_id"], "principal": identity(user), "automatic": False,
                "auth_version": user.auth_version, "content_revision": entry["content_revision"],
                "status": "PENDING", "attempts": 0, "retry_at": 0, "lease_until": 0, "usage_pending": False,
            }, conn, rid=rid)
        return job

    async def forget(self, user, entry, *, internal=False):
        async with self.store.transaction(user, "memory-owner") as conn:
            current = await self.store.get(user, "memory-entry", entry["id"], conn)
            if not internal and current["revision"] != entry["revision"]:
                raise HTTPException(409, "MEMORY_REVISION_CHANGED")
            await self.exclude(user, current, conn)
            job = await self.delete_job(user, current, conn)
            if current["status"] != "FORGOTTEN":
                current["status"] = "FORGOTTEN"
                await self.store.update(user, "memory-entry", current, conn)
                prefs = await self.store.get(user, "memory-preferences", owner_id(user), conn)
                prefs["generation"] += 1
                await self.store.update(user, "memory-preferences", prefs, conn)
        self.wakeup.set()
        return {**public_receipt(job), "excluded": True, "summary": "已停止使用，正在清理" if job["status"] != "SUCCEEDED" else "已清理"}

    async def forget_session(self, user, sid):
        # The session deletion fence is committed before this scan. Even a
        # worker racing this scan can no longer activate a sourced memory.
        if not self.settings.memory_enabled:
            return
        async for entry in self.records(user, "memory-entry"):
            if entry["source"].get("session_id") == sid and entry["status"] != "FORGOTTEN":
                await self.forget(user, entry, internal=True)

    async def capture(self, user, body):
        run = await self.service.session_run(user, body)
        if run.get("automation") or run.get("status") != "SUCCEEDED":
            return {"operations": []}
        prefs = await self.live(user)
        if run.get("created_at", "") < prefs["created_at"]:
            return {"operations": []}
        if not prefs["auto_enabled"] or not (await self.session_settings(user, body["session_id"]))["participate"]:
            return {"operations": []}
        async with self.store.connection() as conn:
            explicit = await (await conn.execute(
                "SELECT 1 FROM agent_runtime.harness_calls WHERE run_id=%s AND tool_name='memory_manage' "
                "AND status='SUCCEEDED' AND arguments->>'action' IN ('remember','correct','forget') LIMIT 1",
                (run["id"],))).fetchone()
        if explicit:
            return {"operations": []}
        operations = []
        for event in body.get("events", []):
            if event.get("type") != "user/message" or type(event.get("seq")) is not int:
                continue
            message = event.get("data", {}).get("message", event.get("data", {}))
            if message.get("source", {}).get("kind") != "user":
                continue
            text = "".join(p.get("text", "") for p in message.get("content", []) if p.get("type") == "text")
            if not text or text not in run.get("request", ""):
                continue
            # A native user event may contain pasted material. Only direct user
            # statements qualify; quotations and document instructions do not.
            if EXTERNAL.search(text) or re.search(r"```|^\s*>|[“”\"「」]", text, re.M):
                continue
            for match in re.finditer(r"[^\n；;。]+[。；;]?", text):
                fragment = match.group().strip()
                if not DIRECT.search(fragment) or TEMPORARY.search(fragment) or SECRET.search(fragment) or re.search(r"忘记|忘掉|更正|forget|correct", fragment, re.I) or not (STABLE.search(fragment) or DECISION.search(fragment)):
                    continue
                category = "preference" if STABLE.search(fragment) else "decision"
                if re.search(r"(?:已|已经).{0,8}(?:完成|上传|保存|创建|提交|导出|删除)", fragment):
                    continue  # Work completion comes only from the ledger below.
                refs = self.service.authorization_sources(run.get("source_refs", [])) if category == "decision" else []
                source = {"kind": "session", "session_id": body["session_id"], "turn": body["turn"],
                          "event_seq": event["seq"], "offset": match.start(), "hash": digest(fragment), "resource_refs": refs}
                try:
                    operations.append(await self.save(user, fragment, category=category, source=source, automatic=True))
                except HTTPException as error:
                    if error.status_code not in {403, 422, 429}:
                        raise
        async with self.store.connection() as conn:
            receipts = await (await conn.execute(
                "SELECT r.receipt_id,r.call_id,r.payload,c.tool_name FROM agent_runtime.harness_receipts r "
                "JOIN agent_runtime.harness_calls c USING(run_id,call_id) WHERE r.run_id=%s "
                "AND r.organization_id=%s AND r.actor_id=%s AND c.status='SUCCEEDED' "
                "AND c.tool_name IN ('create_experiment_draft','report_save','artifact_save') ORDER BY r.created_at LIMIT 5",
                (run["id"], *user.key))).fetchall()
        for receipt in receipts:
            payload = receipt["payload"]
            if not payload.get("id"):
                continue
            name = receipt["tool_name"]
            if name == "create_experiment_draft" and (payload.get("status") != "DRAFT" or not payload.get("version_id")):
                continue
            label = {"create_experiment_draft": "创建实验草稿", "report_save": "保存正式报告", "artifact_save": "登记成果"}[name]
            proof = {k: payload[k] for k in ("id", "version_id", "status", "sha256") if k in payload}
            text = "真实工具回执已核实" + label + "：" + json.dumps(proof, ensure_ascii=False, sort_keys=True)
            source = {"kind": "receipt", "session_id": body["session_id"], "turn": body["turn"],
                      "receipt_id": str(receipt["receipt_id"]), "call_id": receipt["call_id"], "record_id": payload["id"],
                      "hash": digest(text), "resource_refs": self.service.authorization_sources(run.get("source_refs", []))}
            if payload.get("version_id"):
                source["version_id"] = payload["version_id"]
            if name != "create_experiment_draft":
                record_kind = "report" if name == "report_save" else "artifact"
                record = await self.optional(user, record_kind, payload["id"])
                if not record:
                    continue
                source.update(record_kind=record_kind, record_revision=record["revision"])
            operations.append(await self.save(user, text, category="experience", source=source, automatic=True))
        return {"operations": operations}

    async def manage(self, user, body, *, run=None, call_id=None):
        if user.automation_grant_id:
            raise HTTPException(403, "INTERACTIVE_AUTHORIZATION_REQUIRED")
        await self.preferences(user, initialize=True)
        action = body.get("action")
        if action == "status":
            self.identifier(body.get("operation_id"))
            return public_receipt(await self.store.get(user, "memory-job", str(body.get("operation_id", ""))))
        if run and (not INTENT.search(run.get("request", "")) or run.get("automation")):
            raise HTTPException(403, "EXPLICIT_MEMORY_INTENT_REQUIRED")
        if action == "remember":
            text = str(body.get("text", "")).strip()
            if run and (text not in run.get("request", "") or EXTERNAL.search(run["request"]) or re.search(r"```|^\s*>", run["request"], re.M)):
                raise HTTPException(403, "MEMORY_USER_STATEMENT_REQUIRED")
            source = {"kind": "manual", "hash": digest(text)}
            if run:
                source.update(kind="session", session_id=run["dsh_session_id"], turn=run["dsh_turn"],
                              run_id=run["id"], call_id=call_id)
            return await self.save(user, text, category=body.get("category", "preference"), source=source,
                                   key=(run["id"] + ":" + call_id) if run else body.get("idempotency_key"))
        self.identifier(body.get("entry_id"))
        entry = await self.store.get(user, "memory-entry", str(body.get("entry_id", "")))
        if body.get("revision") != entry["revision"]:
            raise HTTPException(409, "MEMORY_REVISION_CHANGED")
        if action == "forget":
            return await self.forget(user, entry)
        if action == "correct":
            text = str(body.get("text", "")).strip()
            if run and (text not in run.get("request", "") or EXTERNAL.search(run["request"]) or re.search(r"```|^\s*>", run["request"], re.M)):
                raise HTTPException(403, "MEMORY_USER_STATEMENT_REQUIRED")
            source = {"kind": "manual", "hash": digest(text)}
            if run:
                source.update(kind="session", session_id=run["dsh_session_id"], turn=run["dsh_turn"],
                              run_id=run["id"], call_id=call_id)
            return await self.save(user, text, category=entry["category"], entry=entry, source=source)
        raise HTTPException(422, "INVALID_MEMORY_ACTION")

    async def listing(self, user, body):
        await self.live(user)
        bank, items, cursor = await self.bank(user), [], body.get("cursor")
        limit = body.get("limit", 25)
        while True:
            page = await self.store.page(user, "memory-entry", cursor=cursor, limit=limit - len(items) if items else limit)
            for entry in page["items"]:
                if entry["status"] == "FORGOTTEN":
                    continue
                if not await self.source_allowed(user, entry):
                    await self.forget(user, entry, internal=True)
                    continue
                doc = await self.backend.document(bank, entry["document_id"]) if entry["status"] == "ACTIVE" else None
                items.append({"id": entry["id"], "revision": entry["revision"], "content_revision": entry["content_revision"],
                              "category": entry["category"], "status": entry["status"], "source": self.source_view(entry),
                              "operation_id": entry["operation_id"], "text": (doc or {}).get("original_text", ""),
                              "receipt": public_receipt(await self.store.get(user, "memory-job", entry["operation_id"])),
                              "updated_at": entry["updated_at"]})
            cursor = page["next_cursor"]
            if not cursor or len(items) >= limit:
                break
        return {"entries": items, "next_cursor": page["next_cursor"]}

    @staticmethod
    def identifier(value):
        try:
            return str(UUID(value))
        except (ValueError, TypeError, AttributeError):
            raise HTTPException(422, "INVALID_MEMORY_ID") from None

    async def handle(self, user, body):
        self.enabled()
        if not isinstance(body, dict) or ("text" in body and not isinstance(body["text"], str)):
            raise HTTPException(422, "INVALID_MEMORY_REQUEST")
        if any(k in body for k in ("bank", "bank_id", "owner", "actor_id", "organization_id", "principal")):
            raise HTTPException(422, "MEMORY_IDENTITY_IS_SERVER_OWNED")
        for key in ("entry_id", "operation_id"):
            if key in body:
                self.identifier(body[key])
        action = body.get("action")
        if action == "initialize":
            await self.preferences(user, initialize=True)
            return await self.settings_view(user)
        if action == "capture":
            return await self.capture(user, body)
        if action == "validate":
            return await self.validate(user, body)
        if action == "settings":
            return await self.settings_view(user)
        if action == "set-settings":
            return await self.set_preferences(user, body)
        if action == "set-session":
            return await self.set_session(user, body)
        if action == "session-settings":
            return await self.session_settings(user, body.get("session_id"))
        if action == "list":
            return await self.listing(user, body)
        if action == "source":
            entry = await self.store.get(user, "memory-entry", self.identifier(body.get("entry_id")))
            await self.live(user)
            if body.get("content_revision", entry["content_revision"]) != entry["content_revision"]:
                raise HTTPException(409, "MEMORY_REVISION_CHANGED")
            if entry["status"] != "ACTIVE" or not await self.source_allowed(user, entry):
                raise HTTPException(403, "MEMORY_SOURCE_EXCLUDED")
            return {"source": self.source_view(entry)}
        if action == "search":
            return await self.search(user, body)
        if action in {"remember", "correct", "forget", "status"}:
            return await self.manage(user, body)
        raise HTTPException(422, "INVALID_MEMORY_ACTION")

    async def background_user(self, job):
        user = Principal.model_validate(job["principal"])
        prefs = await self.preferences(user)
        if job.get("automatic") and (prefs["grant_status"] != "ACTIVE" or not prefs["auto_enabled"]
                                     or job.get("grant_version", 1) != prefs.get("grant_version", 1)):
            raise HTTPException(403, "MEMORY_BACKGROUND_REVOKED")
        if job.get("automatic") and job.get("source", {}).get("session_id"):
            session = await self.session_settings(user, job["source"]["session_id"])
            if not session["participate"] or session.get("participation_version", 1) != job.get("participation_version", 1):
                raise HTTPException(403, "SESSION_MEMORY_DISABLED")
        scoped = user.model_copy(update={"scope": "personal-memory", "automation_grant_id": owner_id(user)})
        proof = issue(self.settings.shared_secret.get_secret_value(), scoped, "automation-exchange", ttl=60)
        response = await self.service.http.post(self.settings.java_url + "/internal/v1/agent-identity/automation",
            headers={"Authorization": "Bearer " + self.settings.runtime_secret.get_secret_value()}, json={"proof": proof}, timeout=10)
        if response.status_code >= 400:
            raise HTTPException(response.status_code, "MEMORY_BACKGROUND_IDENTITY_UNAVAILABLE")
        # Fresh Java identity is checked for every remote write/poll; never store an interactive ticket.
        result = response.json()
        if result.get("auth_version") != user.auth_version:
            raise HTTPException(403, "MEMORY_IDENTITY_CHANGED")
        return scoped

    async def finish_job(self, user, job, *, release=True, **patch):
        async with self.store.transaction(user, "memory-owner") as conn:
            current = await self.store.get(user, "memory-job", job["id"], conn)
            if current.get("lease") != job.get("lease"):
                return
            entry = await self.store.get(user, "memory-entry", job["entry_id"], conn)
            if job["action"] == "retain" and patch.get("status") == "SUCCEEDED":
                prefs = await self.store.get(user, "memory-preferences", owner_id(user), conn)
                excluded = await self.optional(user, "memory-exclusion", exclusion_id(user, job["source"]), conn)
                deleted = await deletion_state(self.store, user, job["source"]["session_id"], conn) if job["source"].get("session_id") else None
                session = await self.optional(user, "memory-session", session_id(user, job["source"]["session_id"]), conn) if job["source"].get("session_id") else None
                if (excluded or deleted or entry["operation_id"] != job["id"] or entry["status"] == "FORGOTTEN"
                    or prefs["auth_version"] != job["auth_version"]
                    or (job.get("automatic") and (not prefs["auto_enabled"] or prefs.get("grant_version", 1) != job.get("grant_version", 1)
                        or (session and (not session["participate"] or session.get("participation_version", 1) != job.get("participation_version", 1))))) ):
                    patch.update(status="CANCELLED", usage_pending=False)
                    await self.delete_job(user, {**job, "id": job["entry_id"], "operation_id": job["id"]}, conn)
            current.update(patch, lease_until=0 if release else current["lease_until"])
            if current["status"] == "SUCCEEDED" and (patch.get("status") == "SUCCEEDED" or patch.get("usage") is not None):
                current.pop("error", None)
            if patch.get("status") in {"SUCCEEDED", "FAILED"} and current.get("submitted_at"):
                current.setdefault("elapsed_ms", round((time.time() - current["submitted_at"]) * 1000))
            if patch.get("status") in {"SUCCEEDED", "FAILED", "CANCELLED"}:
                current.pop("content", None)
            await self.store.update(user, "memory-job", current, conn)
            if job["action"] == "retain" and entry["operation_id"] == job["id"] and entry["status"] == "PENDING":
                if patch.get("status") == "SUCCEEDED":
                    entry["status"] = "ACTIVE"
                elif patch.get("status") in {"FAILED", "CANCELLED"}:
                    entry["status"] = patch["status"]
                if patch.get("status") in {"SUCCEEDED", "FAILED", "CANCELLED"}:
                    await self.store.update(user, "memory-entry", entry, conn)
                    prefs = await self.store.get(user, "memory-preferences", owner_id(user), conn)
                    prefs["generation"] += 1
                    await self.store.update(user, "memory-preferences", prefs, conn)

    async def meter_job(self, user, job, bank, started):
        rows, offset = [], 0
        while True:
            page = await self.backend.request("GET", bank, "/llm-requests", params={
                "document_id": job["document_id"], "limit": 100, "offset": offset})
            rows.extend(page.get("items", []))
            offset += len(page.get("items", []))
            if offset >= page.get("total", 0) or not page.get("items"):
                break
        if not rows or any(row.get("total_tokens") is None for row in rows):
            return None
        rows = list({row["id"]: row for row in rows}.values())
        usage = {key: sum(row.get(key) or 0 for row in rows) for key in
                 ("input_tokens", "output_tokens", "total_tokens", "cached_tokens")}
        elapsed_ms = job.get("elapsed_ms") or (round((time.time() - job["submitted_at"]) * 1000) if job.get("submitted_at") else None)
        await self.account(user, job["id"], job["budget_id"], "retain", started, usage, elapsed_ms=elapsed_ms)
        return usage

    async def process(self, job):
        user = Principal.model_validate(job["principal"])
        started = time.monotonic()
        try:
            bank = await self.bank(user)
            if job["status"] in {"SUCCEEDED", "FAILED"}:
                usage = await self.meter_job(user, job, bank, started)
                await self.finish_job(user, job, usage=usage, usage_pending=usage is None, retry_at=time.time() + 60)
                return
            if job["action"] == "delete":
                # Fence retries under the same owner lock used by job completion.
                # Wait for an already admitted transport to finish (or its lease
                # to expire) before asserting that the document is gone.
                async with self.store.transaction(user, "memory-owner") as conn:
                    original = await self.optional(user, "memory-job", job["retain_operation_id"], conn)
                    in_flight = original and original.get("lease_until", 0) > time.time()
                    if original and not in_flight and original["status"] in {"PENDING", "UNKNOWN"}:
                        original.update(status="CANCELLED", usage_pending=False)
                        original.pop("content", None)
                        original = await self.store.update(user, "memory-job", original, conn)
                if in_flight:
                    await self.finish_job(user, job, retry_at=time.time() + 3)
                    return
                operation = await self.backend.operation(bank, job["retain_operation_id"]) if original and original.get("submitted") else None
                if not operation and original and original.get("submitted_at", 0) + 120 > time.time():
                    await self.finish_job(user, job, retry_at=time.time() + 3)
                    return
                if operation and operation.get("status", "").lower() not in TERMINAL | {"failed", "cancelled", "canceled"}:
                    await self.finish_job(user, job, retry_at=time.time() + 3)
                    return
                if original and original.get("budget_id"):
                    if original.get("submitted"):
                        await self.meter_job(user, original, bank, started)
                    else:
                        await self.release_budget(user, original)
                await self.backend.delete(bank, job["document_id"])
                if await self.backend.document(bank, job["document_id"]) is not None:
                    raise HTTPException(503, "MEMORY_DELETE_PENDING")
                await self.finish_job(user, job, status="SUCCEEDED", usage_pending=False)
                return
            scoped = await self.background_user(job)
            entry = await self.store.get(user, "memory-entry", job["entry_id"])
            excluded = entry["status"] == "FORGOTTEN" or entry["operation_id"] != job["id"] or not await self.source_allowed(scoped, {"source": job["source"]})
            operation = await self.backend.operation(bank, job["id"]) if job.get("submitted") or excluded else None
            if excluded and not operation:
                await self.release_budget(user, job)
                await self.finish_job(user, job, status="CANCELLED", usage_pending=False)
                return
            if not operation:
                budget_id = job.get("budget_id") or await self.reserve(user, job["id"], "retain", estimate=20000)
                await self.finish_job(user, job, submitted=True, submitted_at=job.get("submitted_at") or time.time(), budget_id=budget_id, release=False)
                await self.backend.ensure(bank)
                await self.background_user(job)
                await self.backend.retain(bank, job)
                await self.finish_job(user, job, submitted=True, budget_id=budget_id, retry_at=time.time() + 3)
                return
            state = operation.get("status", "").lower()
            if state not in TERMINAL and state not in {"failed", "cancelled", "canceled"}:
                await self.finish_job(user, job, retry_at=time.time() + 3)
                return
            if excluded:
                await self.backend.delete(bank, job["document_id"])
                await self.finish_job(user, job, status="CANCELLED", usage_pending=False)
                return
            if state not in TERMINAL:
                await self.finish_job(user, job, status="FAILED", error="MEMORY_EXTRACTION_FAILED")
                return
            # Ensure the successful operation still maps to the document and the live source.
            document = await self.backend.document(bank, job["document_id"])
            if not document or not await self.source_allowed(scoped, {"source": job["source"]}):
                raise HTTPException(503, "MEMORY_DOCUMENT_PENDING")
            # A metering outage must never undo a confirmed successful retain.
            try:
                usage = await self.meter_job(user, job, bank, started)
            except Exception:
                usage = None
            await self.finish_job(user, job, status="SUCCEEDED", usage=usage, usage_pending=usage is None, retry_at=time.time() + 60)
        except asyncio.CancelledError:
            raise
        except Exception as error:
            code = error.detail if isinstance(error, HTTPException) else "MEMORY_CONNECTION_UNAVAILABLE"
            rejected = isinstance(error, HTTPException) and error.status_code in {401, 403, 404, 410}
            if job["status"] in {"SUCCEEDED", "FAILED"}:
                await self.finish_job(user, job, error=code, retry_at=time.time() + 60)
                return
            if rejected and job["action"] == "retain":
                async with self.store.transaction(user, "memory-owner") as conn:
                    await self.delete_job(user, {**job, "id": job["entry_id"], "operation_id": job["id"]}, conn)
                if not job.get("submitted"):
                    await self.release_budget(user, job)
            budget_wait = code == "MEMORY_BUDGET_EXCEEDED" and not job.get("submitted")
            retry_at = time.time() + min(300, 3 * 2 ** min(job.get("attempts", 0), 6))
            if budget_wait:
                retry_at = (datetime.now(ZoneInfo("Asia/Shanghai")) + timedelta(days=1)).replace(hour=0, minute=0, second=1, microsecond=0).timestamp()
            await self.finish_job(user, job, status="CANCELLED" if rejected else "PENDING" if budget_wait else "UNKNOWN", error=code,
                                  attempts=job.get("attempts", 0) + 1, retry_at=retry_at)
            log.info("Memory operation deferred id=%s status=%s", job["id"], "revoked" if rejected else "pending")

    async def claim(self):
        async with self.store.connection() as conn, conn.transaction():
            row = await (await conn.execute(
                "SELECT id,organization_id,actor_id,payload FROM agent_runtime.harness_records "
                "WHERE kind='memory-job' AND (payload->>'status' IN ('PENDING','UNKNOWN') "
                "OR (payload->>'status' IN ('SUCCEEDED','FAILED') AND payload->>'usage_pending'='true')) "
                "AND coalesce((payload->>'retry_at')::double precision,0)<=%s "
                "AND coalesce((payload->>'lease_until')::double precision,0)<%s "
                "ORDER BY updated_at,id FOR UPDATE SKIP LOCKED LIMIT 1", (time.time(), time.time()),
            )).fetchone()
            if not row:
                return None
            user = Principal.model_validate(row["payload"]["principal"])
            if user.key != (str(row["organization_id"]), str(row["actor_id"])):
                raise RuntimeError("Memory job ownership mismatch")
            current = await self.store.get(user, "memory-job", str(row["id"]), conn)
            current.update(lease=str(uuid4()), lease_until=time.time() + 120)
            return await self.store.update(user, "memory-job", current, conn)

    async def worker(self):
        while True:
            try:
                job = await self.claim()
                if job:
                    await self.process(job)
                    continue
                self.wakeup.clear()
                with contextlib.suppress(TimeoutError):
                    await asyncio.wait_for(self.wakeup.wait(), 3)
            except asyncio.CancelledError:
                raise
            except Exception:
                log.exception("Memory worker recovery pending")
                await asyncio.sleep(3)

    async def run(self):
        if not self.settings.memory_enabled:
            return
        async with asyncio.TaskGroup() as group:
            for _ in range(self.settings.memory_workers):
                group.create_task(self.worker())
