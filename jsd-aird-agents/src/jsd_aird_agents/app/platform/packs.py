"""Validated declarations and explicit, in-process bindings. No imports from manifest data."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING, Callable, Literal

import yaml
from pydantic import Field, model_validator

from jsd_aird_agents.adapters.ports import ModelAdapter
from jsd_aird_agents.app.platform.ports import PackDomainGateway, VerticalBusiness
from jsd_aird_agents.app.platform.triggers import TriggerSpec
from jsd_aird_agents.contracts import Contract, digest
from jsd_aird_agents.errors import AgentError

if TYPE_CHECKING:
    from jsd_aird_agents.harness.tools import ToolSpec

# Subset verified against Java IamPermissionCatalog.java; declarations never grant these rights.
JAVA_PERMISSIONS = frozenset({"project.view", "project.update", "quality.view", "ai.use"})
SHARED_GRAPH_VERSION = "shared-assistant-v3-packs"


class SkillDeclaration(Contract):
    name: str = Field(pattern=r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
    path: str


class ToolDeclaration(Contract):
    name: str = Field(pattern=r"^[a-z][a-z0-9_]*$")
    version: str
    permission: str
    resource_kind: str
    mutation: bool = False
    risk: Literal["LOW", "MEDIUM", "HIGH"] = "LOW"
    kind: Literal["FACT_QUERY", "RAG_QUERY"] = "FACT_QUERY"


class WorkflowDeclaration(Contract):
    id: str
    version: str
    graph_version: str = SHARED_GRAPH_VERSION
    tools: list[str]
    description: str


class JobDeclaration(Contract):
    trigger: TriggerSpec | None = None
    id: str
    workflow: str
    request: str
    interval_seconds: int = Field(default=86400, ge=1)
    event_type: str | None = None


class EvidenceRule(Contract):
    source_type: str
    permission: str
    resource_kind: str


class EvaluationCase(Contract):
    id: str
    workflow: str
    request: str
    required_sources: list[str]
    must_contain: list[str] = Field(default_factory=list)
    forbid_receipts: bool = False


class VerticalPack(Contract):
    id: str = Field(pattern=r"^[a-z][a-z0-9-]{0,47}$")
    name: str
    version: str
    graph_version: str = SHARED_GRAPH_VERSION
    resource_kind: str
    required_permissions: list[str]
    entry_permission: str
    skills: list[SkillDeclaration] = Field(default_factory=list)
    tools: list[ToolDeclaration]
    workflows: list[WorkflowDeclaration]
    scheduled_jobs: list[JobDeclaration] = Field(default_factory=list)
    evidence_rules: list[EvidenceRule]
    evals: list[EvaluationCase]
    simulated: Literal[True] = True

    @model_validator(mode="after")
    def coherent(self):
        for items in (self.tools, self.workflows, self.scheduled_jobs, self.evals, self.skills):
            keys = [getattr(x, "id", getattr(x, "name", None)) for x in items]
            if len(keys) != len(set(keys)):
                raise ValueError("Duplicate declaration")
        permissions = set(self.required_permissions)
        if self.entry_permission not in permissions or not permissions or permissions - JAVA_PERMISSIONS:
            raise ValueError("Unmapped Java permission")
        if any(t.permission not in permissions for t in self.tools):
            raise ValueError("Tool permission must be declared")
        if any(e.permission not in permissions for e in self.evidence_rules):
            raise ValueError("Evidence permission must be declared")
        if any(t.resource_kind != self.resource_kind for t in self.tools) or any(
            e.resource_kind != self.resource_kind for e in self.evidence_rules
        ):
            raise ValueError("Tool and evidence scope must match the pack authority scope")
        if len({e.source_type for e in self.evidence_rules}) != len(self.evidence_rules):
            raise ValueError("Duplicate evidence rule")
        names, workflows = {t.name for t in self.tools}, {w.id for w in self.workflows}
        if any(set(w.tools) - names for w in self.workflows):
            raise ValueError("Workflow contains undeclared tools")
        if any(x.workflow not in workflows for x in [*self.scheduled_jobs, *self.evals]):
            raise ValueError("Unknown workflow")
        if self.graph_version != SHARED_GRAPH_VERSION:
            raise ValueError("Unsupported graph contract")
        return self

    @classmethod
    def load(cls, path: Path):
        if path.is_symlink() or path.stat().st_size > 65536:
            raise ValueError("Invalid pack manifest")
        # Reuse the bounded, duplicate-key rejecting YAML parser used for standard Skills.
        from jsd_aird_agents.skills.catalog import SkillYamlLoader

        return cls.model_validate(yaml.load(path.read_text(encoding="utf-8"), Loader=SkillYamlLoader))


@dataclass
class PackBinding:
    manifest: VerticalPack
    root: Path
    tools: list[ToolSpec]
    business: VerticalBusiness
    gateway: PackDomainGateway
    prompts: dict[str, str]
    scripted_model: ModelAdapter
    workflow_factories: dict[str, Callable] = field(default_factory=dict)
    monitoring_profiles: dict = field(default_factory=dict)
    task_factories: dict = field(default_factory=dict)


class VerticalRegistry:
    def __init__(self):
        self.bindings: dict[str, PackBinding] = {}

    def register(self, binding: PackBinding):
        pack = binding.manifest
        if pack.id in self.bindings:
            raise ValueError("Duplicate pack")
        declared = {t.name: t for t in pack.tools}
        workflows = {w.id: w for w in pack.workflows}
        if binding.monitoring_profiles and any(
            not callable(getattr(binding.business, method, None))
            for method in ("notification_recipients", "validate_notification")
        ):
            raise ValueError("Monitoring packs require a notification policy binding")
        if set(binding.task_factories) & set(binding.workflow_factories):
            raise ValueError("A workflow must select exactly one executor")
        if set(binding.task_factories) - set(workflows):
            raise ValueError("Task factory must belong to a declared workflow")
        if any(not b.version or not callable(b.factory) for b in binding.task_factories.values()):
            raise ValueError("Tasks require an explicit version and code factory")
        if set(binding.monitoring_profiles) - set(binding.task_factories):
            raise ValueError("Monitoring profiles require explicit execution bindings")
        if any(
            p.tool not in workflows[w].tools or workflows[w].version != p.version
            for w, p in binding.monitoring_profiles.items()
        ):
            raise ValueError("Monitoring tool or rule version mismatch")
        if set(binding.workflow_factories) - set(workflows):
            raise ValueError("Graph factory must belong to a declared workflow")
        if any(
            w.graph_version != pack.graph_version and w.id not in binding.workflow_factories
            for w in pack.workflows
        ):
            raise ValueError("Custom graph requires an explicitly bound factory")
        if set(declared) != {t.name for t in binding.tools}:
            raise ValueError("Manifest and tool bindings differ")
        existing = {t.name for b in self.bindings.values() for t in b.tools}
        for spec in binding.tools:
            declaration = declared[spec.name]
            if spec.name in existing or spec.pack_id != pack.id:
                raise ValueError("Tool name collision or owner mismatch")
            for attribute in ("permission", "mutation", "risk", "kind", "version", "resource_kind"):
                if getattr(spec, attribute) != getattr(declaration, attribute):
                    raise ValueError("Tool contract does not match its manifest")
        for skill in pack.skills:
            path = (binding.root / skill.path).resolve()
            if (
                not path.is_relative_to(binding.root.resolve())
                or path.name != "SKILL.md"
                or not path.is_file()
            ):
                raise ValueError("Skill must be an existing pack-local SKILL.md")
            if skill.name in {s.name for b in self.bindings.values() for s in b.manifest.skills}:
                raise ValueError("Skill names must be unique across registered packs")
        self.bindings[pack.id] = binding

    def require(self, pack_id):
        if pack_id not in self.bindings:
            raise AgentError("PACK_UNREGISTERED", "业务模块未注册", 403)
        return self.bindings[pack_id]

    def select(self, pack_ids):
        if not pack_ids or len(pack_ids) != len(set(pack_ids)):
            raise AgentError("PACK_SELECTION_INVALID", "需要明确且不重复的模块集合")
        return [self.require(pid) for pid in pack_ids]

    def pin(self, pack_ids):
        return {
            b.manifest.id: {
                "version": b.manifest.version,
                "sha256": digest(b.manifest.model_dump(mode="json")),
                "graph_version": b.manifest.graph_version,
                "tools": {
                    t.name: {"version": t.version, "schema_sha256": digest(t.schema.model_json_schema())}
                    for t in b.tools
                },
                "workflows": {
                    w.id: {
                        "version": w.version,
                        "graph_version": w.graph_version,
                        "custom_factory": w.id in b.workflow_factories or w.id in b.task_factories,
                    }
                    for w in b.manifest.workflows
                },
            }
            for b in self.select(pack_ids)
        }

    def verify(self, run):
        expected = run.get("pack_versions") or {}
        if expected and expected != self.pin(run["pack_ids"]):
            raise AgentError("PACK_VERSION_MISMATCH", "恢复需要原模块、工具契约和工作流版本", 409)

    def skill_owner(self, name):
        return next(
            (pid for pid, b in self.bindings.items() if name in {s.name for s in b.manifest.skills}), None
        )

    def tools_for(self, pack_ids):
        return {t.name for b in self.select(pack_ids) for t in b.tools} | {"load_skill_resource"}

    async def authorize(self, principal, pack_ids, scopes):
        from jsd_aird_agents.contracts import RunContext

        if "ai.use" not in principal.permissions:
            raise AgentError("PERMISSION_DENIED", "缺少 ai.use 权限", 403)
        for binding in self.select(pack_ids):
            pid = binding.manifest.id
            ctx = RunContext(principal, "", "", pack_id=pid, resource_id=scopes.get(pid))
            ctx = ctx.for_pack(pid, scopes.get(pid))
            if scopes.get(pid):
                await binding.gateway.require(ctx, binding.manifest.entry_permission, scopes[pid])
            else:
                await binding.gateway.require_entry(ctx, binding.manifest.entry_permission)

    def describe(self):
        return [b.manifest.model_dump(mode="json") for b in self.bindings.values()]
