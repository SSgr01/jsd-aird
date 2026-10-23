import asyncio
from dataclasses import dataclass

from pydantic import BaseModel, ValidationError

from jsd_aird_agents.contracts import (
    SkillResourceArgs,
    ToolResult,
    digest,
)
from jsd_aird_agents.errors import AgentError


@dataclass(frozen=True)
class ToolSpec:
    name: str
    schema: type[BaseModel]
    permission: str
    mutation: bool = False
    risk: str = "LOW"
    kind: str = "FACT_QUERY"
    pack_id: str = "project"
    version: str = "1"
    resource_kind: str = "PROJECT"


class ToolRegistry:
    def __init__(self):
        self.tools = {}

    def register(self, spec):
        if spec.name in self.tools or not spec.permission or spec.risk == "CRITICAL":
            raise ValueError("Duplicate, permissionless or CRITICAL tool")
        self.tools[spec.name] = spec

    def require(self, name):
        if name not in self.tools:
            raise AgentError("UNKNOWN_TOOL", f"工具未注册: {name}")
        return self.tools[name]

    def describe(self):
        return [
            {
                "name": t.name,
                "mutation": t.mutation,
                "risk": t.risk,
                "kind": "ACTION_PROPOSAL" if t.mutation else t.kind,
                "parameters": t.schema.model_json_schema(),
            }
            for t in self.tools.values()
        ]


def default_registry():
    from jsd_aird_agents.app.verticals.project.pack import tools

    r = ToolRegistry()
    for spec in tools():
        r.register(spec)
    r.register(
        ToolSpec("load_skill_resource", SkillResourceArgs, "ai.use", pack_id="platform", resource_kind="RUN")
    )
    return r


class Harness:
    def __init__(self, registry, domain, policy, actions, store, settings, *, skills=None, packs=None):
        self.registry, self.domain, self.policy, self.actions = registry, domain, policy, actions
        self.store, self.settings = store, settings
        self.skills = skills
        self.packs = packs

    def validate(self, name, arguments):
        spec = self.registry.require(name)
        try:
            args = spec.schema.model_validate(arguments).model_dump(mode="json", exclude_none=True)
        except ValidationError as exc:
            raise AgentError(
                "TOOL_ARGUMENTS_INVALID",
                "工具参数不符合 Schema",
                detail=[{"loc": list(e["loc"]), "type": e["type"]} for e in exc.errors()],
            ) from exc
        return spec, args

    async def describe(self, context):
        ceiling = await self.skills.tool_ceiling(context) if self.skills else None
        run = await self.store.run(context.run_id)
        allowed = self.allowed_tools(run)
        return [
            item
            for item in self.registry.describe()
            if item["name"] in allowed
            and self.registry.tools[item["name"]].permission in context.principal.permissions
            and (ceiling is None or item["name"] in ceiling)
        ]

    def allowed_tools(self, run):
        if not self.packs:
            return set(self.registry.tools)
        allowed = self.packs.tools_for(run["pack_ids"])
        if run.get("workflow_id"):
            workflows = [
                w
                for pid in run["pack_ids"]
                for w in self.packs.require(pid).manifest.workflows
                if w.id == run["workflow_id"]
            ]
            if len(workflows) != 1:
                raise AgentError("WORKFLOW_UNREGISTERED", "工作流未注册", 403)
            allowed &= set(workflows[0].tools) | {"load_skill_resource"}
        return allowed

    async def invoke(self, name, arguments, context, step_id, *, proposal=False):
        try:
            if self.skills:
                await self.skills.ensure_usable(context, name)
            result = await self._invoke(name, arguments, context, step_id, proposal=proposal)
        except AgentError as exc:
            if self.skills:
                await self.skills.audit_tool(context, step_id, name, "FAILED", exc.code)
            raise
        if self.skills:
            await self.skills.audit_tool(context, step_id, name, "SUCCEEDED")
        return result

    async def _invoke(self, name, arguments, context, step_id, *, proposal=False):
        spec, args = self.validate(name, arguments)
        if spec.mutation and not proposal:
            raise AgentError("MUTATION_REQUIRES_ACTION_PIPELINE", "写工具只能生成提议", 403)
        if not spec.mutation and proposal:
            raise AgentError("INVALID_ACTION_TOOL", "该工具不是动作工具")
        await self.store.check_active(context)
        run = await self.store.run(context.run_id)
        if name not in self.allowed_tools(run):
            raise AgentError("PACK_TOOL_FORBIDDEN", "工具不属于本轮已注册模块或工作流", 403)
        pid = spec.pack_id
        if pid == "platform":
            pid = (self.packs.skill_owner(args.get("name")) if self.packs else None) or run["pack_ids"][0]
        if pid not in run["pack_ids"]:
            raise AgentError("PACK_TOOL_FORBIDDEN", "不能加载其他模块资源", 403)
        if not run["resource_scopes"].get(pid):
            raise AgentError("SCOPE_REQUIRED", "业务读取或动作前必须先解析并绑定资源范围", 403)
        context = context.for_pack(pid, run["resource_scopes"].get(pid))
        permission = spec.permission
        if spec.pack_id == "platform" and self.packs:
            # Skill files do not require project access in non-project packs.
            permission = self.packs.require(pid).manifest.entry_permission
        await self.policy.require(context, permission, context.resource_id or context.project_id)
        if args.get("project_id") and args["project_id"] != context.project_id:
            raise AgentError("SCOPE_MISMATCH", "模型参数不能扩大项目范围", 403)
        request_hash = digest(
            [name, spec.version, args, context.pack_id, context.resource_id or context.project_id]
        )
        call_kind = f"tool:{name}"
        prior = await self.store.call(context.run_id, step_id, call_kind, request_hash)
        if prior and prior["status"] == "SUCCEEDED" and not spec.mutation:
            return ToolResult.model_validate(prior["result"])
        await self.store.budget(context.run_id, "tool_calls", self.settings.max_tool_calls)
        await self.store.save_call(context.run_id, step_id, call_kind, request_hash, "RUNNING")
        try:
            async with asyncio.timeout(self.settings.tool_timeout_seconds):
                if spec.mutation:
                    result = ToolResult(data=await self.actions.preview(context, step_id, name, args))
                elif name == "load_skill_resource" and self.skills:
                    result = await self.skills.load_resource(args, context)
                else:
                    result = await self.domain.read(name, args, context)
            await self.store.save_evidence(result.evidence)
            await self.store.save_call(
                context.run_id, step_id, call_kind, request_hash, "SUCCEEDED", result.model_dump(mode="json")
            )
            return result
        except (AgentError, TimeoutError) as exc:
            code = exc.code if isinstance(exc, AgentError) else "TOOL_TIMEOUT"
            await self.store.save_call(context.run_id, step_id, call_kind, request_hash, "FAILED", error=code)
            if isinstance(exc, TimeoutError):
                raise AgentError(code, "工具调用超时", 504, retryable=True) from exc
            raise
