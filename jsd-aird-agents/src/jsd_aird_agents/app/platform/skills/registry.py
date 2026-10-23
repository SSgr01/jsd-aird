from __future__ import annotations

import asyncio
import base64
import hashlib
import mimetypes
from pathlib import Path

from psycopg.types.json import Jsonb

from jsd_aird_agents.contracts import ToolResult, digest
from jsd_aird_agents.errors import AgentError
from jsd_aird_agents.persistence.store import wire
from jsd_aird_agents.skills.catalog import SkillLoader, resource_path


class SkillRegistry:
    def __init__(self, store, settings, tools, packs=None):
        self.store, self.db, self.settings, self.tools = store, store.db, settings, tools
        self.loader = SkillLoader(settings)
        self.packs = packs

    async def audit(self, kind, *, ctx=None, name=None, sha256=None, path=None, detail=None, conn=None):
        args = (
            ctx.run_id if ctx else None,
            ctx.principal.actor_id if ctx else None,
            kind,
            name,
            sha256,
            path,
            Jsonb(detail or {}),
        )
        sql = "INSERT INTO agent_runtime.skill_audit(run_id,actor_id,kind,name,sha256,path,detail) VALUES(%s,%s,%s,%s,%s,%s,%s)"
        if conn is not None:
            await conn.execute(sql, args)
        else:
            await self.db.execute(sql, args)
        if ctx:
            await self.store.event(
                ctx.run_id,
                "skill." + kind,
                {
                    "name": name,
                    "sha256": sha256,
                    "path": path,
                    **(detail or {}),
                },
                key="skill:" + digest([kind, name, sha256, path, detail]),
                conn=conn,
            )

    async def sync(self):
        packages, errors = await asyncio.to_thread(self.loader.scan)
        accepted = []
        for package in packages:
            source = Path(package.source).resolve()
            owner = self.packs.skill_owner(package.name) if self.packs else None
            if owner:
                binding = self.packs.require(owner)
                declaration = next(s for s in binding.manifest.skills if s.name == package.name)
                if source != (binding.root / declaration.path).parent.resolve():
                    errors.append({"name": package.name, "code": "SKILL_OWNER_MISMATCH"})
                    continue
            elif self.packs and any(
                source.is_relative_to(b.root.resolve()) for b in self.packs.bindings.values()
            ):
                errors.append({"name": package.name, "code": "SKILL_UNDECLARED"})
                continue
            accepted.append(package)
        packages = accepted
        async with self.db.pool.connection() as conn, conn.transaction():
            await conn.execute("SELECT pg_advisory_xact_lock(1847091002)")
            await conn.execute("UPDATE agent_runtime.skill_catalog SET available=false")
            for package in packages:
                await conn.execute(
                    """INSERT INTO agent_runtime.skill_versions
                    (name,sha256,metadata,instructions,manifest,source) VALUES(%s,%s,%s,%s,%s,%s)
                    ON CONFLICT(name,sha256) DO UPDATE SET source=excluded.source""",
                    (
                        package.name,
                        package.sha256,
                        Jsonb(package.metadata),
                        package.instructions,
                        Jsonb(package.manifest),
                        package.source,
                    ),
                )
                await conn.execute(
                    """INSERT INTO agent_runtime.skill_catalog(name,current_sha256) VALUES(%s,%s)
                    ON CONFLICT(name) DO UPDATE SET current_sha256=excluded.current_sha256,available=true,updated_at=now()""",
                    (package.name, package.sha256),
                )
                await self.audit("registered", name=package.name, sha256=package.sha256, conn=conn)
            for error in errors:
                await self.audit("scan_failed", detail=error, conn=conn)
        return {"registered": [{"name": p.name, "sha256": p.sha256} for p in packages], "errors": errors}

    async def catalog(self, *, enabled_only=False, ctx=None):
        rows = await self.db.all("""SELECT v.name,v.sha256,v.metadata,v.enabled,c.available,v.registered_at
            FROM agent_runtime.skill_catalog c JOIN agent_runtime.skill_versions v
            ON v.name=c.name AND v.sha256=c.current_sha256 ORDER BY v.name""")
        ids = (await self.store.run(ctx.run_id))["pack_ids"] if ctx else None
        return [
            {**self.metadata(row), "pack_id": self.owner(row["name"])}
            for row in rows
            if (not enabled_only or row["enabled"] and row["available"])
            and (ids is None or self.owner(row["name"]) in {*ids, "platform"})
        ]

    def owner(self, name):
        return (self.packs.skill_owner(name) if self.packs else None) or "platform"

    async def history(self, name):
        rows = await self.db.all(
            """SELECT v.name,v.sha256,v.metadata,v.enabled,v.registered_at,
            v.sha256=c.current_sha256 AS current,(v.sha256=c.current_sha256 AND c.available) AS available
            FROM agent_runtime.skill_versions v JOIN agent_runtime.skill_catalog c ON c.name=v.name
            WHERE v.name=%s ORDER BY v.registered_at""",
            (name,),
        )
        return [
            {**self.metadata(row), "current": row["current"], "registered_at": wire(row["registered_at"])}
            for row in rows
        ]

    @staticmethod
    def metadata(row):
        return {
            "name": row["name"],
            "sha256": row["sha256"],
            "description": row["metadata"]["description"],
            "version": row["metadata"].get("metadata", {}).get("version"),
            "allowed_tools": row["metadata"].get("allowed-tools"),
            "enabled": row["enabled"],
            "available": row.get("available", True),
            "untrusted": True,
        }

    async def set_enabled(self, name, sha256, enabled):
        async with self.db.pool.connection() as conn, conn.transaction():
            await conn.execute("SELECT pg_advisory_xact_lock(1847091002)")
            row = await (
                await conn.execute(
                    """SELECT v.*,c.current_sha256,c.available FROM agent_runtime.skill_versions v
                JOIN agent_runtime.skill_catalog c ON c.name=v.name WHERE v.name=%s AND v.sha256=%s FOR UPDATE OF v""",
                    (name, sha256),
                )
            ).fetchone()
            if not row or enabled and (not row["available"] or row["current_sha256"] != sha256):
                raise AgentError("SKILL_VERSION_UNAVAILABLE", "只能启用已注册且当前可用的精确版本", 409)
            await conn.execute(
                "UPDATE agent_runtime.skill_versions SET enabled=%s,enabled_at=CASE WHEN %s THEN now() ELSE NULL END WHERE name=%s AND sha256=%s",
                (enabled, enabled, name, sha256),
            )
            await self.audit("enabled" if enabled else "disabled", name=name, sha256=sha256, conn=conn)

    async def selection(self, ctx):
        row = await self.db.one(
            "SELECT selected FROM agent_runtime.skill_selections WHERE run_id=%s", (ctx.run_id,)
        )
        return None if row is None else row["selected"]

    async def pin(self, ctx, names, offered):
        if len(names) != len(set(names)) or len(names) > self.settings.max_selected_skills:
            raise AgentError("SKILL_SELECTION_INVALID", "技能选择重复或超过上限", 422)
        candidates = {p["name"]: p for p in offered}
        if any(name not in candidates for name in names):
            raise AgentError("SKILL_SELECTION_INVALID", "不能选择未提供或未启用的技能", 422)
        await self.store.check_active(ctx)
        run = await self.store.run(ctx.run_id)
        if any(self.owner(n) not in {*run["pack_ids"], "platform"} for n in names):
            raise AgentError("SKILL_PACK_FORBIDDEN", "不能选择其他模块的技能", 403)
        async with self.db.pool.connection() as conn, conn.transaction():
            await conn.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s,3))", (ctx.run_id,))
            await conn.execute("SELECT pg_advisory_xact_lock_shared(1847091002)")
            existing = await (
                await conn.execute(
                    "SELECT selected FROM agent_runtime.skill_selections WHERE run_id=%s", (ctx.run_id,)
                )
            ).fetchone()
            if existing:
                return existing["selected"]
            selected = []
            for name in sorted(names):
                sha256 = candidates[name]["sha256"]
                row = await (
                    await conn.execute(
                        """SELECT v.enabled,v.metadata,c.available,c.current_sha256
                    FROM agent_runtime.skill_versions v JOIN agent_runtime.skill_catalog c ON c.name=v.name
                    WHERE v.name=%s AND v.sha256=%s FOR SHARE OF v,c""",
                        (name, sha256),
                    )
                ).fetchone()
                if not row or not row["enabled"] or not row["available"] or row["current_sha256"] != sha256:
                    raise AgentError("SKILL_VERSION_UNAVAILABLE", "技能版本在选择期间发生变化", 409)
                requested = row["metadata"].get("allowed-tools")
                if requested is not None and set(requested.split()) - set(self.tools.tools):
                    raise AgentError("SKILL_TOOL_UNAVAILABLE", "技能声明了未注册或不支持的工具", 403)
                owner = self.owner(name)
                if requested and self.packs and owner != "platform":
                    if set(requested.split()) - self.packs.tools_for([owner]):
                        raise AgentError("SKILL_PACK_TOOL_FORBIDDEN", "模块技能不能声明其他模块的工具", 403)
                selected.append({"name": name, "sha256": sha256})
            await conn.execute(
                "INSERT INTO agent_runtime.skill_selections(run_id,selected) VALUES(%s,%s)",
                (ctx.run_id, Jsonb(selected)),
            )
            await self.audit("selected", ctx=ctx, detail={"selected": selected}, conn=conn)
            return selected

    async def versions(self, ctx):
        selected = await self.selection(ctx) or []
        result = []
        for item in selected:
            row = await self.db.one(
                "SELECT * FROM agent_runtime.skill_versions WHERE name=%s AND sha256=%s",
                (item["name"], item["sha256"]),
            )
            if not row or not row["enabled"]:
                raise AgentError("SKILL_DISABLED", "本轮绑定的技能版本已停用", 403)
            result.append(row)
        return result

    async def tool_ceiling(self, ctx):
        rows = await self.versions(ctx)
        if rows:
            unrestricted = any("allowed-tools" not in row["metadata"] for row in rows)
            ceiling = {"load_skill_resource"}
            for row in rows:
                ceiling.update(row["metadata"].get("allowed-tools", "").split())
            if not unrestricted:
                return ceiling
        return None

    async def ensure_usable(self, ctx, tool=None):
        ceiling = await self.tool_ceiling(ctx)
        if tool and ceiling is not None and tool not in ceiling:
            raise AgentError("SKILL_TOOL_FORBIDDEN", "工具不在本轮技能的工具上限内", 403)

    async def instructions(self, ctx):
        result = []
        for row in await self.versions(ctx):
            result.append(
                {
                    **self.metadata(row),
                    "instructions": row["instructions"],
                    "resources": [
                        p
                        for p in sorted(row["manifest"])
                        if p.split("/", 1)[0] in {"references", "assets", "scripts"}
                    ],
                    "scripts_executable": False,
                }
            )
            await self.audit("loaded", ctx=ctx, name=row["name"], sha256=row["sha256"])
        return result

    async def load_resource(self, arguments, ctx):
        name, path = arguments["name"], arguments["path"]
        try:
            resource_path(path)
            await self.store.check_active(ctx)
            rows = await self.versions(ctx)
            version = next((r for r in rows if r["name"] == name), None)
            if version is None:
                raise AgentError("SKILL_NOT_SELECTED", "只能加载本轮已绑定技能的资源", 403)
            expected = version["manifest"].get(path)
            if expected is None:
                raise AgentError("SKILL_RESOURCE_NOT_FOUND", "资源不属于已绑定技能版本", 404)
            saved = await self.db.one(
                "SELECT content,media_type FROM agent_runtime.skill_loads WHERE run_id=%s AND name=%s AND sha256=%s AND path=%s",
                (ctx.run_id, name, version["sha256"], path),
            )
            if saved:
                content, media_type = bytes(saved["content"]), saved["media_type"]
            else:
                content = await asyncio.to_thread(self.loader.resource, version["source"], path, expected)
                media_type = mimetypes.guess_type(path)[0] or "application/octet-stream"
                async with self.db.pool.connection() as conn, conn.transaction():
                    await conn.execute(
                        """INSERT INTO agent_runtime.skill_loads(run_id,name,sha256,path,content,media_type)
                        VALUES(%s,%s,%s,%s,%s,%s) ON CONFLICT DO NOTHING""",
                        (ctx.run_id, name, version["sha256"], path, content, media_type),
                    )
                    await self.audit(
                        "resource_loaded",
                        ctx=ctx,
                        name=name,
                        sha256=version["sha256"],
                        path=path,
                        detail={"content_sha256": expected["sha256"]},
                        conn=conn,
                    )
            if len(content) > self.settings.skill_resource_bytes:
                raise AgentError("SKILL_SIZE_EXCEEDED", "技能资源超过配置上限", 422)
            if hashlib.sha256(content).hexdigest() != expected["sha256"]:
                raise AgentError("SKILL_CONTENT_CHANGED", "已缓存技能资源摘要不匹配", 409)
            try:
                data = {"text": content.decode("utf-8")}
            except UnicodeError:
                data = {"base64": base64.b64encode(content).decode("ascii")}
            return ToolResult(
                data={
                    "name": name,
                    "version_sha256": version["sha256"],
                    "path": path,
                    "content_sha256": expected["sha256"],
                    "media_type": media_type,
                    "untrusted": True,
                    "executable": False,
                    **data,
                }
            )
        except (AgentError, OSError) as exc:
            error = (
                exc
                if isinstance(exc, AgentError)
                else AgentError("SKILL_RESOURCE_UNAVAILABLE", "技能资源不可读取", 409)
            )
            await self.audit("load_failed", ctx=ctx, name=name, path=path, detail={"code": error.code})
            raise error

    async def audit_tool(self, ctx, step_id, tool, status, error=None):
        selected = await self.selection(ctx) or []
        if selected:
            await self.audit(
                "tool_call",
                ctx=ctx,
                detail={
                    "step_id": step_id,
                    "tool": tool,
                    "status": status,
                    "error": error,
                    "selected": selected,
                },
            )

    async def snapshot(self, run_id):
        selection = await self.db.one(
            "SELECT selected FROM agent_runtime.skill_selections WHERE run_id=%s", (run_id,)
        )
        resources = await self.db.all(
            "SELECT name,sha256,path,loaded_at FROM agent_runtime.skill_loads WHERE run_id=%s ORDER BY name,path",
            (run_id,),
        )
        audit = await self.db.all(
            "SELECT kind,name,sha256,path,detail,created_at FROM agent_runtime.skill_audit WHERE run_id=%s ORDER BY id",
            (run_id,),
        )
        return {
            "selected": selection["selected"] if selection else [],
            "loaded_resources": wire(resources),
            "audit": wire(audit),
            "scripts_executable": False,
            "simulated": True,
        }
