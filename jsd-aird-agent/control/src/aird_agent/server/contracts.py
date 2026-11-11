from __future__ import annotations

import re
from pathlib import Path
from typing import Literal
from uuid import UUID

from pydantic import AliasChoices, Field, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

from aird_agent.core.contracts import Contract as Contract
from aird_agent.core.contracts import Principal as Principal
from aird_agent.core.contracts import digest as digest
from aird_agent.evidence.models import SourceRef as SourceRef


class ContextRef(Contract):
    kind: Literal["PROJECT", "EXPERIMENT", "KB_PUBLICATION"]
    resource_id: UUID
    publication_id: UUID | None = None
    version_id: str | None = Field(default=None, min_length=1, max_length=100)

    @model_validator(mode="after")
    def coherent(self):
        if self.kind == "KB_PUBLICATION" and self.publication_id is None:
            raise ValueError("A knowledge context must pin a publication")
        if self.kind == "EXPERIMENT":
            if self.version_id is None:
                raise ValueError("An experiment reference must pin a version")
            UUID(self.version_id)
        if self.kind == "PROJECT" and self.version_id is not None and not self.version_id.isdecimal():
            raise ValueError("Project versions are numeric")
        return self


class PublicationRef(Contract):
    document_id: UUID
    publication_id: UUID


class RetrievalScope(Contract):
    knowledge_category_ids: list[UUID] = Field(default_factory=list, max_length=100)
    data_category_ids: list[UUID] = Field(default_factory=list, max_length=100)
    web_search_enabled: bool = False
    knowledge_documents: list[PublicationRef] | None = Field(default=None, min_length=1, max_length=100)


class ResearchRequirements(Contract):
    fulltext_documents: list[PublicationRef] = Field(min_length=1, max_length=10)
    require_rcs: bool = True
    require_report: bool = False
    require_cards: bool = False
    question_ids: list[str] = Field(default_factory=list, max_length=40)
    question_targets: dict[str, str] = Field(default_factory=dict, max_length=40)

    @model_validator(mode="after")
    def question_keys(self):
        keys = set(self.question_ids) | self.question_targets.keys()
        if (
            len(keys) > 40
            or len(set(self.question_ids)) != len(self.question_ids)
            or any(not re.fullmatch(r"[a-zA-Z0-9_-]{1,64}", key) for key in keys)
        ):
            raise ValueError("Question IDs must be unique stable keys")
        if any(not text.strip() or len(text) > 1000 for text in self.question_targets.values()):
            raise ValueError("Question targets must contain bounded, nonempty user requirements")
        return self


class RunRequest(Contract):
    request: str = Field(min_length=1, max_length=12000)
    conversation_id: UUID | None = None
    topic_id: UUID | None = None
    profile: Literal["agent"] = "agent"
    pack_ids: list[str] | None = Field(default=None, min_length=1, max_length=16)
    context_refs: list[ContextRef] = Field(default_factory=list, max_length=100)
    retrieval_scope: RetrievalScope = Field(default_factory=RetrievalScope)
    research_requirements: ResearchRequirements | None = None
    expected_revision: int | None = None
    private_source_ids: list[UUID] = Field(default_factory=list, max_length=20)



class MessageRequest(Contract):
    text: str = Field(min_length=1, max_length=12000)
    delivery: Literal["steer", "follow_up"] = "follow_up"
    expected_revision: int


class QueueRequest(Contract):
    action: Literal["edit", "delete", "promote"]
    message_id: UUID
    expected_revision: int
    text: str | None = Field(default=None, min_length=1, max_length=12000)

    @model_validator(mode="after")
    def arguments(self):
        if self.action == "edit" and (self.text is None or not self.text.strip()):
            raise ValueError("An edited message requires nonblank text")
        return self


def retryable_failure(run):
    code = run.get("error") or ""
    return run.get("status") == "FAILED" and (
        code
        in {
            "DSH_RUNTIME_UNAVAILABLE",
            "DSH_EXECUTION_INTERRUPTED",
            "RUN_CAPACITY_REACHED",
            "TOOL_ACTIVATION_FAILED",
            "RUNTIME_EXECUTION_FAILED",
            "MODEL_DID_NOT_COMPLETE",
            "CONTEXT_COMPACTION_FAILED",
        }
        or bool(re.fullmatch(r"CONTROL_5[0-9]{2}", code))
    )


class EvidenceField(Contract):
    name: str = Field(min_length=1, max_length=100)
    original: str = Field(max_length=6000)
    excerpt: str = Field(min_length=1, max_length=6000)
    value: str | None = None
    unit: str | None = None
    qualifiers: dict[str, str] = Field(default_factory=dict)
    source: SourceRef
    status: Literal["AI_EXTRACTED", "EXTRACTION_VERIFIED", "REJECTED"] = "AI_EXTRACTED"


class EvidenceCard(Contract):
    topic_id: UUID
    instance: str = Field(min_length=1, max_length=500)
    target: str = Field(max_length=1000)
    fields: list[EvidenceField] = Field(min_length=1, max_length=100)


class HarnessSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="JSD_AIRD_HARNESS_", env_file=".env", extra="ignore")
    database_url: SecretStr
    shared_secret: SecretStr
    runtime_secret: SecretStr
    runtime_url: str = "http://127.0.0.1:8092"
    java_url: str = "http://127.0.0.1:8080"
    public_url: str = Field(default="http://localhost:5173", validation_alias=AliasChoices("JSD_AIRD_HARNESS_PUBLIC_URL", "JSD_AIRD_PUBLIC_URL", "public_url"))
    storage_root: Path = Path(".runtime/harness")
    model_context_window: int = Field(default=128000, ge=16000)
    model_output_tokens: int = Field(default=8192, ge=512)
    max_steps: int = Field(default=32, ge=1, le=128)
    max_model_calls: int = Field(default=128, ge=1, le=512)
    max_tool_calls: int = Field(default=128, ge=1, le=512)
    max_total_tokens: int = Field(default=2000000, ge=1024)
    max_active_seconds: int = Field(default=1800, ge=10)
    stage_timing: bool = False
    citation_repair_model_calls: int = Field(default=2, ge=1, le=2)
    citation_repair_tool_calls: int = Field(default=2, ge=0, le=2)
    parallelism: int = Field(default=4, ge=1, le=8)
    max_running_runs: int = Field(default=4, ge=1, le=32)
    max_running_runs_per_user: int = Field(default=2, ge=1, le=32)
    max_running_runs_per_organization: int | None = Field(default=None, ge=1, le=32)
    admission_heartbeat_seconds: int = Field(default=10, ge=1, le=60)
    admission_lease_seconds: int = Field(default=60, ge=10, le=600)
    workspace_retention_days: int = Field(default=7, ge=1, le=90)
    sandbox_enabled: bool = False
    sandbox_url: str = "http://127.0.0.1:8094"
    sandbox_secret: SecretStr = SecretStr("")
    sandbox_image: str = "jsd-aird-analysis:1"
    sandbox_memory: str = "1g"
    sandbox_cpus: str = "1"
    sandbox_timeout_seconds: int = Field(default=600, ge=1, le=600)
    report_review_timeout_seconds: int = Field(default=120, ge=10, le=600)

    @model_validator(mode="after")
    def boundaries(self):
        if self.admission_lease_seconds < self.admission_heartbeat_seconds * 3:
            raise ValueError('Admission lease must allow at least three heartbeats')
        from urllib.parse import urlsplit

        if not urlsplit(self.database_url.get_secret_value()).path.strip("/").startswith("jsd_aird_agents"):
            raise ValueError("A dedicated jsd_aird_agents database is required")
        if min(len(self.shared_secret.get_secret_value()), len(self.runtime_secret.get_secret_value())) < 32:
            raise ValueError("Independent service secrets of at least 32 characters are required")
        if self.shared_secret == self.runtime_secret:
            raise ValueError("Java delegation and runtime service secrets must differ")
        if self.sandbox_enabled and (
            len(self.sandbox_secret.get_secret_value()) < 32
            or self.sandbox_secret in {self.shared_secret, self.runtime_secret}
        ):
            raise ValueError("Sandbox requires a third independent service secret")
        for endpoint in (self.runtime_url, self.java_url, self.sandbox_url):
            parsed = urlsplit(endpoint)
            if (
                parsed.scheme not in {"http", "https"}
                or parsed.username
                or parsed.password
                or parsed.query
                or parsed.fragment
            ):
                raise ValueError("Invalid service endpoint")
        if self.model_context_window <= self.model_output_tokens + 8192:
            raise ValueError("Model context must leave room for tools and output")
        return self
