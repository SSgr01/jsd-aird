import asyncio
from threading import Event
from types import SimpleNamespace

import httpx
import pytest
from fastapi import HTTPException
from pydantic import SecretStr

from aird_agent.core.workspace import Workspace
from aird_agent.sandbox.broker import BrokerSettings, ContainerRunner, Execution
from aird_agent.sandbox.client import Sandbox
from aird_agent.sandbox.containers import ContainerPool
from aird_agent.sandbox.jobs import BrokerJobs


def request(principal, identity="a", **kwargs):
    return Execution(organization_id=principal.organization_id, run_id=principal.actor_id,
                     execution_id=identity * 32, script="echo hi", language="shell", **kwargs)


async def test_repeated_cancel_does_not_interrupt_cleanup_or_hold_submission_lock(tmp_path, principal):
    entered, cleanup, cleaned = asyncio.Event(), asyncio.Event(), asyncio.Event()

    class Runner:
        async def execute(self, workspace, job):
            if job.execution_id.startswith("b"):
                return {"exit_code": 0, "artifacts": []}
            entered.set()
            try:
                await asyncio.Event().wait()
            finally:
                cleanup.set()
                await cleaned.wait()

    jobs = BrokerJobs(tmp_path, Runner(), 1)
    first = request(principal)
    await jobs.submit(first, [])
    await entered.wait()
    assert (await jobs.cancel(first.execution_id))["status"] == "CANCELLING"
    await cleanup.wait()
    assert (await jobs.cancel(first.execution_id))["status"] == "CANCELLING"
    second = request(principal, "b")
    assert (await asyncio.wait_for(jobs.submit(second, []), 0.2))["status"] == "QUEUED"
    cleaned.set()
    await asyncio.gather(*list(jobs.tasks.values()))
    assert jobs.get(first.execution_id)["status"] == "CANCELLED"
    assert jobs.get(second.execution_id)["status"] == "SUCCEEDED"


async def test_cancel_during_staging_waits_for_copy_before_deleting_container(tmp_path, principal, monkeypatch):
    from aird_agent.sandbox import broker

    entered, finish = Event(), Event()
    released = asyncio.Event()
    settings = BrokerSettings(token="x" * 32, workspace_root=tmp_path)
    runner = ContainerRunner(settings)
    workspace = Workspace(tmp_path, str(principal.organization_id), str(principal.actor_id))
    workspace.prepare()
    slot = SimpleNamespace(workspace=workspace)

    async def acquire():
        return slot

    async def release(value):
        assert finish.is_set()
        released.set()
        return True

    def stage(*args):
        entered.set()
        assert finish.wait(2)

    runner.pool = SimpleNamespace(acquire=acquire, release=release)
    monkeypatch.setattr(broker, "stage_inputs", stage)
    task = asyncio.create_task(runner.execute(workspace, request(principal)))
    assert await asyncio.to_thread(entered.wait, 1)
    task.cancel()
    await asyncio.sleep(0.02)
    assert not released.is_set()
    finish.set()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert released.is_set()  # No script was sent to the container.


async def test_ready_wait_has_request_deadline_and_does_not_dispatch(tmp_path, principal):
    async def acquire():
        await asyncio.Event().wait()

    runner = ContainerRunner(BrokerSettings(token="x" * 32, workspace_root=tmp_path))
    runner.pool = SimpleNamespace(acquire=acquire)
    with pytest.raises(HTTPException, match="SANDBOX_STARTUP_TIMEOUT"):
        await asyncio.wait_for(runner.execute(None, request(principal, timeout_ms=30)), 0.3)


async def test_late_create_sweep_keeps_active_containers(tmp_path, monkeypatch):
    from aird_agent.sandbox import containers

    pool = ContainerPool(BrokerSettings(token="x" * 32, workspace_root=tmp_path), None)
    pool.slots["jsd-analysis-active"] = object()
    calls = []

    async def docker(*args, **kwargs):
        calls.append(args)
        return 0, b"jsd-analysis-active\njsd-analysis-late\nother-service\n", b""

    monkeypatch.setattr(containers, "docker", docker)
    await pool.reap_once()
    assert calls[0][3] == f"label={containers.LABEL}={pool.scope}"
    assert calls[1:] == [("rm", "--force", "jsd-analysis-late")]


async def test_lost_cancel_response_reconciles_without_throwing_timeout(monkeypatch):
    calls = []

    def respond(req):
        calls.append(req.method)
        if req.method == "DELETE":
            raise httpx.ReadTimeout("cleanup in progress", request=req)
        return httpx.Response(200, json={"execution_id": "a" * 32, "status": "CANCELLING"})

    original = httpx.AsyncClient
    monkeypatch.setattr(httpx, "AsyncClient", lambda **kwargs:
                        original(transport=httpx.MockTransport(respond), **kwargs))
    settings = SimpleNamespace(sandbox_enabled=True, sandbox_url="https://sandbox.test",
                               sandbox_secret=SecretStr("x" * 32))
    result = await Sandbox(settings).cancel("a" * 32)
    assert result["status"] == "CANCELLING"
    assert calls == ["DELETE", "GET"]
