"""Portable ownership and serialization contracts; authority is supplied by the host."""

import hashlib
import json
from typing import Any, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


def digest(value: Any) -> str:
    return hashlib.sha256(
        json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), default=str).encode()
    ).hexdigest()


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Principal(Contract):
    organization_id: UUID
    actor_id: UUID
    auth_version: int
    username: str
    role: str

    @property
    def key(self) -> tuple[str, str]:
        return str(self.organization_id), str(self.actor_id)


class ExecutionRequest(Contract):
    request: str = Field(min_length=1, max_length=12000)
    conversation_id: UUID | None = None
    profile: Literal["agent", "fast_qa"] = "agent"
    pack_ids: list[str] | None = Field(default=None, min_length=1, max_length=16)
    scope: dict = Field(default_factory=dict)
    expected_revision: int | None = None
    resume_from_run_id: UUID | None = None
    resume_context: Literal["checkpoint", "rebuild"] = "checkpoint"
    upgrade_capabilities: bool = False
    resume_queue: bool = False
