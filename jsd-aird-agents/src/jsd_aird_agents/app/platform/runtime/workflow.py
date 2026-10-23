from __future__ import annotations

from typing import Annotated, TypedDict

from langchain_core.messages import AIMessage, AnyMessage, HumanMessage
from langgraph.graph import END, START, StateGraph
from langgraph.graph.message import add_messages
from langgraph.runtime import Runtime
from langgraph.types import Overwrite, Send, interrupt
from psycopg.types.json import Jsonb

from jsd_aird_agents.app.platform.artifacts.store import ArtifactStore
from jsd_aird_agents.app.platform.context import recent_context
from jsd_aird_agents.contracts import (
    LEGACY_GRAPH_VERSION,
    AnswerDocument,
    IntentPlan,
    RunContext,
    SkillSelection,
)
from jsd_aird_agents.errors import AgentError
from jsd_aird_agents.graph.validation import (
    render_answer,
    resolve_arguments,
    validate_citations,
    validate_plan,
)


def merge_by_id(left, right):
    return {**(left or {}), **(right or {})}


class AssistantState(TypedDict, total=False):
    messages: Annotated[list[AnyMessage], add_messages]
    conversation_id: str
    run_id: str
    current_request: str
    project_id: str | None
    intent_plan: dict
    step_results: Annotated[dict[str, dict], merge_by_id]
    evidence: Annotated[dict[str, dict], merge_by_id]
    pending_action_id: str | None
    clarification: dict | None
    ready_steps: list[str]
    final_result: dict | None
    run_status: str
    _step_id: str
    active_skills: list[dict]
    resource_scopes: dict[str, str]
    pack_ids: list[str]


class Workflow:
    def __init__(self, store, harness, model, settings, *, after_action=None, skills=None, packs=None):
        self.store, self.harness, self.model, self.settings = store, harness, model, settings
        self.domain, self.actions = harness.domain, harness.actions
        self.after_action = after_action  # Injectable fault probe used only by recovery tests.
        self.skills = skills
        self.packs = packs

    def compile(self, checkpointer):
        graph = StateGraph(AssistantState, context_schema=RunContext)
        for name in (
            "begin_run",
            "select_skills",
            "plan",
            "resolve_entities",
            "ask_clarification",
            "schedule",
            "execute_step",
            "after_step",
            "wait_approval",
            "execute_action",
            "synthesize",
        ):
            graph.add_node(name, getattr(self, name))
        graph.add_edge(START, "begin_run")
        graph.add_edge("begin_run", "select_skills")
        graph.add_edge("select_skills", "plan")
        graph.add_conditional_edges(
            "plan", lambda s: "ask_clarification" if s.get("clarification") else "resolve_entities"
        )
        graph.add_conditional_edges(
            "resolve_entities", lambda s: "ask_clarification" if s.get("clarification") else "schedule"
        )
        graph.add_edge("ask_clarification", "plan")
        graph.add_conditional_edges("schedule", self.dispatch)
        graph.add_edge("execute_step", "after_step")
        graph.add_conditional_edges(
            "after_step", lambda s: "wait_approval" if s.get("pending_action_id") else "schedule"
        )
        graph.add_conditional_edges(
            "wait_approval", lambda s: "execute_action" if s.get("pending_action_id") else "schedule"
        )
        graph.add_edge("execute_action", "schedule")
        graph.add_edge("synthesize", END)
        return graph.compile(checkpointer=checkpointer)

    async def begin_run(self, state, runtime: Runtime[RunContext]):
        ctx = runtime.context
        run = await self.store.run(ctx.run_id)
        return {
            "messages": [
                HumanMessage(
                    content=run["request"],
                    id=f"{ctx.run_id}:user",
                    additional_kwargs={"pack_ids": run["pack_ids"]},
                )
            ],
            "run_id": ctx.run_id,
            "conversation_id": ctx.conversation_id,
            "current_request": run["request"],
            "project_id": run["project_id"],
            "pack_ids": run["pack_ids"],
            "resource_scopes": run["resource_scopes"],
            "intent_plan": {},
            "step_results": Overwrite({}),
            "evidence": Overwrite({}),
            "pending_action_id": None,
            "clarification": None,
            "ready_steps": [],
            "final_result": None,
            "run_status": "RUNNING",
            "active_skills": [],
        }

    async def select_skills(self, state, runtime: Runtime[RunContext]):
        ctx = runtime.context
        if not self.skills or (await self.store.run(ctx.run_id))["graph_version"] == LEGACY_GRAPH_VERSION:
            return {"active_skills": []}
        selected = await self.skills.selection(ctx)
        if selected is None:
            offered = await self.skills.catalog(enabled_only=True, ctx=ctx)
            names = []
            if offered:
                payload = {
                    "request": state["current_request"],
                    "skills": offered,
                    "max_selected": self.settings.max_selected_skills,
                }
                choice = await self.model.generate(ctx, "skill-selector", "skills", payload, SkillSelection)
                names = choice.names
            try:
                await self.skills.pin(ctx, names, offered)
            except AgentError as exc:
                await self.skills.audit("selection_failed", ctx=ctx, detail={"code": exc.code})
                raise
        return {"active_skills": await self.skills.instructions(ctx)}

    async def plan(self, state, runtime: Runtime[RunContext]):
        ctx = runtime.context
        run = await self.store.run(ctx.run_id)
        business_context = {}
        for pid in run["pack_ids"]:
            binding = self.packs.require(pid)
            business_context[pid] = await binding.business.planning_context(
                ctx.for_pack(
                    pid, (state.get("resource_scopes") or {"project": state.get("project_id")}).get(pid)
                ),
                binding.gateway,
            )
        payload = {
            "request": state["current_request"],
            "selected_project_id": state.get("project_id"),
            "business_context": business_context,
            **business_context.get("project", {}),
            "tools": await self.harness.describe(ctx),
            "skill_guidance": state.get("active_skills", []),
            "recent_messages": recent_context(state.get("messages", []), self.settings.context_tokens),
        }
        plan = None
        for repair in range(2):
            try:
                plan = await self.model.generate(ctx, "planner", "plan", payload, IntentPlan)
                validate_plan(
                    plan,
                    self.harness.registry,
                    self.settings.max_steps,
                    allowed_tools=self.harness.allowed_tools(run),
                    pack_ids=run["pack_ids"],
                )
                break
            except AgentError as exc:
                if repair or exc.code not in {"MODEL_OUTPUT_INVALID", "PLAN_INVALID", "UNKNOWN_TOOL"}:
                    raise
                payload["validation_error"] = exc.code + ":" + exc.message
                if plan:
                    payload["rejected_plan"] = plan.model_dump(mode="json")
        await self.store.event(ctx.run_id, "run.planned", {"plan": plan.model_dump(mode="json")})
        return {
            "intent_plan": plan.model_dump(mode="json"),
            "clarification": (
                {"question": plan.clarification_question} if plan.needs_clarification else None
            ),
        }

    async def resolve_entities(self, state, runtime: Runtime[RunContext]):
        ctx, plan = runtime.context, IntentPlan.model_validate(state["intent_plan"])
        scopes = dict(state.get("resource_scopes", {}))
        for pid in state.get("pack_ids", ["project"]):
            binding = self.packs.require(pid)
            scope, clarification = await binding.business.resolve(
                ctx.for_pack(pid, scopes.get(pid)), plan, binding.gateway
            )
            if scope:
                scopes[pid] = scope
            if clarification:
                return {
                    "resource_scopes": scopes,
                    "project_id": scopes.get("project"),
                    "clarification": clarification,
                }
        for step in plan.steps:
            if step.tool and "$ref" not in str(step.arguments):
                self.harness.validate(step.tool, step.arguments)
        await self.store.db.execute(
            "UPDATE agent_runtime.runs SET project_id=%s,resource_scopes=%s,plan=%s WHERE id=%s",
            (scopes.get("project"), Jsonb(scopes), Jsonb(plan.model_dump(mode="json")), ctx.run_id),
        )
        return {
            "project_id": scopes.get("project"),
            "resource_scopes": scopes,
            "intent_plan": plan.model_dump(mode="json"),
            "clarification": None,
        }

    async def ask_clarification(self, state, runtime: Runtime[RunContext]):
        reply = interrupt({"type": "CLARIFICATION_REQUIRED", **state["clarification"], "simulated": True})
        if (
            not isinstance(reply, dict)
            or not isinstance(reply.get("answer"), str)
            or not reply["answer"].strip()
        ):
            raise AgentError("INVALID_RESUME", "澄清恢复需要有效答案")
        answer = reply["answer"]
        return {
            "current_request": state["current_request"] + "\n用户补充：" + answer,
            "messages": [
                HumanMessage(
                    content=answer, additional_kwargs={"pack_ids": state.get("pack_ids", ["project"])}
                )
            ],
            "clarification": None,
        }

    async def schedule(self, state, runtime: Runtime[RunContext]):
        await self.store.check_active(runtime.context)
        steps = IntentPlan.model_validate(state["intent_plan"]).steps
        results, skipped = dict(state.get("step_results", {})), {}
        changed = True
        while changed:
            changed = False
            for step in steps:
                if step.id not in results and any(
                    results.get(d, {}).get("status") == "SKIPPED" for d in step.depends_on
                ):
                    results[step.id] = skipped[step.id] = {"status": "SKIPPED", "reason": "依赖操作未执行"}
                    await self.store.step(runtime.context.run_id, step.id, "SKIPPED", skipped[step.id])
                    changed = True
        pending = [s for s in steps if s.id not in results]
        ready = [
            s for s in pending if all(results.get(d, {}).get("status") == "SUCCEEDED" for d in s.depends_on)
        ]
        reads = [s for s in ready if s.kind != "ACTION_PROPOSAL"]
        if reads:
            selected = reads[: self.settings.read_parallelism]
        else:
            selected = ready[:1]  # Every mutation is serialized, even without a dependency between writes.
        if pending and not selected:
            raise AgentError("PLAN_STALLED", "计划依赖无法继续执行", 409)
        return {"ready_steps": [s.id for s in selected], "step_results": skipped}

    def dispatch(self, state):
        if not state["ready_steps"]:
            return "synthesize"
        return [Send("execute_step", {**state, "_step_id": sid}) for sid in state["ready_steps"]]

    async def execute_step(self, state, runtime: Runtime[RunContext]):
        ctx = runtime.context.with_project(state["project_id"])
        step = next(
            s for s in IntentPlan.model_validate(state["intent_plan"]).steps if s.id == state["_step_id"]
        )
        await self.store.check_active(ctx)
        await self.store.step(ctx.run_id, step.id, "RUNNING")
        try:
            if step.kind == "ANALYSIS":
                payload = {
                    "request": state["current_request"],
                    "inputs": {d: state["step_results"][d] for d in step.depends_on},
                    "evidence": list(state.get("evidence", {}).values()),
                    "skill_guidance": state.get("active_skills", []),
                }
                for repair in range(2):
                    output = await self.model.generate(ctx, step.id, "analysis", payload, AnswerDocument)
                    errors = await validate_citations(
                        output, state.get("evidence", {}), ctx, self.store, self.harness.policy
                    )
                    if not errors:
                        break
                    payload["citation_errors"] = errors
                    if repair:
                        raise AgentError("CITATION_INVALID", "分析引用无效", detail=errors)
                result = {"status": "SUCCEEDED", "data": output.model_dump(mode="json")}
                evidence = {}
            else:
                arguments = resolve_arguments(step.arguments, state.get("step_results", {}))
                output = await self.harness.invoke(
                    step.tool, arguments, ctx, step.id, proposal=step.kind == "ACTION_PROPOSAL"
                )
                result = {
                    "status": "WAITING_APPROVAL" if step.kind == "ACTION_PROPOSAL" else "SUCCEEDED",
                    "data": output.data,
                }
                evidence = {e.id: e.model_dump(mode="json") for e in output.evidence}
            await self.store.step(ctx.run_id, step.id, result["status"], result)
            update = {"step_results": {step.id: result}, "evidence": evidence}
            if step.kind == "ACTION_PROPOSAL":
                update["pending_action_id"] = output.data["id"]
            return update
        except AgentError as exc:
            await self.store.step(
                ctx.run_id, step.id, "FAILED", error={"code": exc.code, "retryable": exc.retryable}
            )
            raise

    def after_step(self, state):
        return {}

    async def wait_approval(self, state, runtime: Runtime[RunContext]):
        ctx = runtime.context.with_project(state["project_id"])
        action = await self.actions.get(state["pending_action_id"], ctx.principal)
        interrupt(
            {
                "type": "APPROVAL_REQUIRED",
                "action_id": action["id"],
                "request_sha256": action["request_hash"],
                "before": action["before_state"],
                "proposed": action["arguments"],
                "expires_at": action["expires_at"],
                "simulated": True,
            }
        )
        # Resume data is only a signal. Authority always comes from the persisted action.
        action = await self.actions.get(state["pending_action_id"], ctx.principal)
        if action["approval_status"] in {"REJECTED", "REVOKED"}:
            result = {"status": "SKIPPED", "action_id": action["id"], "reason": action["approval_status"]}
            await self.store.step(ctx.run_id, action["step_id"], "SKIPPED", result)
            return {"pending_action_id": None, "step_results": {action["step_id"]: result}}
        if action["approval_status"] == "EXPIRED":
            raise AgentError("APPROVAL_EXPIRED", "审批已过期，请重新预览并审批", 409)
        if action["approval_status"] not in {"APPROVED", "CONSUMED"}:
            raise AgentError("APPROVAL_REQUIRED", "账本中没有有效审批", 409)
        return {}

    async def execute_action(self, state, runtime: Runtime[RunContext]):
        ctx = runtime.context.with_project(state["project_id"])
        action = await self.actions.get(state["pending_action_id"], ctx.principal)
        tool = action["tool"]
        spec = self.harness.registry.require(tool)
        run = await self.store.run(ctx.run_id)
        if tool not in self.harness.allowed_tools(run):
            raise AgentError("PACK_TOOL_FORBIDDEN", "动作工具不属于本轮模块", 403)
        ctx = ctx.for_pack(
            spec.pack_id,
            (state.get("resource_scopes") or {"project": state.get("project_id")}).get(spec.pack_id),
        )
        if self.skills:
            await self.skills.ensure_usable(ctx, tool)
        receipt = await self.actions.execute(ctx, action["id"])
        if not receipt or receipt.get("verified") is not True:
            raise AgentError("WRITE_VERIFICATION_FAILED", "执行回执缺少成功的写后验证", 502)
        if self.after_action:
            await self.after_action(receipt)
        actual = receipt["actual"]
        evidence = self.packs.require(ctx.pack_id).business.receipt_evidence(ctx, receipt)
        await self.store.save_evidence([evidence])
        result = {"status": "SUCCEEDED", "data": actual, "receipt": receipt}
        await self.store.step(ctx.run_id, action["step_id"], "SUCCEEDED", result)
        return {
            "step_results": {action["step_id"]: result},
            "pending_action_id": None,
            "evidence": {evidence.id: evidence.model_dump(mode="json")},
        }

    async def synthesize(self, state, runtime: Runtime[RunContext]):
        ctx = runtime.context.with_project(state["project_id"])
        receipts = [r["receipt"] for r in state["step_results"].values() if "receipt" in r]
        rejected = any(r["status"] == "SKIPPED" for r in state["step_results"].values())
        payload = {
            "request": state["current_request"],
            "steps": state["step_results"],
            "evidence": list(state["evidence"].values()),
            "receipts": receipts,
            "skill_guidance": state.get("active_skills", []),
        }
        for repair in range(2):
            answer = await self.model.generate(ctx, "final", "answer", payload, AnswerDocument)
            errors = await validate_citations(answer, state["evidence"], ctx, self.store, self.harness.policy)
            if not errors:
                break
            payload["citation_errors"] = errors
            if repair:
                raise AgentError("CITATION_INVALID", "引用校验失败，未发布答案正文", 502, detail=errors)
        result = render_answer(answer, state["evidence"], receipts, rejected)
        run = await self.store.run(ctx.run_id)
        result["trace"] = {
            "pack_versions": run["pack_versions"],
            "graph_version": run["graph_version"],
            "skills": await self.skills.selection(ctx) if self.skills else [],
        }
        result["artifact"] = await ArtifactStore(self.store).put_report(ctx, result)
        return {
            "final_result": result,
            "run_status": "SUCCEEDED",
            "messages": [
                AIMessage(
                    content=result["answer"],
                    id=f"{ctx.run_id}:assistant",
                    additional_kwargs={"pack_ids": run["pack_ids"]},
                )
            ],
        }


ProjectAssistantState = AssistantState  # Compatibility for persisted project graph consumers.
