from typing import Any, Protocol

from pydantic import BaseModel

from jsd_aird_agents.contracts import Principal, RunContext, ToolResult


class DomainGateway(Protocol):
    async def projects(self, principal: Principal, reference: str | None = None) -> list[dict]: ...
    async def resolve_task(self, context: RunContext, arguments: dict) -> dict: ...
    async def read(self, name: str, arguments: dict, context: RunContext) -> ToolResult: ...


class PolicyGateway(Protocol):
    async def require(self, context: RunContext, permission: str, project_id: str) -> None: ...


class ActionGateway(Protocol):
    async def preview(self, context: RunContext, step_id: str, tool: str, arguments: dict) -> dict: ...
    async def get(self, action_id: str, principal: Principal) -> dict: ...
    async def decide(
        self, action_id: str, principal: Principal, decision: str, request_hash: str
    ) -> dict: ...
    async def execute(self, context: RunContext, action_id: str) -> dict: ...


class ModelAdapter(Protocol):
    async def generate(
        self, kind: str, payload: dict[str, Any], schema: type[BaseModel]
    ) -> tuple[BaseModel, dict]: ...
