"""Read-only homepage projections of existing native briefing runs."""
import re
from urllib.parse import urlencode
from uuid import UUID

from fastapi import HTTPException

from aird_agent.hosts.jsd.briefings import KINDS, coverage

TITLES = dict(zip(KINDS, ("项目晨报", "研发周报", "实验复盘")))


def plain(text, limit=180):
    text = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", text)
    text = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"[\[〔]E:[^\]〕]+[\]〕]", "", text)
    text = re.sub(r"<[^>]*>", "", text)
    text = re.sub(r"[*_`#]", "", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text if len(text) <= limit else text[:limit - 1].rstrip() + "…"


def business_destination(source):
    """Navigation derives from authorized source identities, never model URLs."""
    if source.get("kind") != "BUSINESS_RECORD":
        return None
    kind = source.get("resource_type")
    try:
        identity = str(UUID(source["resource_id"]))
        if kind == "EXPERIMENT":
            version = str(UUID(source["version_id"]))
            return f"/experiments/{identity}?version_id={version}"
        project = identity if kind == "PROJECT" else str(UUID(source["project_id"]))
        if kind in {"PROJECT", "STAGE", "TASK", "REQUIREMENT", "MATERIAL"}:
            return f"/projects/{project}"
    except (KeyError, ValueError, TypeError, AttributeError):
        pass
    return None


def excerpt(run):
    """Extract existing prose, preserving facts without another model call."""
    text = run.get("result", "")[:100_000]
    paragraphs, actions, in_actions = [], [], False
    for block in re.split(r"\n\s*\n", text):
        lines = block.strip().splitlines()
        for line in lines:
            if re.match(r"^#{1,6}\s", line):
                in_actions = bool(re.search(r"建议行动|优先行动|下一步|行动建议|今日优先|建议优先", line))
                continue
            if in_actions and re.match(r"^\s*(?:[-*+] |\d+[.、)）]\s*)", line):
                value = plain(re.sub(r"^\s*(?:[-*+] |\d+[.、)）]\s*)", "", line), 100)
                if value:
                    # Only evidence on this action establishes its business target.
                    sources = [run.get("evidence", {}).get(key, {}).get("source", {})
                               for key in re.findall(r"[\[〔]E:(e[0-9a-f]{24})[\]〕]", line)]
                    if not sources:
                        # Older periods may name the record without repeating its citation.
                        sources = [entry.get("source", {}) for entry in run.get("evidence", {}).values()
                                   if len(entry.get("source", {}).get("title", "")) >= 4
                                   and entry["source"]["title"] in line]
                    destinations = {business_destination(source) for source in sources} - {None}
                    target = next(iter(destinations)) if len(destinations) == 1 else None
                    actions.append({"text": value, **({"href": target} if target else {})})
        if lines and not any(re.match(r"^\s*(?:#{1,6}\s|\||```|---|[-*+] |\d+[.、)）]\s*)", line) for line in lines):
            value = plain(block)
            if value and not re.match(r"^(?:读取范围|本期范围|覆盖范围|统计口径|生成时间|时间范围)[：:]", value):
                paragraphs.append(value)
    preferred = next((p for p in paragraphs if re.match(r"^(?:本期重点|今日研判|研判|判断|结论|核心结论)[：:]", p)), None)
    summary = preferred or next(iter(paragraphs), "")
    summary = re.sub(r"^(?:本期重点|今日研判|研判|判断|结论|核心结论)[：:]\s*", "", summary)
    opening = re.match(r"^(.{8,44}?[。！？])\s*(.{12,})$", summary)
    headline = opening[1] if opening else plain(summary, 44)
    summary = opening[2] if opening else summary
    return {"headline": headline or "本期简报已生成", "summary": summary, "actions": actions[:3]}


async def cards(service, user):
    # Even an empty homepage must validate current AI/menu access.
    await service.java(user, "runtime_context", {"profile": "agent", "context_refs": []})
    try:
        owner = await service.briefings.owner(user)
    except HTTPException as error:
        if error.status_code != 404:
            raise
        owner = {"tasks": {}}
    items = []
    for kind in KINDS:
        task = owner.get("tasks", {}).get(kind, {})
        item = {"kind": kind, "title": TITLES[kind], "task_status": task.get("status", "NOT_INSTALLED"),
                "state": "NOT_GENERATED", "result": None, "latest": None}
        if task.get("session_id") and task.get("native_id"):
            await populate(service, user, task, item)
        items.append(item)
    return {"items": items}


async def populate(service, user, task, item):
    query = ("SELECT id FROM agent_runtime.harness_records WHERE kind='run' AND organization_id=%s "
             "AND actor_id=%s AND payload->>'dsh_session_id'=%s "
             "AND payload->'automation'->>'builtin_id'=%s AND payload->'automation'->>'task_id'=%s "
             "AND coalesce(payload->>'session_deleted','false') <> 'true' ")
    params = (*user.key, task["session_id"], item["kind"], task["native_id"])
    async with service.store.connection() as conn:
        latest = await (await conn.execute(query + "ORDER BY created_at DESC,id DESC LIMIT 1", params)).fetchone()
        success = await (await conn.execute(query + "AND payload->>'status'='SUCCEEDED' "
                                           "ORDER BY created_at DESC,id DESC LIMIT 1", params)).fetchone()
    if not latest:
        return
    current = await service.store.get(user, "run", str(latest["id"]))
    item["state"] = current.get("status", "UNKNOWN")
    item["latest"] = {"status": item["state"], "window": current.get("automation", {}).get("window"),
                      "generated_at": current["created_at"]}
    if not success:
        return
    run = current if success["id"] == latest["id"] else await service.store.get(user, "run", str(success["id"]))
    if not run.get("result") or not coverage(run.get("briefing_snapshot", {}))["complete"]:
        if item["state"] == "SUCCEEDED":
            item["state"] = "INCOMPLETE"
        return
    try:
        await service.authorized_topic(user, run, execution=False)
    except HTTPException as error:
        if error.status_code not in {403, 404, 409, 410}:
            raise
        item.update(state="UNAVAILABLE", latest=None)
        return
    meta = run["automation"]
    if (meta.get("session_id") != task["session_id"] or meta.get("turn") != run.get("dsh_turn")
            or type(run.get("dsh_turn")) is not int):
        item.update(state="UNAVAILABLE", latest=None)
        return
    # No report entity, body copy, native schedule or unread ledger is created.
    item["result"] = {"run_id": run["id"], "session_id": task["session_id"], "turn": run["dsh_turn"],
                      "generated_at": run.get("briefing_completed_at", run["created_at"]), "window": meta["window"], **excerpt(run),
                      "href": "/agent/?" + urlencode({"briefing": item["kind"], "run": run["id"]})}
    if item["state"] == "SUCCEEDED":
        item["state"] = "READY"
        if item["kind"] == "experiment-review" and not run["briefing_snapshot"]["sections"].get("EXPERIMENT"):
            item["state"] = "NO_ACTIVITY"
            item["result"].update(headline="本期无新增实验活动", summary="本期活动范围内没有新增实验，历史复盘可在会话中查看。", actions=[])


async def open_result(service, user, kind, run_id):
    if kind not in KINDS:
        raise HTTPException(422, "INVALID_BRIEFING_KIND")
    try:
        run_id = str(UUID(run_id))
    except (ValueError, TypeError, AttributeError):
        raise HTTPException(422, "INVALID_RUN_ID") from None
    owner = await service.briefings.owner(user)
    task = owner.get("tasks", {}).get(kind, {})
    run = await service.store.get(user, "run", run_id)
    meta = run.get("automation", {})
    if (run.get("session_deleted") or meta.get("builtin_id") != kind
            or meta.get("task_id") != task.get("native_id")
            or not task.get("session_id") or run.get("dsh_session_id") != task["session_id"]
            or meta.get("session_id") != task["session_id"]
            or type(run.get("dsh_turn")) is not int or meta.get("turn") != run["dsh_turn"]):
        raise HTTPException(404, "BRIEFING_NOT_AVAILABLE")
    await service.authorized_topic(user, run, execution=False)
    return {"session_id": task["session_id"], "turn": run["dsh_turn"], "title": TITLES[kind], "window": meta["window"]}
