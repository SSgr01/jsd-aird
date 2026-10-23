"""Basic monitoring must not require a model or an Agent runtime."""

import asyncio
import json
import os
import subprocess
import sys
from dataclasses import replace
from uuid import uuid4

import httpx
import pytest
from conftest import AUTH, approve, awaiting_action

from jsd_aird_agents.adapters.fixtures import ACTOR, PROJECT
from jsd_aird_agents.api import PREFIX, create_app
from jsd_aird_agents.app.platform.monitoring.contracts import MonitorOptions
from jsd_aird_agents.app.platform.runtime.execution import TaskBinding
from jsd_aird_agents.app.platform.scheduler import CreateSchedule, Scheduler
from jsd_aird_agents.contracts import CreateRun, RunContext
from jsd_aird_agents.errors import AgentError
from jsd_aird_agents.services import Services
from jsd_aird_agents.worker import Worker

pytestmark = pytest.mark.integration
PROFILE = "project-task-followup"


def no_model_settings(settings):
    return settings.model_copy(
        update={
            "model_backend": "disabled",
            "model_base_url": "",
            "model_name": "",
            "model_api_key": settings.model_api_key.__class__(""),
        }
    )


async def task_run(services, profile=PROFILE, **kwargs):
    return await services.store.create_run(
        services.identity.lookup(ACTOR),
        CreateRun(request="运行固定检查", project_id=PROJECT, workflow_id=profile, **kwargs),
        str(uuid4()),
    )


async def test_five_profiles_without_model_or_graph_and_readiness(services, settings):
    bare = Services(no_model_settings(settings))
    await bare.open()
    try:

        def forbidden(*args, **kwargs):
            raise AssertionError("Basic monitoring cannot initialize a graph or load a Skill")

        bare.ensure_graph = forbidden
        bare.skills.catalog = forbidden
        bare.skills.instructions = forbidden
        assert bare.model.adapter is None and bare.graph is None and bare.db.checkpointer is None
        app = create_app(bare.settings, bare)
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            for profile in bare.packs.require("project").monitoring_profiles:
                response = await client.post(
                    PREFIX + "/runs",
                    headers={**AUTH, "Idempotency-Key": str(uuid4())},
                    json={"request": "规则检查", "workflow_id": profile, "project_id": PROJECT},
                )
                assert response.status_code == 202, response.text
                await Worker(bare).tick()
                run = await bare.store.run(response.json()["run_id"])
                assert run["status"] == "SUCCEEDED", run["error"]
                assert run["execution_kind"] == "task" and run["execution_version"] == "monitoring-task-v1"
                assert run["model_calls"] == 0 and run["tool_calls"] == 1
                assert run["result"]["trace"]["graph_version"] is None
            ready = await client.get(PREFIX + "/health/ready")
            assert ready.status_code == 200
            assert ready.json()["monitoring_ready"] and not ready.json()["agent_ready"]
            assert not ready.json()["model_configured"]
            denied = await client.post(
                PREFIX + "/runs",
                headers={**AUTH, "Idempotency-Key": str(uuid4())},
                json={"request": "分析延期"},
            )
            assert denied.status_code == 503 and denied.json()["error"]["code"] == "MODEL_NOT_CONFIGURED"
            denied = await client.post(
                PREFIX + "/runs",
                headers={**AUTH, "Idempotency-Key": str(uuid4())},
                json={
                    "request": "解释巡检",
                    "workflow_id": PROFILE,
                    "project_id": PROJECT,
                    "monitoring": {"explain_with_model": True},
                },
            )
            assert denied.status_code == 503
        assert bare.db.checkpointer is None
        assert not await bare.db.all("SELECT * FROM agent_checkpoint.checkpoints")
    finally:
        await bare.close()


async def test_all_fixed_schedules_dispatch_without_model(services, settings):
    bare = Services(no_model_settings(settings))
    await bare.open()
    try:
        scheduler = Scheduler(bare)
        for job in bare.packs.require("project").manifest.scheduled_jobs:
            if job.workflow not in bare.packs.require("project").task_factories:
                continue
            created = await scheduler.create(
                bare.identity.lookup(ACTOR),
                CreateSchedule(
                    pack_id="project",
                    job_id=job.id,
                    scope_id=PROJECT,
                    first_fire_at=bare.clock.now(),
                    max_runs=1,
                ),
                str(uuid4()),
            )
            await scheduler.tick()
            await Worker(bare).tick()
            await scheduler.tick()
            result = await scheduler.snapshot(created["id"], bare.identity.lookup(ACTOR))
            assert result["status"] == "EXHAUSTED" and result["occurrences"][0]["status"] == "SUCCEEDED"
        assert bare.graph is None and bare.db.checkpointer is None
        assert not await bare.db.all(
            "SELECT * FROM agent_runtime.calls WHERE call_kind LIKE %s", ("model:%",)
        )
    finally:
        await bare.close()


async def test_explicit_ai_explanation_is_single_call_without_graph(services, monkeypatch):
    async def forbidden(*args):
        raise AssertionError("An optional summary does not require a graph")

    monkeypatch.setattr(services, "ensure_graph", forbidden)
    run = await task_run(services, monitoring=MonitorOptions(explain_with_model=True))
    await Worker(services).tick()
    final = await services.store.run(run["id"])
    assert final["status"] == "SUCCEEDED" and final["model_calls"] == 1
    assert final["execution_kind"] == "task"


async def test_restart_after_durable_staging_resumes_plain_steps(services, settings):
    bare = Services(no_model_settings(settings))
    await bare.open()
    try:

        async def crash(run_id):
            raise RuntimeError("after batch commit before persist step commit")

        bare.monitoring.after_commit = crash
        run = await task_run(bare)
        await Worker(bare).tick()
        assert (await bare.store.run(run["id"]))["status"] == "FAILED"
        assert not await bare.db.all("SELECT * FROM agent_runtime.notifications")
    finally:
        await bare.close()
    restored = Services(no_model_settings(settings))
    await restored.open()
    try:
        await restored.store.enqueue_resume(run["id"], restored.identity.lookup(ACTOR), {"kind": "retry"})
        await Worker(restored).tick()
        final = await restored.store.run(run["id"])
        assert final["status"] == "SUCCEEDED" and final["tool_calls"] == 1 and final["model_calls"] == 0
        assert len(await restored.db.all("SELECT * FROM agent_runtime.monitoring_batches")) == 1
        assert restored.graph is None and restored.db.checkpointer is None
    finally:
        await restored.close()


async def test_two_workers_claim_once_and_restore_final_result(services, monkeypatch):
    run = await task_run(services)
    worker = Worker(services)

    async def crash(ctx, result, elapsed):
        raise AgentError("FINALIZE_PROBE", "Final task step committed", 500, retryable=True)

    monkeypatch.setattr(worker, "_complete", crash)
    await worker.tick()
    assert (await services.store.run(run["id"]))["status"] == "FAILED"
    await services.store.enqueue_resume(run["id"], services.identity.lookup(ACTOR), {"kind": "retry"})
    await asyncio.gather(Worker(services).tick(), Worker(services).tick())
    final = await services.store.run(run["id"])
    assert final["status"] == "SUCCEEDED" and final["tool_calls"] == 1 and final["model_calls"] == 0
    rows = await services.db.all("SELECT * FROM agent_runtime.notifications")
    assert len(rows) == len({str(r["id"]) for r in rows})


async def test_step_commit_fences_stale_worker_and_cancellation(services):
    created = await task_run(services)
    claimed = await services.store.claim("owner")
    ctx = RunContext(
        services.identity.lookup(ACTOR),
        created["conversation_id"],
        created["id"],
        PROJECT,
        "owner",
        claimed["lease_epoch"],
    )
    with pytest.raises(AgentError, match="执行权"):
        await services.store.complete_task_step(replace(ctx, lease_epoch=0), "probe", {})
    await services.store.cancel(created["id"], services.identity.lookup(ACTOR))
    with pytest.raises(AgentError) as error:
        await services.store.complete_task_step(ctx, "probe", {})
    assert error.value.code == "CANCELLED"
    assert not await services.db.all("SELECT * FROM agent_runtime.steps WHERE step_id='probe'")


async def test_plain_executor_version_change_rejected(services):
    created = await task_run(services)
    binding = services.packs.require("project").task_factories[PROFILE]
    services.packs.require("project").task_factories[PROFILE] = TaskBinding("different", binding.factory)
    await Worker(services).tick()
    final = await services.store.run(created["id"])
    assert final["error"]["code"] == "TASK_VERSION_MISMATCH"


async def test_lost_configuration_fails_model_schedule_once(services):
    job = await Scheduler(services).create(
        services.identity.lookup(ACTOR),
        CreateSchedule(
            pack_id="project",
            job_id="daily-task-followup",
            scope_id=PROJECT,
            first_fire_at=services.clock.now(),
            monitoring=MonitorOptions(explain_with_model=True),
        ),
        str(uuid4()),
    )
    services.settings.model_backend = "disabled"
    await Scheduler(services).tick()
    result = await Scheduler(services).snapshot(job["id"], services.identity.lookup(ACTOR))
    assert result["status"] == "FAILED"
    assert result["occurrences"][0]["error_code"] == "MODEL_NOT_CONFIGURED"


async def test_langgraph_import_blocked_in_fresh_rule_worker(services, settings):
    script = r"""
import asyncio, importlib.abc, json, os, sys
class NoGraph(importlib.abc.MetaPathFinder):
    def find_spec(self, fullname, path=None, target=None):
        if fullname == "langgraph" or fullname.startswith("langgraph."):
            raise ImportError("LangGraph deliberately unavailable")
sys.meta_path.insert(0, NoGraph())
from jsd_aird_agents.services import Services
from jsd_aird_agents.settings import Settings
from jsd_aird_agents.contracts import CreateRun
from jsd_aird_agents.worker import Worker
from jsd_aird_agents.adapters.fixtures import ACTOR, PROJECT
if sys.platform == "win32":
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
async def main():
    s = Services(Settings(database_url=os.environ["JSD_AIRD_AGENTS_TEST_DATABASE_URL"],
        actor_token="standalone-test-actor-token",approver_token="standalone-test-reviewer-token",
        model_backend="disabled",model_base_url="",model_name="",model_api_key="",_env_file=None))
    await s.open()
    try:
        r = await s.store.create_run(s.identity.lookup(ACTOR),CreateRun(request="规则检查",project_id=PROJECT,
            workflow_id="project-progress-inspection"),"without-langgraph")
        await Worker(s).tick()
        final = await s.store.run(r["id"])
        assert final["status"] == "SUCCEEDED", final["error"]
        assert not any(m == "langgraph" or m.startswith("langgraph.") for m in sys.modules)
        assert s.graph is None and s.db.checkpointer is None
        print(json.dumps({"status":final["status"],"model_calls":final["model_calls"],"execution_kind":final["execution_kind"]}))
    finally:
        await s.close()
asyncio.run(main())
"""
    env = {**os.environ, "JSD_AIRD_AGENTS_TEST_DATABASE_URL": settings.database_url.get_secret_value()}
    result = await asyncio.to_thread(
        subprocess.run, [sys.executable, "-c", script], env=env, capture_output=True, text=True, timeout=45
    )
    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout) == {"status": "SUCCEEDED", "model_calls": 0, "execution_kind": "task"}


async def test_cancel_running_plain_task_keeps_findings_unpublished(services, monkeypatch):
    from jsd_aird_agents.app.platform.monitoring.operations import MonitoringOperations

    started, release = asyncio.Event(), asyncio.Event()
    original = MonitoringOperations.check

    async def held(self, state, ctx):
        started.set()
        await release.wait()
        return await original(self, state, ctx)

    monkeypatch.setattr(MonitoringOperations, "check", held)
    run = await task_run(services)
    work = asyncio.create_task(Worker(services).tick())
    try:
        await asyncio.wait_for(started.wait(), 5)
        await services.store.cancel(run["id"], services.identity.lookup(ACTOR))
    finally:
        release.set()
        await work
    assert (await services.store.run(run["id"]))["status"] == "CANCELLED"
    assert not await services.db.all("SELECT * FROM agent_runtime.notification_outbox")


async def test_plain_task_retains_active_time_budget(services):
    services.settings.max_active_seconds = 0.001
    run = await task_run(services)
    await Worker(services).tick()
    final = await services.store.run(run["id"])
    assert final["status"] == "FAILED" and final["error"]["code"] == "BUDGET_EXCEEDED"
    assert not await services.db.all("SELECT * FROM agent_runtime.notification_outbox")


async def test_migration_retires_old_monitoring_but_preserves_agent_approval(services):
    pending, action = await awaiting_action(services)
    old_monitor = await task_run(services)
    # Reconstruct the pre-005 schema only in the conftest-validated *_test database.
    await services.db.execute(
        "ALTER TABLE agent_runtime.runs DROP COLUMN execution_kind, DROP COLUMN execution_version"
    )
    await services.db.execute("DELETE FROM agent_runtime.schema_version WHERE version=5")
    await services.db.migrate()
    retired = await services.store.run(old_monitor["id"])
    assert retired["status"] == "CANCELLED" and retired["error"]["code"] == "MONITORING_EXECUTION_RETIRED"
    assert not retired["error"]["retryable"]
    retained = await services.store.run(pending["id"])
    services.packs.verify(retained)
    assert retained["status"] == "WAITING_APPROVAL" and retained["execution_kind"] == "graph"
    await approve(services, action)
    await Worker(services).tick()
    assert (await services.store.run(pending["id"]))["status"] == "SUCCEEDED"
    assert (await task_run(services))["execution_kind"] == "task"


async def test_graph_cannot_take_over_a_fixed_monitor(services):
    run = await task_run(services)
    await services.db.execute(
        "UPDATE agent_runtime.runs SET execution_kind='graph' WHERE id=%s", (run["id"],)
    )
    await Worker(services).tick()
    final = await services.store.run(run["id"])
    assert final["status"] == "FAILED" and final["error"]["code"] == "MONITORING_EXECUTION_RETIRED"
    assert final["model_calls"] == 0
