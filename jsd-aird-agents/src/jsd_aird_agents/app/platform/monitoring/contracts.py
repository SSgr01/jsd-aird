from dataclasses import dataclass
from datetime import time
from typing import Any, Callable, Literal
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class MonitorOptions(BaseModel):
    model_config = ConfigDict(extra="forbid")
    timezone: str = "Asia/Shanghai"
    due_soon_days: int = Field(default=3, ge=0, le=30)
    stalled_days: int = Field(default=3, ge=1, le=90)
    milestone_days: int = Field(default=7, ge=1, le=30)
    approval_wait_minutes: int = Field(default=5, ge=0, le=43200)
    approval_expiry_minutes: int = Field(default=5, ge=0, le=1440)
    cooldown_hours: int = Field(default=24, ge=1, le=720)
    notify: bool = True
    notify_to: Literal["self", "approvers"] = "self"
    quiet_start: time | None = None
    quiet_end: time | None = None
    explain_with_model: bool = False

    @field_validator("timezone")
    @classmethod
    def valid_zone(cls, value):
        try:
            ZoneInfo(value)
        except (ValueError, ZoneInfoNotFoundError) as exc:
            raise ValueError("Unknown IANA timezone") from exc
        return value

    @model_validator(mode="after")
    def complete_quiet_period(self):
        if (self.quiet_start is None) != (self.quiet_end is None):
            raise ValueError("Both quiet times are required")
        if self.quiet_start is not None and (
            self.quiet_start == self.quiet_end or self.quiet_start.tzinfo or self.quiet_end.tzinfo
        ):
            raise ValueError("Quiet hours must be distinct local wall times")
        return self


@dataclass(frozen=True)
class MonitorProfile:
    id: str
    name: str
    tool: str
    rules: tuple[str, ...]
    evaluate: Callable[..., dict]
    defaults: MonitorOptions
    baseline_workflow: str | None = None
    version: str = "checks-v1"
    skill: str = ""
    kind: str = "inspection"


def profile_for(packs, workflow_id):
    found = [
        (pid, b.monitoring_profiles[workflow_id])
        for pid, b in packs.bindings.items()
        if workflow_id in b.monitoring_profiles
    ]
    return found[0] if len(found) == 1 else None


def effective_options(profile: MonitorProfile, supplied: MonitorOptions | None):
    data: dict[str, Any] = profile.defaults.model_dump()
    if supplied is not None:
        data.update(supplied.model_dump(exclude_unset=True))
    return MonitorOptions.model_validate(data)
