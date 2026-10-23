from pathlib import Path

from jsd_aird_agents.app.platform.packs import PackBinding, VerticalPack
from jsd_aird_agents.contracts import AnswerDocument, Claim, IntentPlan, SkillSelection, Step
from jsd_aird_agents.harness.tools import ToolSpec

from .gateway import QualityArgs


class QualityBusiness:
    async def planning_context(self, ctx, gateway):
        return {"dataset_scope": ctx.resource_id, "mode": "quality_mock_read_only"}

    async def resolve(self, ctx, plan, gateway):
        scope = ctx.resource_id or await gateway.default_scope(ctx)
        await gateway.require(ctx, "quality.view", scope)
        return scope, None


class QualityScriptedModel:
    async def generate(self, kind, payload, schema):
        if kind == "skills":
            result = SkillSelection(
                names=[s["name"] for s in payload["skills"] if s["name"] == "quality-anomaly-review"]
            )
        elif kind == "plan":
            result = IntentPlan(
                steps=[
                    Step(id="anomalies", kind="FACT_QUERY", tool="list_quality_anomalies"),
                    Step(id="review", kind="ANALYSIS", depends_on=["anomalies"]),
                ]
            )
        else:
            facts = [e for e in payload.get("evidence", []) if e["source_type"] == "QUALITY_RECORD"]
            result = AnswerDocument(
                claims=[Claim(text=e["content"], evidence_ids=[e["id"]]) for e in facts],
                recommendations=["建议人工核对复检与工艺记录。"],
            )
        return schema.model_validate(result), {"reported": False, "scripted": True}


def bind(gateway):
    root = Path(__file__).parent
    tools = [
        ToolSpec(
            "list_quality_anomalies",
            QualityArgs,
            "quality.view",
            pack_id="quality",
            resource_kind="QUALITY_DATASET",
        )
    ]
    prompts = {"plan": "当前质量示例仅支持查询异常和带引用的分析，不提供任何质量写操作。"}
    return PackBinding(
        VerticalPack.load(root / "manifest.yaml"),
        root,
        tools,
        QualityBusiness(),
        gateway,
        prompts,
        QualityScriptedModel(),
    )
