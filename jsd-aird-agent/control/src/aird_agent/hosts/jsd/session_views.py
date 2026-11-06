"""Enterprise views over native sessions; no execution state or second message queue."""
import re

from fastapi import HTTPException

from aird_agent.server.contracts import ContextRef


class SessionViews:
    async def ensure_conversation(self, principal, sid, profile):
        # Native IDs are opaque (the web client creates session-<uuid>).
        # Enterprise conversation UUIDs remain separate from this JSONB mapping.
        if not isinstance(sid, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,200}", sid):
            raise HTTPException(422, "INVALID_NATIVE_SESSION_ID")
        conversation = await self.session_conversation(principal, sid)
        if conversation is None:
            topic = await self.java(principal, "runtime_context", {"profile": profile, "context_refs": []})
            async with self.store.transaction(principal, "session:" + sid) as conn:
                row = await (await conn.execute(
                    "SELECT id FROM agent_runtime.harness_records WHERE kind='conversation' AND organization_id=%s "
                    "AND actor_id=%s AND payload->>'dsh_session_id'=%s", (*principal.key, sid))).fetchone()
                if row:
                    conversation = await self.store.get(principal, "conversation", str(row["id"]), conn)
                else:
                    packs = topic["pack_ids"]
                    conversation = await self.store.create(principal, "conversation", {
                        "engine_version": "dsh-v1", "dsh_session_id": sid, "profile": profile,
                        "pack_ids": packs, "pack_versions": self.packs.pin(packs),
                        "private_source_ids": [],
                        "title": "新任务", "messages": [], "notes": [],
                    }, conn)
        if conversation["profile"] != profile:
            raise HTTPException(403, "SESSION_PROFILE_CHANGED")
        await self.authorize_conversation(principal, conversation)
        return conversation

    async def session_view(self, principal, action, body):
        if action == "context":
            query = str(body.get("query", "")).strip()
            if len(query) > 200:
                raise HTTPException(422, "CONTEXT_QUERY_TOO_LONG")
            # Discovery and preview do not create or mutate a business conversation.
            return await self.java(principal, "context_choices", {"query": query})
        if action == "resource":
            ref = ContextRef.model_validate(body["ref"]).model_dump(mode="json", exclude_none=True)
            return await self.java(principal, "context_resource", {"ref": ref, "offset": body.get("offset", 0)})
        raise HTTPException(404, "UNKNOWN_RESOURCE_VIEW")
