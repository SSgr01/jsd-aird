from __future__ import annotations

import hashlib

from pydantic import ValidationError

from jsd_aird_agents.contracts import AnswerDocument, Evidence, IntentPlan
from jsd_aird_agents.errors import AgentError


def validate_plan(plan: IntentPlan, registry, limit, *, allowed_tools=None, pack_ids=None):
    if pack_ids and set(plan.resource_refs) - set(pack_ids):
        raise AgentError("PACK_TOOL_FORBIDDEN", "不能解析未选择模块的资源", 403)
    if len(plan.steps) > limit or (not plan.steps and not plan.needs_clarification):
        raise AgentError("PLAN_INVALID", "计划步骤为空或超出上限")
    if plan.needs_clarification and not plan.clarification_question:
        raise AgentError("PLAN_INVALID", "追问缺少问题")
    steps = {step.id: step for step in plan.steps}
    if len(steps) != len(plan.steps):
        raise AgentError("PLAN_INVALID", "重复步骤 ID")
    visited, visiting = set(), set()

    def visit(sid):
        if sid not in steps or sid in visiting:
            raise AgentError("PLAN_INVALID", "依赖不存在或存在循环")
        if sid in visited:
            return
        visiting.add(sid)
        for dep in steps[sid].depends_on:
            visit(dep)
        visiting.remove(sid)
        visited.add(sid)

    def refs(value):
        if isinstance(value, dict):
            if "$ref" in value:
                if len(value) != 1 or not isinstance(value["$ref"], str):
                    raise AgentError("PLAN_INVALID", "非法结果引用")
                yield value["$ref"].split(".")[0]
            else:
                for k, v in value.items():
                    if k in {
                        "actor_id",
                        "user_id",
                        "organization_id",
                        "org_id",
                        "approved",
                        "approver_id",
                        "permission",
                        "risk",
                    }:
                        raise AgentError("PLAN_INVALID", f"计划不能设置身份、权限或审批字段: {k}")
                    yield from refs(v)
        elif isinstance(value, list):
            for v in value:
                yield from refs(v)

    for step in plan.steps:
        visit(step.id)
        if pack_ids:
            owner = registry.require(step.tool).pack_id if step.tool else (step.pack_id or pack_ids[0])
            if owner == "platform":
                owner = step.pack_id or pack_ids[0]
            if owner not in pack_ids or step.pack_id and step.pack_id != owner:
                raise AgentError("PACK_TOOL_FORBIDDEN", "步骤模块与工具归属不一致", 403)
            step.pack_id = owner
        if step.tool and allowed_tools is not None and step.tool not in allowed_tools:
            raise AgentError("PACK_TOOL_FORBIDDEN", "计划调用了其他模块或工作流的工具", 403)
        if step.kind == "ANALYSIS":
            if step.tool is not None:
                raise AgentError("PLAN_INVALID", "分析节点不能指定任意工具")
        else:
            spec = registry.require(step.tool)
            if spec.mutation != (step.kind == "ACTION_PROPOSAL"):
                raise AgentError("PLAN_INVALID", "工具与步骤风险类型不一致")
            if not spec.mutation and step.kind != spec.kind:
                raise AgentError("PLAN_INVALID", "读取步骤类型与注册工具不一致")
            if spec.mutation and not step.depends_on:
                raise AgentError("PLAN_INVALID", "业务动作必须依赖读取或分析")
            unknown = set(step.arguments) - set(spec.schema.model_fields)
            if unknown:
                raise AgentError(
                    "PLAN_INVALID", f"工具 {step.tool} 包含未知参数: {','.join(sorted(unknown))}"
                )
            if "$ref" not in str(step.arguments):
                try:
                    spec.schema.model_validate(step.arguments)
                except ValidationError as exc:
                    locations = [".".join(str(p) for p in e["loc"]) + ":" + e["type"] for e in exc.errors()]
                    raise AgentError(
                        "PLAN_INVALID", f"工具 {step.tool} 参数错误: {','.join(locations)}"
                    ) from exc
        for ref in refs(step.arguments):
            ancestors = set()

            def collect(sid):
                for dep in steps[sid].depends_on:
                    if dep not in ancestors:
                        ancestors.add(dep)
                        collect(dep)

            collect(step.id)
            if ref not in ancestors:
                raise AgentError("PLAN_INVALID", "只能引用已声明依赖的步骤结果")


def resolve_arguments(value, results):
    if isinstance(value, dict):
        if "$ref" in value:
            parts = value["$ref"].split(".")
            try:
                result = results[parts[0]]
                for part in parts[1:]:
                    result = (
                        result[int(part)] if isinstance(result, list) and part.isdigit() else result[part]
                    )
                return result
            except (KeyError, IndexError, TypeError, ValueError) as exc:
                raise AgentError("RESULT_REFERENCE_INVALID", "步骤结果引用无效") from exc
        return {k: resolve_arguments(v, results) for k, v in value.items()}
    if isinstance(value, list):
        return [resolve_arguments(v, results) for v in value]
    return value


async def validate_citations(answer: AnswerDocument, evidence: dict, context, store, policy):
    errors = []
    for claim in answer.claims:
        for eid in claim.evidence_ids:
            item = evidence.get(eid)
            if not item:
                errors.append(f"UNKNOWN_EVIDENCE:{eid}")
                continue
            row = await store.db.one(
                "SELECT payload FROM agent_runtime.evidence WHERE id=%s AND run_id=%s", (eid, context.run_id)
            )
            try:
                stored = Evidence.model_validate(row["payload"]).model_dump(mode="json") if row else None
                supplied = Evidence.model_validate(item).model_dump(mode="json")
            except ValidationError:
                errors.append(f"EVIDENCE_MISMATCH:{eid}")
                continue
            if (
                not row
                or stored != supplied
                or item["run_id"] != context.run_id
                or item["organization_id"] != context.principal.organization_id
                or hashlib.sha256(item["content"].encode()).hexdigest() != item["content_sha256"]
            ):
                errors.append(f"EVIDENCE_MISMATCH:{eid}")
                continue
            if hasattr(policy, "validate_evidence"):
                await policy.validate_evidence(context, item)
            else:
                await policy.require(context, "project.view", item["project_id"])
    return errors


def render_answer(answer, evidence, receipts, rejected):
    text = "\n\n".join(
        claim.text + " " + " ".join(f"[{eid}]" for eid in dict.fromkeys(claim.evidence_ids))
        for claim in answer.claims
    )
    if answer.recommendations:
        text += "\n\n建议：\n" + "\n".join(f"- {x}" for x in answer.recommendations)
    for receipt in receipts:
        text += f"\n\n模拟操作已完成并验证：动作 {receipt['action_id']}，版本 {receipt['after_version']}。"
    if rejected:
        text += "\n\n部分模拟操作未获批准，未执行相应修改。"
    used = {eid for c in answer.claims for eid in c.evidence_ids}
    # No markdown links from model-generated locator text are treated as trusted source URLs.
    return {
        "answer": text.strip() or "当前没有足够证据支持事实结论。",
        "claims": [c.model_dump() for c in answer.claims],
        "citations": [evidence[eid] for eid in sorted(used)],
        "receipts": receipts,
        "warnings": ["部分操作未执行"] if rejected else [],
        "simulated": True,
    }
