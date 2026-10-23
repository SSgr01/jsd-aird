from jsd_aird_agents.contracts import canonical, digest
from jsd_aird_agents.errors import AgentError


class ArtifactStore:
    def __init__(self, store):
        self.store, self.db = store, store.db

    async def put_report(self, ctx, result):
        await self.store.check_active(ctx)
        content = canonical(result)
        if len(content.encode()) > 1048576:
            raise AgentError("ARTIFACT_TOO_LARGE", "报告产物超过当前存储上限", 422)
        sha = digest(result)
        artifact_id = "artifact_" + digest([ctx.run_id, sha])[:32]
        await self.db.execute(
            """INSERT INTO agent_runtime.artifacts
            (id,run_id,organization_id,actor_id,pack_id,media_type,content,sha256)
            VALUES(%s,%s,%s,%s,%s,'application/json',%s,%s) ON CONFLICT DO NOTHING""",
            (
                artifact_id,
                ctx.run_id,
                ctx.principal.organization_id,
                ctx.principal.actor_id,
                ctx.pack_id,
                content,
                sha,
            ),
        )
        return {"id": artifact_id, "sha256": sha, "media_type": "application/json", "simulated": True}

    async def get(self, artifact_id, principal):
        row = await self.db.one(
            "SELECT id,run_id,content,sha256,media_type FROM agent_runtime.artifacts WHERE id=%s AND organization_id=%s AND actor_id=%s",
            (artifact_id, principal.organization_id, principal.actor_id),
        )
        if not row:
            raise AgentError("NOT_FOUND", "产物不存在", 404)
        await self.store.run(str(row["run_id"]), principal)
        return {**row, "run_id": str(row["run_id"]), "simulated": True}
