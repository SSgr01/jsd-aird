from pathlib import Path

from jsd_aird_agents.app.platform.packs import PackBinding, VerticalPack
from jsd_aird_agents.contracts import DeadlineArgs, ProjectArgs, SearchArgs, TaskArgs
from jsd_aird_agents.errors import AgentError
from jsd_aird_agents.evidence import build_evidence


def tools():
    from jsd_aird_agents.harness.tools import ToolSpec

    result = [
        ToolSpec(n, ProjectArgs, "project.view")
        for n in (
            "get_project_snapshot",
            "list_project_stages",
            "list_project_tasks",
            "get_project_monitoring_snapshot",
            "get_project_approval_snapshot",
        )
    ]
    result += [
        ToolSpec("get_task_detail", TaskArgs, "project.view"),
        ToolSpec("search_project_knowledge", SearchArgs, "ai.use", kind="RAG_QUERY"),
        ToolSpec("update_project_task", DeadlineArgs, "project.update", True, "MEDIUM"),
    ]
    return result


class ProjectBusiness:
    async def notification_recipients(self, owner, scope, observation, options, identity, gateway):
        if options.notify_to != "approvers" or observation["rule_id"] != "approval.waiting":
            return [owner.actor_id]
        result = []
        from jsd_aird_agents.contracts import RunContext

        for p in list(identity.principals.values()):
            if (
                not p.can_approve
                or p.actor_id == owner.actor_id
                or p.organization_id != owner.organization_id
            ):
                continue
            try:
                await gateway.require(RunContext(p, "", "", scope), "project.update", scope)
                if "ai.use" in p.permissions:
                    result.append(p.actor_id)
            except AgentError:
                continue
        return result

    async def validate_notification(self, conn, notice, recipient, now):
        action_id = notice["payload"].get("action_id")
        if not action_id:
            return
        action = await (
            await conn.execute(
                """SELECT approval_status,expires_at,actor_id FROM mock_domain.actions
            WHERE id=%s AND project_id=%s AND organization_id=%s""",
                (action_id, notice["scope_id"], notice["organization_id"]),
            )
        ).fetchone()
        if not action or action["approval_status"] != "PENDING" or action["expires_at"] <= now:
            raise AgentError("APPROVAL_NO_LONGER_PENDING", "审批状态已变化")
        if notice["audience"] == "approvers" and (
            not recipient.can_approve
            or "project.update" not in recipient.permissions
            or str(action["actor_id"]) == recipient.actor_id
        ):
            raise AgentError("APPROVER_UNAUTHORIZED", "审批人资格已变化")

    async def planning_context(self, ctx, gateway):
        return {
            "available_projects": await gateway.projects(ctx.principal),
            "selected_project_id": ctx.project_id,
        }

    async def resolve(self, ctx, plan, gateway):
        scope = ctx.resource_id or ctx.project_id
        if not scope:
            candidates = await gateway.projects(
                ctx.principal, plan.resource_refs.get("project") or plan.project_ref
            )
            if len(candidates) != 1:
                return None, {"question": "请明确项目，提供以下候选的项目 ID。", "candidates": candidates}
            scope = candidates[0]["id"]
        ctx = ctx.for_pack("project", scope)
        await gateway.require(ctx, "project.view", scope)
        for step in plan.steps:
            if step.pack_id != "project" or "$ref" in str(step.arguments):
                continue
            if step.tool in {"get_task_detail", "update_project_task"}:
                try:
                    task = await gateway.resolve_task(ctx, step.arguments)
                except AgentError as exc:
                    if exc.code != "CLARIFICATION_REQUIRED":
                        raise
                    return scope, {"question": exc.message, **(exc.detail or {})}
                step.arguments.pop("task_ref", None)
                step.arguments["task_id"] = task["id"]
                if step.kind == "ACTION_PROPOSAL":
                    step.arguments.setdefault("expected_version", task["version"])
        return scope, None

    def receipt_evidence(self, ctx, receipt):
        actual = receipt["actual"]
        return build_evidence(ctx, "TASK", actual["id"], actual["version"], actual["name"], actual)


def bind(gateway):
    from jsd_aird_agents.app.platform.monitoring.task import MonitoringTask
    from jsd_aird_agents.app.platform.runtime.execution import TaskBinding

    from .model import PROMPTS, ScriptedModel
    from .monitoring import profiles

    monitoring = profiles()
    root = Path(__file__).parent
    return PackBinding(
        VerticalPack.load(root / "manifest.yaml"),
        root,
        tools(),
        ProjectBusiness(),
        gateway,
        PROMPTS,
        ScriptedModel(),
        monitoring_profiles=monitoring,
        task_factories={wid: TaskBinding(MonitoringTask.version, MonitoringTask) for wid in monitoring},
    )
