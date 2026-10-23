"""Execution contracts with no dependency on an Agent framework."""

from dataclasses import dataclass
from typing import Callable

from jsd_aird_agents.errors import AgentError


@dataclass(frozen=True)
class TaskBinding:
    version: str
    factory: Callable


def requires_model(run):
    monitoring = run.get("monitoring") or {}
    if monitoring:
        return bool(monitoring["options"]["explain_with_model"])
    return True


def require_model_config(settings, run):
    if requires_model(run) and not settings.model_configured:
        raise AgentError("MODEL_NOT_CONFIGURED", "此请求需要模型，请配置模型后重试", 503)
