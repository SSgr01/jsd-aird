"""Read-only second pack: isolated fixture data, never a production quality implementation."""

from jsd_aird_agents.contracts import Contract, ToolResult
from jsd_aird_agents.errors import AgentError
from jsd_aird_agents.evidence import build_evidence

DATASET = "70000000-0000-0000-0000-000000000001"
OTHER_DATASET = "70000000-0000-0000-0000-000000000002"


class QualityArgs(Contract):
    # Scope is injected by the runtime. The model cannot change organization or dataset.
    pass


class MockQualityGateway:
    def __init__(self, store, identity):
        self.store, self.db, self.identity = store, store.db, identity

    async def require_entry(self, ctx, permission):
        await self.require(ctx, permission, ctx.resource_id or await self.default_scope(ctx))

    async def default_scope(self, ctx):
        principal = self.identity.lookup(ctx.principal.actor_id)
        values = [scope for pack, scope in principal.resource_scopes if pack == "quality"]
        if len(values) != 1:
            raise AgentError("SCOPE_REQUIRED", "请指定已授权的质量数据集范围", 403)
        return values[0]

    async def require(self, ctx, permission, scope):
        p = self.identity.lookup(ctx.principal.actor_id)
        if (
            p.organization_id != ctx.principal.organization_id
            or permission not in p.permissions
            or ("quality", scope) not in p.resource_scopes
        ):
            raise AgentError("PERMISSION_DENIED", "缺少质量权限或数据集授权范围", 403)
        row = await self.db.one(
            "SELECT id FROM mock_domain.quality_records WHERE organization_id=%s AND dataset_id=%s LIMIT 1",
            (p.organization_id, scope),
        )
        if not row:
            raise AgentError("PERMISSION_DENIED", "质量数据集不属于当前组织", 403)

    async def read(self, name, arguments, ctx):
        if name != "list_quality_anomalies":
            raise AgentError("UNKNOWN_TOOL", "质量模块未声明此工具", 403)
        await self.require(ctx, "quality.view", ctx.resource_id)
        rows = await self.db.all(
            "SELECT id,version,title,summary FROM mock_domain.quality_records WHERE organization_id=%s AND dataset_id=%s ORDER BY id",
            (ctx.principal.organization_id, ctx.resource_id),
        )
        return ToolResult(
            data=rows,
            evidence=[
                build_evidence(ctx, "QUALITY_RECORD", row["id"], row["version"], row["title"], row["summary"])
                for row in rows
            ],
        )


async def seed(db):
    for number, dataset in ((1, DATASET), (2, OTHER_DATASET)):
        await db.execute(
            """INSERT INTO mock_domain.quality_records(id,organization_id,dataset_id,title,summary)
            VALUES(%s,%s,%s,'模拟质量异常','模拟批次的涂层附着力复检未通过，需要核对工艺记录。') ON CONFLICT DO NOTHING""",
            (f"80000000-0000-0000-0000-{number:012d}", f"10000000-0000-0000-0000-{number:012d}", dataset),
        )
