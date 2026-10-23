"""APScheduler calculates trigger times; PostgreSQL remains the only job/Run ledger."""

from datetime import date, datetime, timedelta, timezone
from typing import Literal
from zoneinfo import ZoneInfo

from apscheduler.triggers.cron import CronTrigger
from pydantic import BaseModel, ConfigDict, Field, model_validator

from jsd_aird_agents.app.platform.monitoring.contracts import MonitorOptions


class TriggerSpec(BaseModel):
    model_config = ConfigDict(extra="forbid")
    kind: Literal["cron", "date"]
    timezone: str = "Asia/Shanghai"
    expression: str | None = Field(default=None, max_length=120)
    at: datetime | None = None
    skip_dates: list[date] = Field(default_factory=list, max_length=366)

    @model_validator(mode="after")
    def valid(self):
        MonitorOptions(timezone=self.timezone)
        if self.kind == "date":
            if not self.at or self.at.tzinfo is None or self.expression or self.skip_dates:
                raise ValueError("Date trigger requires an aware at timestamp only")
        else:
            if self.at or not self.expression:
                raise ValueError("Cron trigger requires an expression")
            parts = self.expression.split()
            # Pin APScheduler's 3.x dialect; weekday names avoid its numeric-weekday ambiguity.
            if len(parts) != 5 or any(c.isdigit() for c in parts[4]):
                raise ValueError("Use a five-field cron expression and weekday names mon..sun or *")
            CronTrigger.from_crontab(self.expression, timezone=ZoneInfo(self.timezone))
        return self


def next_fire(spec: TriggerSpec, after: datetime):
    after = after.astimezone(timezone.utc)
    if spec.kind == "date":
        return spec.at.astimezone(timezone.utc) if spec.at > after else None
    zone = ZoneInfo(spec.timezone)
    trigger = CronTrigger.from_crontab(spec.expression, timezone=zone)
    search = after + timedelta(microseconds=1)
    for _ in range(1500):
        candidate = trigger.get_next_fire_time(None, search)
        if candidate is None:
            return None
        utc = candidate.astimezone(timezone.utc)
        local = utc.astimezone(zone)
        valid = local.replace(tzinfo=None) == candidate.replace(tzinfo=None) and local.fold == 0
        if utc > after and utc >= search and valid and local.date() not in spec.skip_dates:
            return utc
        if local.date() in spec.skip_dates:
            search = datetime.combine(local.date() + timedelta(days=1), datetime.min.time(), zone).astimezone(
                timezone.utc
            )
        else:
            search = max(search, utc) + timedelta(minutes=1)
    raise ValueError("Trigger search exceeded the supported calendar horizon")
