"""Durable enterprise bindings for native schedules; no timers or model execution here."""
import copy
from datetime import datetime, timedelta, timezone
from uuid import UUID, NAMESPACE_URL, uuid4, uuid5
from zoneinfo import ZoneInfo

from fastapi import HTTPException

from aird_agent.core.contracts import Principal, digest
from aird_agent.server.security import issue

KINDS = ("project-morning", "research-weekly", "experiment-review")
READ_TOOLS = frozenset({"tool_search", "business_search", "business_read", "business_links",
    "get_research_briefing_context", "knowledge_search", "document_read", "document_outline", "table_read",
    "source_context", "evidence_read", "evidence_context", "evidence_image_read", "evidence_quote", "query_structured_data"})
RESEARCH_TYPES = {"PROJECT", "STAGE", "TASK", "REQUIREMENT", "MATERIAL", "EXPERIMENT", "STRUCTURED_DATA"}


def window(kind, *, now=None, last_success=None):
    end = now or datetime.now(timezone.utc)
    local = end.astimezone(ZoneInfo("Asia/Shanghai"))
    start = (local - timedelta(days=local.weekday())).replace(hour=0, minute=0, second=0, microsecond=0) if kind == "research-weekly" else end - timedelta(days=7)
    if kind == "experiment-review" and last_success:
        start = datetime.fromisoformat(last_success)
    return {"start": None if kind == "project-morning" else start.astimezone(timezone.utc).isoformat(),
            "end": end.isoformat(), "time_zone": "Asia/Shanghai"}


def ref_key(ref):
    identity = [ref.get("type"), ref.get("id"), ref.get("version_id", ref.get("revision"))]
    if ref.get("version_hash"):
        identity.append(ref["version_hash"])
    return digest(identity)


def coverage(snapshot):
    missing = [] if snapshot.get("frozen") else [{"section": "activity-index", "required_tool": "get_research_briefing_context"}]
    for section, entries in snapshot.get("sections", {}).items():
        seen = set(snapshot.get("seen", {}).get(section, []))
        if len(seen) < len(entries):
            missing.append({"section": section, "unread_index_count": len(entries) - len(seen)})
        for item in entries:
            key = ref_key(item["ref"])
            read = snapshot.get("reads", {}).get(key, {})
            if not read.get("overview"):
                missing.append({"ref": item["ref"], "section": "overview"})
            if section == "EXPERIMENT":
                for part, count in read.get("counts", {}).items():
                    if count and not section_complete(read, part, count):
                        missing.append({"ref": item["ref"], "section": part})
    return {"complete": bool(snapshot.get("frozen")) and not snapshot.get("truncated") and not missing,
            "missing": missing, "counts": {key: len(items) for key, items in snapshot.get("sections", {}).items()}}


def section_complete(read, part, total):
    # Text limits can return overlapping, unequal page lengths. Completeness
    # depends on the union of actual server-returned row ranges, not on a
    # model repeating the next cursor verbatim. A gap must still block success.
    cursor = 0
    for start, end in sorted((int(start), total if end is None else end)
            for start, end in read.get("pages", {}).get(part, {}).items()):
        if start > cursor:
            break
        cursor = max(cursor, end)
    return cursor >= total


class Briefings:
    def __init__(self, service):
        self.service, self.store = service, service.store

    @staticmethod
    def owner_id(user):
        return str(uuid5(NAMESPACE_URL, "jsd:research-briefings:" + ":".join(user.key)))

    async def owner(self, user, conn=None):
        return await self.store.get(user, "briefing-owner", self.owner_id(user), conn)

    async def check(self, user):
        if not user.automation_grant_id:
            return
        owner = await self.owner(user)
        if user.scope != "research-briefings" or user.automation_grant_id != owner["id"] or owner["grant_status"] != "ACTIVE" or owner["auth_version"] != user.auth_version:
            raise HTTPException(403, "BACKGROUND_AUTHORIZATION_REVOKED")

    async def install(self, user):
        if user.automation_grant_id:
            raise HTTPException(403, "INTERACTIVE_AUTHORIZATION_REQUIRED")
        await self.service.java(user, "runtime_context", {"profile": "agent", "context_refs": []})
        async with self.store.transaction(user, "briefing-install") as conn:
            try:
                return await self.owner(user, conn)
            except HTTPException as error:
                if error.status_code != 404:
                    raise
            return await self.store.create(user, "briefing-owner", {
                "principal": user.model_dump(mode="json", exclude_none=True), "auth_version": user.auth_version,
                "grant_status": "ACTIVE", "grant_scope": "research-briefings", "installed_version": 1,
                "tasks": {kind: {"builtin_id": kind, "session_id": "briefing-" + str(uuid4()),
                    "native_id": None, "status": "INSTALLING"} for kind in KINDS},
            }, conn, rid=self.owner_id(user))

    async def reconcile(self, user, body):
        async with self.store.transaction(user, "briefing-install") as conn:
            owner = await self.owner(user, conn)
            for item in body.get("tasks", []):
                saved = owner["tasks"].get(item.get("builtin_id"))
                if not saved or saved["session_id"] != item.get("session_id"):
                    raise HTTPException(409, "BUILTIN_TASK_CONFLICT")
                if saved["native_id"] and saved["native_id"] != item.get("native_id"):
                    raise HTTPException(409, "NATIVE_TASK_CONFLICT")
                if item.get("status") not in {"active", "paused", "ended", "deleted", "target_changed"}:
                    raise HTTPException(422, "INVALID_TASK_STATUS")
                saved.update(native_id=item.get("native_id"), status=item["status"])
            return await self.store.update(user, "briefing-owner", owner, conn)

    async def begin_creation(self, user, body):
        async with self.store.transaction(user, "briefing-install") as conn:
            owner = await self.owner(user, conn)
            saved = owner["tasks"].get(body.get("builtin_id"))
            if not saved or saved["session_id"] != body.get("session_id") or saved["native_id"]:
                raise HTTPException(409, "BUILTIN_TASK_CONFLICT")
            if saved.get("creation_started"):
                return {"claimed": False}
            saved["creation_started"] = True
            await self.store.update(user, "briefing-owner", owner, conn)
            return {"claimed": True}

    async def revoke(self, user):
        if user.automation_grant_id:
            raise HTTPException(403, "INTERACTIVE_AUTHORIZATION_REQUIRED")
        async with self.store.transaction(user, "briefing-install") as conn:
            owner = await self.owner(user, conn)
            owner["grant_status"] = "REVOKED"
            await self.store.update(user, "briefing-owner", owner, conn)
        return {"revoked": True}

    async def owners(self, offset=0):
        if not isinstance(offset, int) or not 0 <= offset <= 100000:
            raise HTTPException(422, "INVALID_OWNER_OFFSET")
        async with self.store.pool.connection() as conn:
            rows = await (await conn.execute("SELECT id,payload FROM agent_runtime.harness_records WHERE kind='briefing-owner' "
                "AND payload->>'grant_status'='ACTIVE' ORDER BY id LIMIT 101 OFFSET %s", (offset,))).fetchall()
        return {"owners": [{"id": str(row["id"]), "principal": row["payload"]["principal"]} for row in rows[:100]],
                **({"next_offset": offset + 100} if len(rows) > 100 else {})}

    async def ticket(self, owner_id):
        UUID(owner_id)
        async with self.store.pool.connection() as conn:
            row = await (await conn.execute("SELECT payload FROM agent_runtime.harness_records WHERE kind='briefing-owner' AND id=%s", (owner_id,))).fetchone()
        if not row or row["payload"]["grant_status"] != "ACTIVE":
            raise HTTPException(403, "BACKGROUND_AUTHORIZATION_REVOKED")
        user = Principal.model_validate({**row["payload"]["principal"], "automation_grant_id": owner_id, "scope": "research-briefings"})
        proof = issue(self.service.settings.shared_secret.get_secret_value(), user, "automation-exchange", ttl=60)
        result = await self.service.http.post(self.service.settings.java_url + "/internal/v1/agent-identity/automation",
            headers={"Authorization": "Bearer " + self.service.settings.runtime_secret.get_secret_value()}, json={"proof": proof})
        if result.status_code != 200:
            raise HTTPException(result.status_code, "BACKGROUND_IDENTITY_UNAVAILABLE")
        return result.json()

    async def pending(self, user):
        owner = await self.owner(user)
        allowed = {task["session_id"] for task in owner["tasks"].values()}
        async with self.store.pool.connection() as conn:
            rows = await (await conn.execute("SELECT payload->>'dsh_session_id' AS session_id FROM agent_runtime.harness_records "
                "WHERE kind='run' AND organization_id=%s AND actor_id=%s AND payload->>'status'='RUNNING' "
                "AND payload->'automation' IS NOT NULL", user.key)).fetchall()
        return {"sessions": sorted({row["session_id"] for row in rows if row["session_id"] in allowed})}

    async def binding(self, user, body):
        info = body.get("automation")
        if not info:
            if user.automation_grant_id:
                raise HTTPException(403, "NATIVE_DELIVERY_REQUIRED")
            return None
        await self.check(user)
        owner = await self.owner(user)
        task = owner["tasks"].get(info.get("builtin_id"))
        if not task or task["native_id"] != info.get("task_id") or task["session_id"] != body["session_id"] or task["status"] not in {"active", "paused", "ended"}:
            raise HTTPException(403, "NATIVE_DELIVERY_NOT_BOUND")
        if not info.get("message_id") or not info.get("delivered_at"):
            raise HTTPException(403, "NATIVE_DELIVERY_REQUIRED")
        key = str(uuid5(NAMESPACE_URL, digest([owner["id"], info["task_id"], info["message_id"]])))
        async with self.store.transaction(user, "briefing-delivery") as conn:
            try:
                delivery = await self.store.get(user, "briefing-delivery", key, conn)
                if delivery["turn"] != body["turn"] or delivery["session_id"] != body["session_id"]:
                    raise HTTPException(409, "DELIVERY_ALREADY_BOUND")
                return delivery
            except HTTPException as error:
                if error.status_code != 404:
                    raise
            return await self.store.create(user, "briefing-delivery", {**info, "session_id": body["session_id"],
                "turn": body["turn"], "window": window(info["builtin_id"], last_success=task.get("last_success")),
                "delivery_status": "DELIVERED", "business_status": "RUNNING"}, conn, rid=key)

    async def historical_run(self, user, run):
        """Only a completed, server-bound period may use historical access checks."""
        info = run.get("automation")
        if not info or run.get("status") not in {"SUCCEEDED", "FAILED", "CANCELLED"}:
            return False
        owner = await self.owner(user)
        task = owner["tasks"].get(info.get("builtin_id"), {})
        return bool(task.get("native_id") and task["native_id"] == info.get("task_id")
            and task["session_id"] == run.get("dsh_session_id") == info.get("session_id")
            and info.get("turn") == run.get("dsh_turn"))

    async def context(self, context, args):
        run, user = context.run, context.principal
        automation = run.get("automation")
        kind = automation["builtin_id"] if automation else args["task_type"]
        if args["task_type"] != kind:
            raise HTTPException(409, "BRIEFING_KIND_CONFLICT")
        current = await self.store.get(user, "run", run["id"])
        snapshot = current.get("briefing_snapshot")
        projects = [ref["id"] for ref in args["project_refs"]] if "project_refs" in args else snapshot["project_ids"] if snapshot else []
        if snapshot and projects != snapshot["project_ids"]:
            raise HTTPException(409, "BRIEFING_SCOPE_FROZEN")
        if not snapshot:
            span = automation["window"] if automation else window(kind)
            sections = ["EXPERIMENT"] if kind == "experiment-review" else ["PROJECT", "STAGE", "TASK"] + (["EXPERIMENT"] if kind == "research-weekly" else [])
            snapshot = {"window": span, "project_ids": projects, "sections": {}, "seen": {}, "reads": {}, "frozen": True}
            for section in sections:
                items, offset, index_revision = [], 0, None
                while True:
                    result = await self.service.host_call(user, "get_research_briefing_context", {
                        "task_type": kind, "section": section, "window_start": span["start"], "window_end": span["end"],
                        "project_ids": projects, "offset": offset}, run["id"])
                    current_revision = result.get("index_revision")
                    if index_revision is not None and current_revision != index_revision:
                        raise HTTPException(409, "BRIEFING_INDEX_CHANGED")
                    index_revision = current_revision
                    items.extend(result.get("candidates", []))
                    if "next_offset" not in result:
                        break
                    following = result["next_offset"]
                    if following <= offset or len(items) >= 4000:
                        snapshot["truncated"] = True
                        break
                    offset = following
                snapshot["sections"][section] = items
            async with self.store.transaction(user, run["id"]) as conn:
                current = await self.store.get(user, "run", run["id"], conn)
                current.setdefault("briefing_snapshot", snapshot)
                current = await self.store.update(user, "run", current, conn)
                snapshot = current["briefing_snapshot"]
        section = args.get("section", next(iter(snapshot["sections"])))
        if section not in snapshot["sections"]:
            raise HTTPException(422, "BRIEFING_SECTION_UNAVAILABLE")
        offset, limit = args.get("offset", 0), args.get("limit", 20)
        entries = snapshot["sections"][section]
        selected = copy.deepcopy(entries[offset:offset+limit])
        async with self.store.transaction(user, run["id"]) as conn:
            current = await self.store.get(user, "run", run["id"], conn)
            saved = current["briefing_snapshot"]
            saved["seen"][section] = sorted(set(saved["seen"].get(section, [])) | set(range(offset, min(len(entries), offset+limit))))
            await self.store.update(user, "run", current, conn)
        counts = {key: len(items) for key, items in snapshot["sections"].items()}
        labels = {"PROJECT": "项目", "STAGE": "阶段", "TASK": "任务", "EXPERIMENT": "实验"}
        count_text = "、".join(f"{labels[key]} {count} 个" for key, count in counts.items())
        return {"task_type": kind, "window": snapshot["window"], "section": section,
            "available_sections": list(snapshot["sections"]), "candidates": selected,
            "index_summary": {"counts": counts, "total": sum(counts.values()),
                "text": f"本期活动索引：{count_text}，共 {sum(counts.values())} 个业务对象。",
                "complete": not snapshot.get("truncated", False)},
            "coverage": coverage(saved), **({"next_offset": offset+limit} if offset+limit < len(entries) else {}),
            "reading_notice": "索引仅表示活动记录。用business_read完整读取固定ref；实验优先section=record读完整版本，过大时再按分区分页；本周更新时间不代表已知前后差异。"}

    @staticmethod
    def read_request(run, args):
        """The frozen server index supplies pins, even if a model omits one."""
        requested = args.get("ref", {})
        for entries in run.get("briefing_snapshot", {}).get("sections", {}).values():
            for item in entries:
                expected = item["ref"]
                if (requested.get("type"), requested.get("id")) != (expected.get("type"), expected.get("id")):
                    continue
                version = requested.get("version_id", requested.get("revision"))
                if version and version != expected.get("version_id", expected.get("revision")):
                    continue  # An explicit earlier version is a separate historical read.
                if requested.get("version_hash") and requested["version_hash"] != expected.get("version_hash"):
                    raise HTTPException(409, "BRIEFING_VERSION_CHANGED")
                return {**args, "ref": {**requested, **expected}}
        return args

    @staticmethod
    def record_read(run, name, args, result):
        snapshot = run.get("briefing_snapshot")
        if not snapshot or name != "business_read" or result.get("error") or not result.get("candidates"):
            return
        reference = result["candidates"][0].get("ref") or args.get("ref", {})
        key = ref_key(reference)
        reading = snapshot.setdefault("reads", {}).setdefault(key, {})
        if reference.get("type") != "EXPERIMENT":
            reading["overview"] = True
            return
        info = result.get("reading", {})
        part = info.get("section", args.get("section", "overview"))
        if part == "record" and info.get("complete") is True and not args.get("sample_key") and not args.get("field_codes"):
            reading.update(overview=True, counts=info.get("section_counts", {}))
            reading["pages"] = {name: {"0": None} for name in reading["counts"]}
        elif part == "overview":
            reading.update(overview=True, counts=info.get("section_counts", {}))
        elif not args.get("sample_key") and not args.get("field_codes"):
            offset = args.get("offset", 0)
            reading.setdefault("pages", {}).setdefault(part, {})[str(offset)] = info.get("next_offset")
            pages, cursor, seen = reading["pages"][part], 0, set()
            while str(cursor) in pages and cursor not in seen:
                seen.add(cursor)
                following = pages[str(cursor)]
                if following is None:
                    reading.setdefault("complete", {})[part] = True
                    break
                cursor = following

    async def finish(self, user, run, conn):
        meta = run.get("automation")
        if not meta:
            return
        delivery = await self.store.get(user, "briefing-delivery", meta["id"], conn)
        delivery.update(business_status=run["status"], run_id=run["id"], error=run.get("error"))
        await self.store.update(user, "briefing-delivery", delivery, conn)
        if run["status"] != "SUCCEEDED" or meta["builtin_id"] != "experiment-review" or not coverage(run.get("briefing_snapshot", {}))["complete"]:
            return
        owner = await self.owner(user, conn)
        task = owner["tasks"][meta["builtin_id"]]
        # A late completion cannot move the cursor backwards.
        end = meta["window"]["end"]
        if not task.get("last_success") or end > task["last_success"]:
            task.update(last_success=end, last_success_run=run["id"])
            await self.store.update(user, "briefing-owner", owner, conn)
