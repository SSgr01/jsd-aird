import asyncio
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from uuid import uuid4
from zoneinfo import ZoneInfo

import pytest
from conftest import AUTH, FOREIGN, REVIEWER, awaiting_action

from jsd_aird_agents.adapters.fixtures import ACTOR, APPROVER, HIDDEN_PROJECT, SECOND_PROJECT
from jsd_aird_agents.api import PREFIX
from jsd_aird_agents.app.platform.monitoring.contracts import MonitorOptions
from jsd_aird_agents.app.platform.scheduler import CreateSchedule, Scheduler
from jsd_aird_agents.app.platform.triggers import TriggerSpec, next_fire
from jsd_aird_agents.app.verticals.project.monitoring_fixtures import OVERDUE, seed_monitoring
from jsd_aird_agents.contracts import CreateRun
from jsd_aird_agents.worker import Worker


class Clock:
    def __init__(self, value="2026-10-22T02:00:00+00:00"):
        self.value = datetime.fromisoformat(value)

    def now(self):
        return self.value


async def setup(services):
    services.clock = Clock()
    services.store.clock = services.clock
    await seed_monitoring(services.db, services.clock.now())


async def monitor(
    services, workflow="project-progress-inspection", options=None, scope=SECOND_PROJECT, execute=True
):
    run = await services.store.create_run(
        services.identity.lookup(ACTOR),
        CreateRun(request="执行巡检", project_id=scope, workflow_id=workflow, monitoring=options),
        str(uuid4()),
    )
    if execute:
        await Worker(services).tick()
        run = await services.store.run(run["id"])
        assert run["status"] == "SUCCEEDED", run["error"]
    return run


@pytest.mark.integration
@pytest.mark.parametrize(
    "workflow,rule",
    [
        ("project-progress-inspection", "progress.deviation"),
        ("project-task-followup", "task.stalled"),
        ("project-milestone-precheck", "milestone.readiness"),
        ("project-weekly-report", "task.overdue"),
    ],
)
async def test_first_four_reports_and_evidence(services, workflow, rule):
    await setup(services)
    run = await monitor(services, workflow)
    report = run["result"]["monitoring"]
    assert any(o["rule_id"] == rule and o["status"] == "FAIL" for o in report["observations"])
    from jsd_aird_agents.app.platform.evaluation import evaluate

    assert evaluate(services.packs.require("project").manifest, workflow + "-fixture", run["result"])[
        "passed"
    ]
    assert (await services.store.events(run["id"]))[-1]["kind"] == "run.succeeded"
    assert run["result"]["publication_status"] == "DRAFT" and not run["result"]["receipts"]
    assert all(c["evidence_ids"] for c in run["result"]["claims"])
    assert run["tool_calls"] == 1 and run["model_calls"] == 0
    assert await services.monitoring.inbox(services.identity.lookup(ACTOR))
    batch = await services.db.one(
        "SELECT * FROM agent_runtime.monitoring_batches WHERE run_id=%s", (run["id"],)
    )
    assert batch["projected_at"]
    assert (
        len(
            await services.db.all(
                "SELECT * FROM agent_runtime.steps WHERE run_id=%s AND status='SUCCEEDED'", (run["id"],)
            )
        )
        == 5
    )
    if workflow == "project-weekly-report":
        assert not report["baseline_available"] and report["overdue_change"] is None
        assert report["counts"]["completed_this_week"] == 1
    if workflow == "project-milestone-precheck":
        assert len(report["observations"][0]["metrics"]["gaps"]) == 2


@pytest.mark.integration
async def test_dedup_resolve_reopen_and_quiet_hours(services):
    await setup(services)
    options = MonitorOptions(quiet_start="09:00", quiet_end="11:00", explain_with_model=False)
    await monitor(services, options=options)
    assert not await services.monitoring.inbox(services.identity.lookup(ACTOR))
    initial = await services.db.all("SELECT * FROM agent_runtime.notification_outbox")
    await monitor(services, options=options)
    assert len(await services.db.all("SELECT * FROM agent_runtime.notification_outbox")) == len(initial)
    services.clock.value += timedelta(hours=2)
    await asyncio.gather(services.monitoring.dispatch(), services.monitoring.dispatch())
    assert len(await services.monitoring.inbox(services.identity.lookup(ACTOR))) == len(initial)
    await services.db.execute(
        "UPDATE mock_domain.tasks SET status='DONE',version=version+1 WHERE id=%s", (OVERDUE,)
    )
    services.clock.value += timedelta(minutes=1)
    await monitor(services, options=MonitorOptions(explain_with_model=False))
    finding = await services.db.one(
        "SELECT * FROM agent_runtime.monitoring_findings WHERE resource_id=%s AND rule_id='task.overdue'",
        (OVERDUE,),
    )
    assert finding["status"] == "RESOLVED"
    await services.db.execute(
        "UPDATE mock_domain.tasks SET status='IN_PROGRESS',version=version+1 WHERE id=%s", (OVERDUE,)
    )
    services.clock.value += timedelta(minutes=1)
    await monitor(services, options=MonitorOptions(explain_with_model=False))
    finding = await services.db.one(
        "SELECT * FROM agent_runtime.monitoring_findings WHERE resource_id=%s AND rule_id='task.overdue'",
        (OVERDUE,),
    )
    assert finding["status"] == "OPEN" and finding["episode"] == 2


@pytest.mark.integration
async def test_weekly_baseline_and_unknown_not_healthy(services):
    await setup(services)
    services.clock.value = datetime(2026, 10, 18, 12, tzinfo=timezone.utc)
    baseline = await monitor(services, options=MonitorOptions(explain_with_model=False))
    services.clock.value += timedelta(days=4)
    run = await monitor(services, "project-weekly-report")
    assert run["result"]["monitoring"]["baseline_available"]
    data = await services.store.snapshot(run["id"], services.identity.lookup(ACTOR))
    assert any(
        e["source_type"] == "MONITORING_BASELINE"
        and e["run_id"] == run["id"]
        and e["resource_id"] == baseline["id"]
        for e in data["evidence"]
    )
    assert any(o["status"] == "UNKNOWN" for o in run["result"]["monitoring"]["observations"])


@pytest.mark.integration
async def test_approval_reminder_does_not_decide_and_filters_recipients(services):
    await setup(services)
    run, action = await awaiting_action(services)
    # Align logical test clock to authority timestamps; no test manipulates approval into valid state.
    services.clock.value = datetime.fromisoformat(action["created_at"]) + timedelta(minutes=6)
    report = await monitor(services, "project-approval-reminder", scope=action["project_id"])
    assert any(
        o["rule_id"] == "approval.waiting" and o["status"] == "FAIL"
        for o in report["result"]["monitoring"]["observations"]
    )
    notices = await services.monitoring.inbox(services.identity.lookup(APPROVER))
    assert len(notices) == 1 and notices[0]["payload"]["action_id"] == action["id"]
    assert "run_id" not in notices[0]["payload"]
    ledger = await services.domain.get(action["id"], services.identity.lookup(ACTOR))
    assert ledger["approval_status"] == "PENDING" and ledger["receipt"] is None
    assert (await services.store.run(run["id"]))["status"] == "WAITING_APPROVAL"


@pytest.mark.integration
async def test_notification_acl_and_revocation(services, client):
    await setup(services)
    await monitor(services)
    own = (await client.get(PREFIX + "/notifications", headers=AUTH)).json()["notifications"]
    assert own
    assert (await client.get(PREFIX + "/notifications", headers=FOREIGN)).json()["notifications"] == []
    assert (
        await client.post(PREFIX + f"/notifications/{own[0]['id']}/read", headers=REVIEWER)
    ).status_code == 404
    assert (
        await client.post(PREFIX + f"/notifications/{own[0]['id']}/read", headers=AUTH)
    ).status_code == 200
    assert (
        await client.get(PREFIX + f"/monitoring/findings?scope_id={HIDDEN_PROJECT}", headers=AUTH)
    ).status_code == 403
    services.identity.principals[ACTOR] = replace(services.identity.lookup(ACTOR), projects=())
    assert (await client.get(PREFIX + "/notifications", headers=AUTH)).json()["notifications"] == []


@pytest.mark.integration
async def test_staged_commit_crash_recovery_has_one_projection(services):
    await setup(services)
    attempts = []

    async def crash(run_id):
        attempts.append(run_id)
        raise RuntimeError("after durable staging before checkpoint")

    services.monitoring.after_commit = crash
    run = await monitor(services, execute=False)
    await Worker(services).tick()
    failed = await services.store.run(run["id"])
    assert failed["status"] == "FAILED"
    assert not await services.db.all("SELECT * FROM agent_runtime.monitoring_findings")
    assert not await services.monitoring.inbox(services.identity.lookup(ACTOR))
    services.monitoring.after_commit = None
    await services.store.enqueue_resume(run["id"], services.identity.lookup(ACTOR), {"kind": "retry"})
    await Worker(services).tick()
    assert (await services.store.run(run["id"]))["status"] == "SUCCEEDED"
    assert len(await services.db.all("SELECT * FROM agent_runtime.monitoring_batches")) == 1
    before = len(await services.db.all("SELECT * FROM agent_runtime.notifications"))
    await asyncio.gather(services.monitoring.dispatch(), services.monitoring.dispatch())
    assert len(await services.db.all("SELECT * FROM agent_runtime.notifications")) == before


@pytest.mark.integration
async def test_schedule_defaults_cron_and_all_five_workflows(services):
    await setup(services)
    scheduler = Scheduler(services)
    principal = services.identity.lookup(ACTOR)
    for job in services.packs.require("project").manifest.scheduled_jobs:
        if not job.trigger:
            continue
        created = await scheduler.create(
            principal,
            CreateSchedule(
                pack_id="project",
                job_id=job.id,
                scope_id=SECOND_PROJECT,
                first_fire_at=services.clock.now(),
                max_runs=1,
                monitoring=MonitorOptions(explain_with_model=False),
            ),
            str(uuid4()),
        )
        assert created["trigger_spec"]["kind"] == "cron"
        await scheduler.tick()
        await Worker(services).tick()
        await scheduler.tick()
        snapshot = await scheduler.snapshot(created["id"], principal)
        assert snapshot["status"] == "EXHAUSTED"
        assert snapshot["occurrences"][0]["status"] == "SUCCEEDED"
    assert len(await services.db.all("SELECT * FROM agent_runtime.monitoring_batches")) == 5


def test_cron_timezone_holidays_weekly_dst_and_dates():
    spec = TriggerSpec(kind="cron", expression="30 8 * * mon-fri", skip_dates=["2026-10-23"])
    assert (
        next_fire(spec, datetime.fromisoformat("2026-10-22T01:00:00+00:00")).isoformat()
        == "2026-10-26T00:30:00+00:00"
    )
    weekly = TriggerSpec(kind="cron", expression="0 17 * * fri")
    assert (
        next_fire(weekly, datetime.fromisoformat("2026-10-22T01:00:00+00:00")).isoformat()
        == "2026-10-23T09:00:00+00:00"
    )
    spring = TriggerSpec(kind="cron", expression="30 2 * * *", timezone="America/New_York")
    assert (
        next_fire(spring, datetime.fromisoformat("2026-03-08T05:00:00+00:00")).date().isoformat()
        == "2026-03-09"
    )
    fall = TriggerSpec(kind="cron", expression="30 1 * * *", timezone="America/New_York")
    assert (
        next_fire(fall, datetime.fromisoformat("2026-11-01T05:30:00+00:00")).date().isoformat()
        == "2026-11-02"
    )
    once = TriggerSpec(kind="date", at="2026-10-22T00:00:00+00:00")
    assert next_fire(once, datetime.fromisoformat("2026-10-23T00:00:00+00:00")) is None
    with pytest.raises(ValueError):
        TriggerSpec(kind="cron", expression="0 0 * * 1")


@pytest.mark.integration
async def test_options_are_frozen_and_no_identity_override(services, client):
    await setup(services)
    payload = {
        "request": "巡检",
        "workflow_id": "project-task-followup",
        "project_id": SECOND_PROJECT,
        "monitoring": {"stalled_days": 3, "explain_with_model": False},
    }
    key = {**AUTH, "Idempotency-Key": "frozen"}
    created = await client.post(PREFIX + "/runs", headers=key, json=payload)
    assert created.status_code == 202
    services.clock.value += timedelta(days=1)
    duplicate = await client.post(PREFIX + "/runs", headers=key, json=payload)
    assert duplicate.json()["run_id"] == created.json()["run_id"]
    run = await services.store.run(created.json()["run_id"])
    assert run["monitoring"]["as_of"].startswith("2026-10-22")
    assert (
        await client.post(
            PREFIX + "/runs",
            headers={**AUTH, "Idempotency-Key": "forged"},
            json={**payload, "monitoring": {"actor_id": APPROVER}},
        )
    ).status_code == 422
    assert (
        await client.post(
            PREFIX + "/runs",
            headers={**AUTH, "Idempotency-Key": "audience"},
            json={**payload, "monitoring": {"notify_to": "approvers"}},
        )
    ).status_code == 403
    await services.store.cancel(run["id"], services.identity.lookup(ACTOR))
    await Worker(services).tick()
    assert not await services.monitoring.inbox(services.identity.lookup(ACTOR))


@pytest.mark.integration
@pytest.mark.parametrize("change", ["approved", "approver-revoked", "expired"])
async def test_pending_reminder_is_rechecked_at_delivery(services, change):
    await setup(services)
    _, action = await awaiting_action(services)
    services.clock.value = datetime.fromisoformat(action["created_at"]) + timedelta(seconds=10)
    local = services.clock.now().astimezone(ZoneInfo("Asia/Shanghai"))
    options = MonitorOptions(
        approval_wait_minutes=0,
        explain_with_model=False,
        quiet_start=(local - timedelta(minutes=1)).time().replace(tzinfo=None),
        quiet_end=(local + timedelta(minutes=2)).time().replace(tzinfo=None),
    )
    await monitor(services, "project-approval-reminder", options, action["project_id"])
    assert not await services.monitoring.inbox(services.identity.lookup(APPROVER))
    assert await services.db.all("SELECT * FROM agent_runtime.notification_outbox WHERE status='PENDING'")
    if change == "approved":
        await services.domain.decide(
            action["id"], services.identity.lookup(APPROVER), "APPROVED", action["request_hash"]
        )
    elif change == "approver-revoked":
        services.identity.principals[APPROVER] = replace(
            services.identity.lookup(APPROVER), can_approve=False
        )
    else:
        services.clock.value = datetime.fromisoformat(action["expires_at"])
    services.clock.value += timedelta(minutes=3)
    await services.monitoring.dispatch()
    assert not await services.monitoring.inbox(services.identity.lookup(APPROVER))
    suppressed = await services.db.all(
        "SELECT * FROM agent_runtime.notification_outbox WHERE status='SUPPRESSED'"
    )
    assert len(suppressed) == 1 and suppressed[0]["reason"] in {
        "APPROVAL_NO_LONGER_PENDING",
        "APPROVER_UNAUTHORIZED",
    }
    assert (await services.domain.get(action["id"], services.identity.lookup(ACTOR)))["receipt"] is None


@pytest.mark.integration
async def test_expired_approval_notified_only_to_requester(services):
    await setup(services)
    _, action = await awaiting_action(services)
    services.clock.value = datetime.fromisoformat(action["expires_at"]) + timedelta(seconds=1)
    await monitor(
        services, "project-approval-reminder", MonitorOptions(explain_with_model=False), action["project_id"]
    )
    assert not await services.monitoring.inbox(services.identity.lookup(APPROVER))
    notices = await services.monitoring.inbox(services.identity.lookup(ACTOR))
    assert len(notices) == 1 and notices[0]["payload"]["rule_id"] == "approval.expired"
    assert (await services.domain.get(action["id"], services.identity.lookup(ACTOR)))["receipt"] is None


@pytest.mark.integration
async def test_invalid_model_citation_never_publishes_findings_or_body(services):
    from jsd_aird_agents.app.verticals.project.model import ScriptedModel

    await setup(services)
    services.model.adapter = ScriptedModel(
        overrides={"answer": {"claims": [{"text": "非法引用", "evidence_ids": ["missing"]}]}}
    )
    run = await monitor(services, options=MonitorOptions(explain_with_model=True), execute=False)
    await Worker(services).tick()
    final = await services.store.run(run["id"])
    assert final["status"] == "FAILED" and final["error"]["code"] == "CITATION_INVALID"
    assert not await services.db.all("SELECT * FROM agent_runtime.monitoring_findings")
    assert not await services.db.all("SELECT * FROM agent_runtime.notifications")
    assert not await services.db.all("SELECT * FROM agent_runtime.events WHERE kind='answer.delta'")


@pytest.mark.integration
async def test_monitoring_skill_body_and_reference_remain_lazy(services):
    await setup(services)
    scanned = await services.skills.sync()
    assert not scanned["errors"]
    skill = next(s for s in await services.skills.catalog() if s["name"] == "project-proactive-monitoring")
    await services.skills.set_enabled(skill["name"], skill["sha256"], True)
    run = await monitor(services, options=MonitorOptions(explain_with_model=True))
    selection = await services.db.one(
        "SELECT selected FROM agent_runtime.skill_selections WHERE run_id=%s", (run["id"],)
    )
    assert selection["selected"] == [{"name": skill["name"], "sha256": skill["sha256"]}]
    assert not await services.db.all("SELECT * FROM agent_runtime.skill_loads WHERE run_id=%s", (run["id"],))


@pytest.mark.integration
async def test_date_trigger_runs_once_and_default_cron_waits(services):
    await setup(services)
    scheduler = Scheduler(services)
    principal = services.identity.lookup(ACTOR)
    command = CreateSchedule(
        pack_id="project",
        job_id="daily-task-followup",
        scope_id=SECOND_PROJECT,
        monitoring=MonitorOptions(explain_with_model=False),
    )
    daily = await scheduler.create(principal, command, "daily")
    assert datetime.fromisoformat(daily["next_fire_at"]) > services.clock.now()
    once = await scheduler.create(
        principal,
        command.model_copy(
            update={"trigger": TriggerSpec(kind="date", at=services.clock.now() + timedelta(minutes=1))}
        ),
        "once",
    )
    await scheduler.tick()
    assert not await services.db.all("SELECT * FROM agent_runtime.schedule_occurrences")
    services.clock.value += timedelta(minutes=1)
    await scheduler.tick()
    await Worker(services).tick()
    await scheduler.tick()
    result = await scheduler.snapshot(once["id"], principal)
    assert result["status"] == "EXHAUSTED" and result["next_fire_at"] is None
    assert len(result["occurrences"]) == 1


@pytest.mark.parametrize(
    "field,value",
    [("stalled_days", 0), ("milestone_days", 100), ("timezone", "unknown"), ("quiet_start", "22:00")],
)
def test_monitoring_rejects_invalid_policy(field, value):
    with pytest.raises(ValueError):
        MonitorOptions(**{field: value})


@pytest.mark.integration
async def test_different_thresholds_cannot_close_another_policy_finding(services):
    await setup(services)
    await monitor(services, "project-task-followup", MonitorOptions(stalled_days=3, explain_with_model=False))
    services.clock.value += timedelta(minutes=1)
    await monitor(services, "project-task-followup", MonitorOptions(stalled_days=7, explain_with_model=False))
    rows = await services.db.all(
        "SELECT * FROM agent_runtime.monitoring_findings WHERE rule_id='task.stalled' AND resource_id=%s",
        (OVERDUE,),
    )
    assert len(rows) == 1 and rows[0]["status"] == "OPEN"
    assert rows[0]["payload"]["rule_parameters"]["stalled_days"] == 3
