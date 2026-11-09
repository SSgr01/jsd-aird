import asyncio
import base64
import hashlib

import httpx
import pytest
from fastapi import HTTPException

from aird_agent.core.workspace import Workspace
from aird_agent.sandbox.broker import BrokerSettings, Execution, create_broker
from aird_agent.sandbox.client import Sandbox
from aird_agent.sandbox.jobs import BrokerJobs, TransferFile, decode_files


def transfer(path="input/data.txt", text=b"authorized"):
    return TransferFile(
        path=path, base64=base64.b64encode(text).decode(), sha256=hashlib.sha256(text).hexdigest()
    )


def request(principal):
    return Execution(
        organization_id=principal.organization_id,
        run_id=principal.actor_id,
        execution_id="a" * 32,
        script="synthetic",
        language="python",
    )


@pytest.mark.parametrize("path", ["../outside", "input/../secret", "output/injected", "input/C:stream"])
def test_transfer_refuses_paths_outside_read_only_inputs(path):
    with pytest.raises(HTTPException):
        decode_files([transfer(path)])


async def test_broker_job_is_idempotent_and_recovered_unknown_jobs_are_not_replayed(tmp_path, principal):
    class Runner:
        calls = 0

        async def execute(self, workspace, execution):
            self.calls += 1
            return {"exit_code": 0, "output": "ok", "artifacts": []}

    runner = Runner()
    jobs = BrokerJobs(tmp_path, runner)
    job = request(principal)
    await jobs.submit(job, [transfer()])
    await jobs.tasks[job.execution_id]
    result = await jobs.submit(job, [transfer()])
    assert result["status"] == "SUCCEEDED" and runner.calls == 1
    with pytest.raises(HTTPException, match="EXECUTION_ID_CONFLICT"):
        await jobs.submit(job, [transfer(text=b"different")])
    unknown = {"status": "RUNNING", "request_hash": "test", "execution_id": "b" * 32}
    jobs.save("b" * 32, unknown)
    restored = BrokerJobs(tmp_path, runner)
    assert restored.get("b" * 32)["status"] == "UNKNOWN" and runner.calls == 1


async def test_queued_job_cancellation_has_a_durable_receipt(tmp_path, principal):
    class Runner:
        async def execute(self, workspace, execution):
            await asyncio.Event().wait()

    jobs = BrokerJobs(tmp_path, Runner())
    job = request(principal)
    await jobs.submit(job, [])
    result = await jobs.cancel(job.execution_id)
    assert result["status"] == "CANCELLED"
    assert BrokerJobs(tmp_path, Runner()).get(job.execution_id)["status"] == "CANCELLED"


async def test_remote_protocol_transfers_files_between_separate_roots_without_docker(
    tmp_path, principal, settings, monkeypatch
):
    class Runner:
        async def execute(self, workspace, execution):
            assert workspace.read("input/data.txt")["content"] == "authorized"
            assert workspace.root.is_relative_to(tmp_path / "broker")
            output = workspace.write("output/result.txt", "computed by synthetic runner")
            return {"exit_code": 0, "output": "synthetic", "artifacts": [output]}

    import aird_agent.sandbox.broker as module

    monkeypatch.setattr(module, "ContainerRunner", lambda _: Runner())
    secret = "sandbox-" + "s" * 32
    app = create_broker(BrokerSettings(token=secret, workspace_root=tmp_path / "broker"))
    client_type = httpx.AsyncClient
    monkeypatch.setattr(
        httpx, "AsyncClient", lambda **kwargs: client_type(transport=httpx.ASGITransport(app=app), **kwargs)
    )
    workspace = Workspace(tmp_path / "control", str(principal.organization_id), str(principal.actor_id))
    workspace.prepare()
    workspace.materialize("input/data.txt", b"authorized")
    configured = settings.model_copy(
        update={"sandbox_enabled": True, "sandbox_secret": settings.runtime_secret.__class__(secret)}
    )
    result = await Sandbox(configured).execute(workspace, "synthetic", "python", "c" * 32, ["input/data.txt"])
    assert workspace.read("output/result.txt")["content"] == "computed by synthetic runner"
    assert result["receipt"]["status"] == "SUCCEEDED"
    assert not (tmp_path / "broker" / workspace.root.name / "output").exists()
