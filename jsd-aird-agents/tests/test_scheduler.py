import asyncio
from dataclasses import replace
from datetime import timedelta
from uuid import uuid4

import pytest
from conftest import AUTH, FOREIGN

from jsd_aird_agents.adapters.fixtures import ACTOR, PROJECT
from jsd_aird_agents.app.platform.scheduler import CreateSchedule, Scheduler
from jsd_aird_agents.app.verticals.quality.gateway import DATASET
from jsd_aird_agents.errors import AgentError
from jsd_aird_agents.models.adapters import ScriptedModel
from jsd_aird_agents.persistence.store import utcnow
from jsd_aird_agents.worker import Worker

pytestmark = pytest.mark.integration
PREFIX = "/internal/v1/agents"


async def job(services, *, quality=False, key=None, **options):
    command = CreateSchedule(
        pack_id="quality" if quality else "project",
        job_id="daily-quality-anomaly-check" if quality else "daily-project-health-check",
        scope_id=DATASET if quality else PROJECT,
        **options,
    )
    return await Scheduler(services).create(services.identity.lookup(ACTOR), command, key or str(uuid4()))


async def occurrence(services, schedule):
    return await services.db.one(
        "SELECT * FROM agent_runtime.schedule_occurrences WHERE schedule_id=%s ORDER BY created_at LIMIT 1",
        (schedule["id"],),
    )


async def test_competing_schedulers_dispatch_only_once_and_share_worker(services):
    schedule = await job(services, max_runs=1)
    await asyncio.gather(*(Scheduler(services).tick() for _ in range(5)))
    rows = await services.db.all("SELECT * FROM agent_runtime.schedule_occurrences")
    assert len(rows) == 1 and rows[0]["attempts"] == 1
    assert len(await services.db.all("SELECT id FROM agent_runtime.runs")) == 1
    await Worker(services).tick()
    await Scheduler(services).tick()
    result = await Scheduler(services).snapshot(schedule["id"], services.identity.lookup(ACTOR))
    assert result["occurrences"][0]["status"] == "SUCCEEDED"
    assert result["status"] == "EXHAUSTED"
    run = await services.store.run(str(rows[0]["run_id"]))
    assert run["workflow_id"] == "project-health-check" and run["result"]["receipts"] == []


async def test_quality_job_uses_same_scheduler_and_only_quality_tools(services):
    schedule = await job(services, quality=True, max_runs=1)
    await Scheduler(services).tick()
    item = await occurrence(services, schedule)
    await Worker(services).tick()
    run = await services.store.run(str(item["run_id"]))
    assert run["status"] == "SUCCEEDED", run["error"]
    assert run["pack_ids"] == ["quality"]
    assert {s["tool"] for s in run["plan"]["steps"] if s["tool"]} == {"list_quality_anomalies"}


async def test_crash_after_run_creation_reconciles_same_occurrence_and_run(services):
    schedule = await job(services)

    async def crash(run):
        raise RuntimeError("process stopped after durable Run commit")

    with pytest.raises(RuntimeError):
        await Scheduler(services, after_dispatch=crash).tick()
    runs = await services.db.all("SELECT id FROM agent_runtime.runs")
    assert len(runs) == 1
    assert not await occurrence(services, schedule)  # Outer dispatch projection rolled back.
    await Scheduler(services).tick()
    item = await occurrence(services, schedule)
    assert item["run_id"] == runs[0]["id"]
    assert len(await services.db.all("SELECT id FROM agent_runtime.runs")) == 1


async def test_pause_resume_cancel_and_owner_checks(services, client):
    schedule = await job(services)
    sid = schedule["id"]
    assert (await client.get(PREFIX + f"/schedules/{sid}", headers=FOREIGN)).status_code == 404
    assert (await client.post(PREFIX + f"/schedules/{sid}/pause", headers=AUTH)).status_code == 200
    await Scheduler(services).tick()
    assert not await occurrence(services, schedule)
    await Scheduler(services).transition(sid, services.identity.lookup(ACTOR), "resume")
    await Scheduler(services).tick()
    item = await occurrence(services, schedule)
    await Scheduler(services).transition(sid, services.identity.lookup(ACTOR), "cancel")
    assert (await services.store.run(str(item["run_id"])))["status"] == "CANCELLED"
    assert (await Scheduler(services).snapshot(sid, services.identity.lookup(ACTOR)))["status"] == "CANCELLED"
    with pytest.raises(AgentError):
        await Scheduler(services).transition(sid, services.identity.lookup(ACTOR), "resume")


async def test_event_trigger_deduplication_and_pack_definition_whitelist(services):
    schedule = await job(services, quality=True, first_fire_at=utcnow() + timedelta(days=1))
    scheduler = Scheduler(services)
    p = services.identity.lookup(ACTOR)
    with pytest.raises(AgentError):
        await scheduler.trigger(schedule["id"], p, "project.changed", "same-event")
    await asyncio.gather(
        *(scheduler.trigger(schedule["id"], p, "quality.changed", "same-event") for _ in range(3))
    )
    assert len(await services.db.all("SELECT * FROM agent_runtime.schedule_occurrences")) == 1
    await scheduler.tick()
    assert len(await services.db.all("SELECT * FROM agent_runtime.runs")) == 1
    assert (await occurrence(services, schedule))["attempts"] == 1


async def test_schedule_idempotency_different_parameters_and_unknown_job(services):
    first = await job(services, key="repeat")
    assert (await job(services, key="repeat"))["id"] == first["id"]
    with pytest.raises(AgentError) as error:
        await job(services, key="repeat", max_runs=2)
    assert error.value.code == "IDEMPOTENCY_CONFLICT"
    with pytest.raises(AgentError):
        await Scheduler(services).create(
            services.identity.lookup(ACTOR),
            CreateSchedule(pack_id="quality", job_id="daily-project-health-check", scope_id=DATASET),
            "unknown-job",
        )


async def test_revocation_before_dispatch_fails_without_creating_run(services):
    schedule = await job(services, quality=True)
    p = services.identity.lookup(ACTOR)
    services.identity.principals[ACTOR] = replace(p, permissions=("project.view", "ai.use"))
    await Scheduler(services).tick()
    row = await Scheduler(services).get(schedule["id"], services.identity.lookup(ACTOR))
    assert row["status"] == "FAILED"
    assert not await services.db.all("SELECT id FROM agent_runtime.runs")


async def test_scheduled_workflow_cannot_broaden_to_write_tool(services):
    schedule = await job(services)
    await Scheduler(services).tick()
    item = await occurrence(services, schedule)
    from jsd_aird_agents.contracts import IntentPlan, Step

    services.model.adapter = ScriptedModel(
        {
            "plan": IntentPlan(
                steps=[
                    Step(id="read", kind="FACT_QUERY", tool="list_project_tasks"),
                    Step(
                        id="write",
                        kind="ACTION_PROPOSAL",
                        tool="update_project_task",
                        depends_on=["read"],
                        arguments={"planned_date": "2026-11-10"},
                    ),
                ]
            )
        }
    )
    await Worker(services).tick()
    run = await services.store.run(str(item["run_id"]))
    assert run["error"]["code"] == "PACK_TOOL_FORBIDDEN"
    assert not await services.db.all("SELECT id FROM mock_domain.actions")


async def test_call_budget_is_enforced_inside_run_and_stops_future_occurrences(services):
    schedule = await job(services, model_call_budget=1)
    await Scheduler(services).tick()
    item = await occurrence(services, schedule)
    await Worker(services).tick()
    run = await services.store.run(str(item["run_id"]))
    assert run["error"]["code"] == "BUDGET_EXCEEDED" and run["model_calls"] == 1
    await Scheduler(services).tick()
    assert (await Scheduler(services).get(schedule["id"], services.identity.lookup(ACTOR)))[
        "status"
    ] == "EXHAUSTED"


async def test_retry_resumes_original_checkpoint_with_attempt_cap(services):
    schedule = await job(services, max_runs=1, max_attempts=2)
    await Scheduler(services).tick()
    item = await occurrence(services, schedule)
    services.model.adapter = ScriptedModel(
        {"analysis": AgentError("MODEL_TIMEOUT", "timeout", retryable=True)}
    )
    await Worker(services).tick()
    assert (await services.store.run(str(item["run_id"])))["status"] == "FAILED"
    await Scheduler(services).tick()
    assert (await occurrence(services, schedule))["attempts"] == 2
    services.model.adapter = ScriptedModel()
    await Worker(services).tick()
    await Scheduler(services).tick()
    assert (await occurrence(services, schedule))["status"] == "SUCCEEDED"
    assert len(await services.db.all("SELECT id FROM agent_runtime.runs")) == 1


async def test_schedule_api_rejects_identity_override_and_naive_time(client):
    base = {"pack_id": "project", "job_id": "daily-project-health-check", "scope_id": PROJECT}
    for addition in ({"actor_id": "forged"}, {"first_fire_at": "2026-10-22T09:00:00"}):
        response = await client.post(
            PREFIX + "/schedules",
            headers={**AUTH, "Idempotency-Key": str(uuid4())},
            json={**base, **addition},
        )
        assert response.status_code == 422
    response = await client.post(
        PREFIX + "/schedules", headers={**AUTH, "Idempotency-Key": "http"}, json=base
    )
    assert response.status_code == 201, response.text


async def test_registered_write_job_waits_for_approval_across_scheduler_restart(services):
    from conftest import approve

    from jsd_aird_agents.adapters.fixtures import TASK
    from jsd_aird_agents.app.platform.packs import JobDeclaration

    binding = services.packs.require("project")
    definition = JobDeclaration(
        id="approved-deadline-followup",
        workflow="analyze-project-delay",
        request="分析延期并将耐候测试验证截止日期调整到2026-11-10",
    )
    binding.manifest = binding.manifest.model_copy(
        update={"scheduled_jobs": [*binding.manifest.scheduled_jobs, definition]}
    )
    schedule = await Scheduler(services).create(
        services.identity.lookup(ACTOR),
        CreateSchedule(pack_id="project", job_id=definition.id, scope_id=PROJECT, max_runs=1),
        "write-job",
    )
    await Scheduler(services).tick()
    item = await occurrence(services, schedule)
    await Worker(services).tick()
    waiting = await services.store.run(str(item["run_id"]))
    assert waiting["status"] == "WAITING_APPROVAL", waiting["error"]
    for _ in range(2):
        await Scheduler(services).tick()
        await Worker(services).tick()
    assert (await services.db.one("SELECT version FROM mock_domain.tasks WHERE id=%s", (TASK,)))[
        "version"
    ] == 0
    assert len(await services.db.all("SELECT id FROM agent_runtime.runs")) == 1
    action = await services.domain.get(
        waiting["interrupt"]["value"]["action_id"], services.identity.lookup(ACTOR)
    )
    await approve(services, action)
    await asyncio.gather(Worker(services).tick(), Worker(services).tick())
    await Scheduler(services).tick()
    final = await services.store.run(waiting["id"])
    assert final["status"] == "SUCCEEDED", final["error"]
    assert len(final["result"]["receipts"]) == 1
    assert (await services.db.one("SELECT version FROM mock_domain.tasks WHERE id=%s", (TASK,)))[
        "version"
    ] == 1
