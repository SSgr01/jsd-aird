import os
import subprocess
import sys
import time
from pathlib import Path

import pytest
from fastapi import HTTPException

from aird_agent.core import CapabilityPack, CapabilityRegistry
from aird_agent.core.bindings import RuntimeBindings
from aird_agent.core.config import RuntimeConfig
from aird_agent.core.contracts import ExecutionRequest
from aird_agent.core.coordinator import Coordinator


def test_portable_coordinator_import_does_not_load_a_jsd_adapter():
    environment = {**os.environ, "PYTHONPATH": str(Path(__file__).parents[1] / "src")}
    subprocess.run(
        [
            sys.executable,
            "-c",
            "import sys; from aird_agent.core.coordinator import Coordinator; "
            "assert not any(name.startswith('aird_agent.server') for name in sys.modules)",
        ],
        env=environment,
        check=True,
        capture_output=True,
        text=True,
    )


@pytest.mark.integration
async def test_second_host_uses_its_own_scope_registry_and_tools_without_java(store, principal, tmp_path):
    class OtherHost:
        allowed = True
        calls = []

        async def call(self, user, action, arguments, run_id=None):
            self.calls.append(action)
            assert action == "authorize_context"
            if not self.allowed:
                raise HTTPException(403, "OTHER_HOST_REVOKED")
            return {
                "scope": {"tenant": str(user.organization_id)},
                "pack_ids": ["core", "arithmetic"],
                "model_context": {"purpose": "portable arithmetic test"},
            }

    async def add(context, args):
        return {"sum": args["left"] + args["right"]}

    registry = CapabilityRegistry()
    registry.register(CapabilityPack("core", "1", "Use the host's authorized tools.", ()))
    registry.register(
        CapabilityPack(
            "arithmetic",
            "1",
            "Return numeric arithmetic results.",
            (
                {
                    "name": "add",
                    "description": "Add two numbers",
                    "parameters": {
                        "type": "object",
                        "properties": {"left": {"type": "number"}, "right": {"type": "number"}},
                        "required": ["left", "right"],
                        "additionalProperties": False,
                    },
                },
            ),
            ("add",),
            {"add": add},
        )
    )
    host = OtherHost()
    runtime = Coordinator(
        RuntimeConfig(runtime_secret="r" * 32, storage_root=tmp_path),
        store,
        registry=registry,
        host=host,
        bindings=RuntimeBindings(),
    )
    try:
        assert runtime.authorization_sources([{"id": "other-host-source"}]) == [{"id": "other-host-source"}]
        run = await runtime.create_run(
            principal, ExecutionRequest(request="Add 2 and 3", pack_ids=["core", "arithmetic"]), "other-host"
        )
        assert run["host_scope"] == {"tenant": str(principal.organization_id)}
        async with store.transaction(principal, run["id"]) as conn:
            current = await store.get(principal, "run", run["id"], conn)
            current.update(status="RUNNING", epoch=1, started_at=time.time(), lease_until=time.time() + 180)
            await store.update(principal, "run", current, conn)
        assert await runtime.tool(principal, run["id"], 1, "addition", "add", {"left": 2, "right": 3}) == {
            "sum": 5
        }
        host.allowed = False
        with pytest.raises(HTTPException, match="OTHER_HOST_REVOKED"):
            await runtime.tool(principal, run["id"], 1, "addition", "add", {"left": 2, "right": 3})
        host.allowed = True
        await runtime.callback(principal, run["id"], "settled", {"epoch": 1, "text": "5"})
        assert (await store.get(principal, "run", run["id"]))["status"] == "SUCCEEDED"
        assert set(host.calls) == {"authorize_context"}
        assert not hasattr(runtime.settings, "java_url")
    finally:
        await runtime.http.aclose()
