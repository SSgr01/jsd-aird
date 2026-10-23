from jsd_aird_agents.adapters.identity import FixtureIdentity
from jsd_aird_agents.adapters.mock_java import MockJavaGateway
from jsd_aird_agents.app.platform.packs import VerticalRegistry
from jsd_aird_agents.app.platform.routing import ActionRouter, GatewayRouter, PackScriptedModel
from jsd_aird_agents.app.verticals.quality.gateway import MockQualityGateway
from jsd_aird_agents.contracts import SkillResourceArgs
from jsd_aird_agents.harness.tools import Harness, ToolRegistry, ToolSpec
from jsd_aird_agents.models.adapters import OpenAICompatibleModel
from jsd_aird_agents.models.service import ModelService
from jsd_aird_agents.persistence.database import Database
from jsd_aird_agents.persistence.store import Store
from jsd_aird_agents.skills.registry import SkillRegistry


class Services:
    def __init__(
        self,
        settings,
        *,
        model_adapter=None,
        after_action=None,
        extra_pack_factories=(),
        clock=None,
        after_monitor_commit=None,
    ):
        from jsd_aird_agents.app.platform.clock import SystemClock
        from jsd_aird_agents.app.verticals.project.pack import bind as project
        from jsd_aird_agents.app.verticals.quality.pack import bind as quality

        self.clock = clock or SystemClock()
        self.settings = settings
        self.db = Database(settings)
        self.packs = VerticalRegistry()
        self.store = Store(self.db, self.packs)
        self.store.clock = self.clock
        self.identity = FixtureIdentity(settings)
        self.domain = MockJavaGateway(self.store, self.identity)  # Existing project adapter contract.
        self.packs.register(project(self.domain))
        self.packs.register(quality(MockQualityGateway(self.store, self.identity)))
        for factory in extra_pack_factories:  # Explicit code allowlist, never a manifest import string.
            self.packs.register(factory(self.store, self.identity))
        self.registry = ToolRegistry()
        for binding in self.packs.bindings.values():
            for spec in binding.tools:
                self.registry.register(spec)
        self.registry.register(
            ToolSpec(
                "load_skill_resource", SkillResourceArgs, "ai.use", pack_id="platform", resource_kind="RUN"
            )
        )
        self.actions = ActionRouter(self.packs)
        self.gateway = GatewayRouter(self.packs, self.store)
        self.store.action_gateways = [
            b.gateway for b in self.packs.bindings.values() if any(t.mutation for t in b.tools)
        ]
        if "skill_roots" not in settings.model_fields_set:
            # Explicit operator roots remain authoritative; otherwise discover only registered pack roots.
            settings.skill_roots = list(
                dict.fromkeys(
                    (b.root / skill.path).parent.parent
                    for b in self.packs.bindings.values()
                    for skill in b.manifest.skills
                )
            )
        self.skills = SkillRegistry(self.store, settings, self.registry, self.packs)
        self.harness = Harness(
            self.registry,
            self.gateway,
            self.gateway,
            self.actions,
            self.store,
            settings,
            skills=self.skills,
            packs=self.packs,
        )
        if model_adapter is None:
            if settings.model_backend == "scripted":
                model_adapter = PackScriptedModel(self.packs)
            elif settings.model_configured:
                model_adapter = OpenAICompatibleModel(settings)
        self.model = ModelService(
            model_adapter, self.store, settings, run_guard=self.guard_run, packs=self.packs
        )
        self.workflow = None
        self.after_action = after_action
        from jsd_aird_agents.app.platform.monitoring.service import MonitoringService

        self.monitoring = MonitoringService(self, after_commit=after_monitor_commit)
        self.graph = None
        self.workflow_graphs = {}

    async def guard_run(self, ctx):
        from jsd_aird_agents.errors import AgentError

        await self.skills.ensure_usable(ctx)
        run = await self.store.run(ctx.run_id)
        principal = self.identity.lookup(run["actor_id"])
        if principal.organization_id != run["organization_id"] or "ai.use" not in principal.permissions:
            raise AgentError("PERMISSION_DENIED", "当前主体没有 AI 使用权限", 403)
        await self.packs.authorize(principal, run["pack_ids"], run["resource_scopes"])

    async def open(self):
        await self.db.open()
        if self.settings.model_configured:
            await self.enable_agent()

    async def enable_agent(self):
        if self.graph is not None:
            return
        from jsd_aird_agents.graph.workflow import Workflow

        await self.db.enable_checkpoints()
        self.workflow = Workflow(
            self.store,
            self.harness,
            self.model,
            self.settings,
            after_action=self.after_action,
            skills=self.skills,
            packs=self.packs,
        )
        self.graph = self.workflow.compile(self.db.checkpointer)
        for pid, binding in self.packs.bindings.items():
            for wid, factory in binding.workflow_factories.items():
                self.workflow_graphs[pid, wid] = factory(self.workflow, self.db.checkpointer)

    async def ensure_graph(self, run):
        if any(run.get("workflow_id") in self.packs.require(pid).task_factories for pid in run["pack_ids"]):
            from jsd_aird_agents.errors import AgentError

            raise AgentError(
                "MONITORING_EXECUTION_RETIRED", "固定巡检只能使用普通任务执行器，请创建新 Run", 409
            )
        await self.enable_agent()
        if run.get("workflow_id"):
            for pid in run["pack_ids"]:
                binding = self.packs.require(pid)
                wid = run["workflow_id"]
                if wid in binding.workflow_factories and (pid, wid) not in self.workflow_graphs:
                    self.workflow_graphs[pid, wid] = binding.workflow_factories[wid](
                        self.workflow, self.db.checkpointer
                    )
        return self.graph_for(run)

    def task_binding(self, run):
        from jsd_aird_agents.errors import AgentError

        bindings = [
            self.packs.require(pid).task_factories[run["workflow_id"]]
            for pid in run["pack_ids"]
            if run["workflow_id"] in self.packs.require(pid).task_factories
        ]
        if len(bindings) != 1:
            raise AgentError("TASK_UNREGISTERED", "固定任务执行器未注册", 403)
        binding = bindings[0]
        if run["execution_version"] != binding.version:
            raise AgentError("TASK_VERSION_MISMATCH", "恢复需要原固定任务执行器版本", 409)
        return binding

    def graph_for(self, run):
        if run.get("workflow_id"):
            from jsd_aird_agents.errors import AgentError

            matches = [
                (pid, w.id)
                for pid in run["pack_ids"]
                for w in self.packs.require(pid).manifest.workflows
                if w.id == run["workflow_id"]
            ]
            if len(matches) != 1:
                raise AgentError("WORKFLOW_UNREGISTERED", "工作流未注册或归属不明确", 403)
            return self.workflow_graphs.get(matches[0], self.graph)
        return self.graph

    async def close(self):
        if self.model.adapter and hasattr(self.model.adapter, "aclose"):
            await self.model.adapter.aclose()
        await self.db.close()
