"""Trusted in-process capability bindings; declarations never grant authority."""

import hashlib
import json
import re
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Protocol


def fingerprint(value):
    return hashlib.sha256(
        json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    ).hexdigest()


class HostGateway(Protocol):
    async def call(self, principal: Any, action: str, arguments: dict, run_id: str | None = None) -> dict: ...


@dataclass(frozen=True)
class ExecutionContext:
    principal: Any
    run: dict
    scope: dict
    host: Callable[..., Awaitable[dict]]


Handler = Callable[[ExecutionContext, dict], Awaitable[dict]]


@dataclass(frozen=True)
class CapabilityPack:
    id: str
    version: str
    instructions: str
    tools: tuple[dict, ...]
    initial_tools: tuple[str, ...] = ()
    handlers: dict[str, Handler] = field(default_factory=dict)
    mutations: frozenset[str] = frozenset()


class CapabilityRegistry:
    def __init__(self):
        self._packs: dict[str, CapabilityPack] = {}

    def register(self, pack: CapabilityPack):
        if not re.fullmatch(r"[a-z][a-z0-9-]{0,47}", pack.id) or not pack.version or pack.id in self._packs:
            raise ValueError("Invalid/duplicate capability pack")
        names = [tool["name"] for tool in pack.tools]
        existing = {tool["name"] for item in self._packs.values() for tool in item.tools}
        if len(names) != len(set(names)) or existing.intersection(names):
            raise ValueError("Tool names must be unique")
        if (
            set(pack.initial_tools) - set(names)
            or set(pack.handlers) - set(names)
            or pack.mutations - set(names)
        ):
            raise ValueError("Capability binding contains undeclared tools")
        if any(not callable(handler) for handler in pack.handlers.values()):
            raise ValueError("Tool bindings must be explicit callables")
        # Copy JSON declarations so a caller cannot change schemas after registration.
        self._packs[pack.id] = CapabilityPack(
            pack.id,
            pack.version,
            pack.instructions,
            tuple(json.loads(json.dumps(pack.tools))),
            tuple(pack.initial_tools),
            dict(pack.handlers),
            frozenset(pack.mutations),
        )

    def select(self, ids):
        if not ids or len(ids) != len(set(ids)) or any(key not in self._packs for key in ids):
            raise ValueError("Unknown/duplicate capability selection")
        return [self._packs[key] for key in ids]

    def tools(self, ids):
        return [json.loads(json.dumps(tool)) for pack in self.select(ids) for tool in pack.tools]

    def initial_tools(self, ids):
        return [name for pack in self.select(ids) for name in pack.initial_tools]

    def instructions(self, ids):
        return "\n\n".join(pack.instructions for pack in self.select(ids) if pack.instructions)

    def pin(self, ids):
        return {
            pack.id: {
                "version": pack.version,
                "sha256": fingerprint(
                    {
                        "instructions": pack.instructions,
                        "tools": pack.tools,
                        "initial_tools": pack.initial_tools,
                        "mutations": sorted(pack.mutations),
                    }
                ),
            }
            for pack in self.select(ids)
        }

    def verify(self, ids, versions):
        if self.pin(ids) != versions:
            raise ValueError("PACK_VERSION_MISMATCH")

    def handler(self, ids, name):
        return next((pack.handlers[name] for pack in self.select(ids) if name in pack.handlers), None)

    def mutations(self, ids):
        return set().union(*(pack.mutations for pack in self.select(ids)))

    def describe(self):
        return [
            {"id": pack.id, "version": pack.version, "tools": [tool["name"] for tool in pack.tools]}
            for pack in self._packs.values()
        ]
