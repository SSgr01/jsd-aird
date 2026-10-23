from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field, replace
from typing import Any, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field

from jsd_aird_agents.app.platform.monitoring.contracts import MonitorOptions


def canonical(value: Any) -> str:
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), default=str)


def digest(value: Any) -> str:
    return hashlib.sha256(canonical(value).encode()).hexdigest()


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid")


@dataclass(frozen=True)
class Principal:
    organization_id: str
    actor_id: str
    projects: tuple[str, ...]
    permissions: tuple[str, ...] = ("project.view", "project.update", "ai.use")
    can_approve: bool = False
    resource_scopes: tuple[tuple[str, str], ...] = ()


@dataclass(frozen=True)
class RunContext:
    principal: Principal
    conversation_id: str
    run_id: str
    project_id: str | None = None
    worker_id: str | None = None
    lease_epoch: int = 0

    pack_id: str = "project"
    resource_id: str | None = None
    pack_ids: tuple[str, ...] = ("project",)
    scopes: dict[str, str] = field(default_factory=dict)

    def with_project(self, project_id: str | None):
        return replace(self, project_id=project_id)

    def for_pack(self, pack_id: str, scope: str | None = None):
        resource_id = scope or self.scopes.get(pack_id)
        if pack_id == "project":
            resource_id = resource_id or self.project_id
        return replace(
            self,
            pack_id=pack_id,
            resource_id=resource_id,
            project_id=resource_id if pack_id == "project" else None,
        )


class Step(Contract):
    id: str = Field(pattern=r"^[a-z][a-z0-9_]{0,47}$")
    kind: Literal["FACT_QUERY", "RAG_QUERY", "ANALYSIS", "ACTION_PROPOSAL"]
    tool: str | None = None
    pack_id: str | None = None
    depends_on: list[str] = Field(default_factory=list)
    arguments: dict[str, Any] = Field(
        default_factory=dict,
        description="Actual tool input values only, never tool schema/name/description/parameters or identity. ANALYSIS uses {}.",
    )


class IntentPlan(Contract):
    resource_refs: dict[str, str] = Field(default_factory=dict)
    project_ref: str | None = None
    needs_clarification: bool = False
    clarification_question: str | None = None
    steps: list[Step] = Field(default_factory=list)


class SkillSelection(Contract):
    names: list[str] = Field(default_factory=list, max_length=6)


class SkillResourceArgs(Contract):
    name: str = Field(pattern=r"^[a-z0-9]+(?:-[a-z0-9]+)*$", max_length=64)
    path: str = Field(min_length=1, max_length=512)


class Evidence(Contract):
    id: str
    run_id: str
    organization_id: str
    project_id: str | None = None
    pack_id: str = "project"
    scope_id: str | None = None
    source_type: str
    resource_id: str
    resource_version: str
    title: str
    locator: dict[str, Any]
    content: str
    content_sha256: str
    simulated: Literal[True] = True


class Claim(Contract):
    text: str = Field(min_length=1, max_length=4000)
    evidence_ids: list[str] = Field(min_length=1)


class AnswerDocument(Contract):
    claims: list[Claim] = Field(default_factory=list)
    recommendations: list[str] = Field(default_factory=list)


class CreateRun(Contract):
    monitoring: MonitorOptions | None = None
    request: str = Field(min_length=1, max_length=6000)
    pack_ids: list[str] = Field(default_factory=lambda: ["project"], min_length=1, max_length=4)
    resource_scopes: dict[str, UUID] = Field(default_factory=dict)
    workflow_id: str | None = None
    conversation_id: UUID | None = None
    project_id: UUID | None = None


class ResumeRun(Contract):
    interrupt_id: str | None = None
    answer: str | None = Field(default=None, max_length=6000)
    retry: bool = False


class ActionDecision(Contract):
    decision: Literal["APPROVED", "REJECTED", "REVOKED"]
    request_sha256: str


class ToolResult(Contract):
    data: Any
    evidence: list[Evidence] = Field(default_factory=list)


TERMINAL = {"SUCCEEDED", "FAILED", "CANCELLED"}
GRAPH_VERSION = "shared-assistant-v3-packs"
SKILLS_GRAPH_VERSION = "project-assistant-v2-skills"
LEGACY_GRAPH_VERSION = "project-assistant-v1"
ACTIVE = {"QUEUED", "RUNNING", "WAITING_CLARIFICATION", "WAITING_APPROVAL"}


from jsd_aird_agents.app.verticals.project.contracts import (  # noqa: E402,F401
    DeadlineArgs,
    ProjectArgs,
    SearchArgs,
    TaskArgs,
)
