from datetime import date
from uuid import UUID

from pydantic import Field

from jsd_aird_agents.contracts.types import Contract


class ProjectArgs(Contract):
    project_id: UUID | None = None


class TaskArgs(ProjectArgs):
    task_id: UUID | None = None
    task_ref: str | None = None


class SearchArgs(ProjectArgs):
    query: str = Field(min_length=1, max_length=1000)


class DeadlineArgs(TaskArgs):
    planned_date: date
    expected_version: int | None = Field(default=None, ge=0)
    reason: str = Field(default="用户提出的截止日期调整", max_length=1000)
