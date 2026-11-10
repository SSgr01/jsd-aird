"""Portable ownership and serialization contracts; authority is supplied by the host."""

import hashlib
import json
from typing import Any
from uuid import UUID

from pydantic import BaseModel, ConfigDict


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
    automation_grant_id: str | None = None
    scope: str | None = None

    @property
    def key(self) -> tuple[str, str]:
        return str(self.organization_id), str(self.actor_id)
