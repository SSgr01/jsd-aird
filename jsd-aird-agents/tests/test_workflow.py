import asyncio
from dataclasses import replace

import pytest
from conftest import approve, awaiting_action, new_run

from jsd_aird_agents.adapters.fixtures import ACTOR, SECOND_PROJECT, TASK
from jsd_aird_agents.contracts import AnswerDocument, Claim, IntentPlan, Step
from jsd_aird_agents.errors import AgentError
from jsd_aird_agents.models.adapters import ScriptedModel
from jsd_aird_agents.services import Services
from jsd_aird_agents.worker import Worker

pytestmark = pytest.mark.integration


async def test_end_to_end_multistep_approval_and_actual_state(services):
    run, action = await awaiting_action(services)
    assert (await services.db.one("SELECT version FROM mock_domain.tasks WHERE id=%s", (TASK,)))[
        "version"
    ] == 0
    await approve(services, action)
    await Worker(services).tick()
    result = await services.store.snapshot(run["id"], services.identity.lookup(ACTOR))
    assert result["status"] == "SUCCEEDED", result["error"]
    assert all(s["status"] == "SUCCEEDED" for s in result["steps"])
    assert result["result"]["receipts"][0]["actual"]["planned_date"] == "2026-11-10"
    assert result["result"]["citations"]
    assert result["result"]["receipts"][0]["verified"]
    events = await services.store.events(run["id"])
    assert [e["kind"] for e in events].index("action.executed") < [e["kind"] for e in events].index(
        "answer.delta"
    )


async def test_independent_reads_execute_concurrently(services, monkeypatch):
    original = services.domain.read
    active = maximum = 0

    async def instrumented(*args):
        nonlocal active, maximum
        active += 1
        maximum = max(active, maximum)
        try:
            await asyncio.sleep(0.03)
            return await original(*args)
        finally:
            active -= 1

    monkeypatch.setattr(services.domain, "read", instrumented)
    run = await new_run(services)
    await Worker(services).tick()
    assert (await services.store.run(run["id"]))["status"] == "SUCCEEDED"
    assert maximum >= 2


async def test_entity_clarification_resumes_same_run_after_service_restart(services, settings):
    run = await new_run(services, project_id=None)
    await Worker(services).tick()
    waiting = await services.store.run(run["id"])
    assert waiting["status"] == "WAITING_CLARIFICATION"
    assert len(waiting["interrupt"]["value"]["candidates"]) == 2
    await services.close()
    restored = Services(settings)
    await restored.open()
    try:
        await restored.store.enqueue_resume(
            run["id"], restored.identity.lookup(ACTOR), {"kind": "clarification", "answer": SECOND_PROJECT}
        )
        await Worker(restored).tick()
        final = await restored.store.run(run["id"])
        assert final["status"] == "SUCCEEDED", final["error"]
        assert final["project_id"] == SECOND_PROJECT
    finally:
        await restored.close()


async def test_missing_date_clarification_then_approval(services):
    run = await new_run(services, request="分析延期并调整任务截止日期")
    await Worker(services).tick()
    assert (await services.store.run(run["id"]))["status"] == "WAITING_CLARIFICATION"
    await services.store.enqueue_resume(
        run["id"], services.identity.lookup(ACTOR), {"kind": "clarification", "answer": "2026-11-10"}
    )
    await Worker(services).tick()
    assert (await services.store.run(run["id"]))["status"] == "WAITING_APPROVAL"


async def test_approval_and_checkpoint_survive_restart(services, settings):
    run, action = await awaiting_action(services)
    await approve(services, action)
    await services.close()
    restored = Services(settings)
    await restored.open()
    try:
        await Worker(restored).tick()
        final = await restored.store.run(run["id"])
        assert final["status"] == "SUCCEEDED", final["error"]
        assert final["result"]["receipts"][0]["action_id"] == action["id"]
    finally:
        await restored.close()


async def test_stale_running_projection_reconciles_existing_interrupt(services):
    run, action = await awaiting_action(services)
    await services.db.execute(
        """UPDATE agent_runtime.runs SET status='RUNNING',worker_id='dead-worker',
        lease_until=now()-interval '10 seconds' WHERE id=%s""",
        (run["id"],),
    )
    await Worker(services).tick()
    recovered = await services.store.run(run["id"])
    assert recovered["status"] == "WAITING_APPROVAL"
    assert recovered["interrupt"]["value"]["action_id"] == action["id"]
    assert (await services.db.one("SELECT count(*) AS n FROM mock_domain.actions"))["n"] == 1


async def test_crash_after_action_commit_before_checkpoint_does_not_repeat_write(services):
    run, action = await awaiting_action(services)
    await approve(services, action)
    crashes = 0

    async def fail_once(receipt):
        nonlocal crashes
        crashes += 1
        if crashes == 1:
            raise AgentError("RECOVERY_PROBE", "模拟写入已提交、节点尚未 checkpoint", 500, retryable=True)

    services.workflow.after_action = fail_once
    await Worker(services).tick()
    assert (await services.store.run(run["id"]))["status"] == "FAILED"
    assert (await services.db.one("SELECT version FROM mock_domain.tasks WHERE id=%s", (TASK,)))[
        "version"
    ] == 1
    await services.store.enqueue_resume(run["id"], services.identity.lookup(ACTOR), {"kind": "retry"})
    await Worker(services).tick()
    final = await services.store.run(run["id"])
    assert final["status"] == "SUCCEEDED", final["error"]
    assert (await services.db.one("SELECT version FROM mock_domain.tasks WHERE id=%s", (TASK,)))[
        "version"
    ] == 1
    assert (
        await services.db.one("SELECT count(*) AS n FROM agent_runtime.events WHERE kind='action.executed'")
    )["n"] == 1


async def test_completed_checkpoint_reprojects_without_new_model_calls(services, monkeypatch):
    run = await new_run(services)
    worker = Worker(services)

    async def broken(*args):
        raise AgentError("PROJECTION_FAULT", "模拟最终投影前故障", 500, retryable=True)

    monkeypatch.setattr(worker, "_complete", broken)
    await worker.tick()
    failed = await services.store.run(run["id"])
    assert failed["status"] == "FAILED"
    calls = failed["model_calls"]
    await services.store.enqueue_resume(run["id"], services.identity.lookup(ACTOR), {"kind": "retry"})
    await Worker(services).tick()
    final = await services.store.run(run["id"])
    assert final["status"] == "SUCCEEDED" and final["model_calls"] == calls


async def test_two_workers_do_not_duplicate_actions(services):
    run, action = await awaiting_action(services)
    await approve(services, action)
    await asyncio.gather(Worker(services).tick(), Worker(services).tick())
    final = await services.store.run(run["id"])
    assert final["status"] == "SUCCEEDED", final["error"]
    assert (await services.db.one("SELECT version FROM mock_domain.tasks WHERE id=%s", (TASK,)))[
        "version"
    ] == 1


async def test_rejection_returns_analysis_and_cancellation_preserves_receipt(services):
    run, action = await awaiting_action(services)
    await approve(services, action, "REJECTED")
    await Worker(services).tick()
    final = await services.store.run(run["id"])
    assert final["status"] == "SUCCEEDED", final["error"]
    assert final["result"]["receipts"] == [] and final["result"]["warnings"]
    assert (await services.db.one("SELECT version FROM mock_domain.tasks WHERE id=%s", (TASK,)))[
        "version"
    ] == 0


async def test_new_run_resets_transient_state_but_keeps_conversation(services):
    first = await new_run(services)
    await Worker(services).tick()
    second = await new_run(services, conversation_id=first["conversation_id"])
    await Worker(services).tick()
    final = await services.store.snapshot(second["id"], services.identity.lookup(ACTOR))
    assert final["status"] == "SUCCEEDED", final["error"]
    assert all(e["run_id"] == second["id"] for e in final["result"]["citations"])
    snapshot = await services.graph.aget_state({"configurable": {"thread_id": first["conversation_id"]}})
    assert len(snapshot.values["messages"]) == 4
    assert not snapshot.values["pending_action_id"]


async def test_invalid_citations_never_reach_sse_body(services):
    bad = AnswerDocument(claims=[Claim(text="无依据结论", evidence_ids=["ev_fabricated"])])
    services.model.adapter = ScriptedModel({"answer": bad})
    run = await new_run(services)
    await Worker(services).tick()
    final = await services.store.run(run["id"])
    assert final["status"] == "FAILED" and final["error"]["code"] == "CITATION_INVALID"
    assert not any(e["kind"] == "answer.delta" for e in await services.store.events(run["id"]))
    assert len([c for c in services.model.adapter.calls if c[0] == "answer"]) == 2


async def test_invalid_citation_can_be_repaired_once(services):
    original = services.model.adapter
    bad = AnswerDocument(claims=[Claim(text="修复前", evidence_ids=["ev_fake"])])
    calls = 0

    async def generate(kind, payload, schema):
        nonlocal calls
        if kind == "answer":
            calls += 1
            if calls == 1:
                return bad, {"reported": False}
        return await original.generate(kind, payload, schema)

    class Repairable:
        pass

    adapter = Repairable()
    adapter.generate = generate
    services.model.adapter = adapter
    run = await new_run(services)
    await Worker(services).tick()
    assert (await services.store.run(run["id"]))["status"] == "SUCCEEDED"
    assert calls == 2


async def test_multiple_dependent_writes_have_separate_approvals_and_versions(services):
    plan = IntentPlan(
        steps=[
            Step(id="tasks", kind="FACT_QUERY", tool="list_project_tasks"),
            Step(
                id="first",
                kind="ACTION_PROPOSAL",
                tool="update_project_task",
                depends_on=["tasks"],
                arguments={"task_ref": "耐候测试验证", "planned_date": "2026-11-10"},
            ),
            Step(
                id="second",
                kind="ACTION_PROPOSAL",
                tool="update_project_task",
                depends_on=["first"],
                arguments={
                    "task_id": {"$ref": "first.data.id"},
                    "expected_version": {"$ref": "first.data.version"},
                    "planned_date": "2026-11-20",
                },
            ),
        ]
    )
    services.model.adapter = ScriptedModel({"plan": plan})
    run = await new_run(services)
    await Worker(services).tick()
    first = await services.store.run(run["id"])
    first_action = await services.domain.get(
        first["interrupt"]["value"]["action_id"], services.identity.lookup(ACTOR)
    )
    await approve(services, first_action)
    await Worker(services).tick()
    second = await services.store.run(run["id"])
    assert second["status"] == "WAITING_APPROVAL", second["error"]
    second_action = await services.domain.get(
        second["interrupt"]["value"]["action_id"], services.identity.lookup(ACTOR)
    )
    assert first_action["id"] != second_action["id"] and second_action["expected_version"] == 1
    await approve(services, second_action)
    await Worker(services).tick()
    final = await services.store.run(run["id"])
    assert final["status"] == "SUCCEEDED", final["error"]
    assert len(final["result"]["receipts"]) == 2
    task = await services.db.one("SELECT * FROM mock_domain.tasks WHERE id=%s", (TASK,))
    assert task["version"] == 2 and str(task["planned_date"]) == "2026-11-20"


async def test_disabled_fixture_actor_finishes_without_perpetual_reclaim(services):
    run = await new_run(services)
    del services.identity.principals[ACTOR]
    await Worker(services).tick()
    final = await services.store.run(run["id"])
    assert final["status"] == "FAILED" and final["error"]["code"] == "AUTH_REQUIRED"
    assert not await Worker(services).tick()


async def test_cancel_running_model_stops_future_work(services):
    entered = asyncio.Event()
    original = services.model.adapter

    class SlowModel:
        async def generate(self, kind, payload, schema):
            entered.set()
            await asyncio.sleep(30)
            return await original.generate(kind, payload, schema)

    services.model.adapter = SlowModel()
    run = await new_run(services)
    worker_task = asyncio.create_task(Worker(services).tick())
    await asyncio.wait_for(entered.wait(), 5)
    await services.store.cancel(run["id"], services.identity.lookup(ACTOR))
    await asyncio.wait_for(worker_task, 5)
    assert (await services.store.run(run["id"]))["status"] == "CANCELLED"
    assert not await services.db.all("SELECT * FROM mock_domain.actions")


async def test_model_budget_is_durable(services):
    services.settings.max_model_calls = 1
    run = await new_run(services)
    await Worker(services).tick()
    final = await services.store.run(run["id"])
    assert final["status"] == "FAILED" and final["error"]["code"] == "BUDGET_EXCEEDED"
    assert final["model_calls"] == 1


async def test_permission_revoked_during_pause_prevents_write(services):
    run, action = await awaiting_action(services)
    await approve(services, action)
    services.identity.principals[ACTOR] = replace(
        services.identity.lookup(ACTOR), permissions=("project.view", "ai.use")
    )
    await Worker(services).tick()
    final = await services.store.run(run["id"])
    assert final["status"] == "FAILED" and final["error"]["code"] == "PERMISSION_DENIED"
    assert (await services.db.one("SELECT version FROM mock_domain.tasks WHERE id=%s", (TASK,)))[
        "version"
    ] == 0


async def test_plan_repair_receives_rejected_plan_and_validates_parameters(services):
    bad = IntentPlan(
        steps=[
            Step(id="tasks", kind="FACT_QUERY", tool="list_project_tasks", arguments={"user_id": "invented"})
        ]
    )
    good = IntentPlan(steps=[Step(id="tasks", kind="FACT_QUERY", tool="list_project_tasks")])
    model = ScriptedModel({"plan": [bad, good]})
    services.model.adapter = model
    run = await new_run(services)
    await Worker(services).tick()
    assert (await services.store.run(run["id"]))["status"] == "SUCCEEDED"
    assert model.calls[1][1]["rejected_plan"] == bad.model_dump(mode="json")
    assert "user_id" in model.calls[1][1]["validation_error"]


async def test_repeated_invalid_plan_fails_without_tool_or_business_side_effects(services):
    services.model.adapter = ScriptedModel(
        {
            "plan": IntentPlan(
                steps=[
                    Step(
                        id="tasks",
                        kind="FACT_QUERY",
                        tool="list_project_tasks",
                        arguments={"user_id": "invented"},
                    )
                ]
            )
        }
    )
    run = await new_run(services)
    await Worker(services).tick()
    final = await services.store.run(run["id"])
    assert final["status"] == "FAILED" and final["error"]["code"] == "PLAN_INVALID"
    assert final["model_calls"] == 2 and final["tool_calls"] == 0
    assert not await services.db.all("SELECT * FROM mock_domain.actions")


async def test_waiting_approval_expiry_is_reconciled_without_a_client_request(services):
    run, action = await awaiting_action(services)
    await services.db.execute(
        "UPDATE mock_domain.actions SET expires_at=now()-interval '1 second' WHERE id=%s", (action["id"],)
    )
    await Worker(services).tick()
    final = await services.store.run(run["id"])
    assert final["status"] == "FAILED" and final["error"]["code"] == "APPROVAL_EXPIRED"
    assert (await services.db.one("SELECT version FROM mock_domain.tasks WHERE id=%s", (TASK,)))[
        "version"
    ] == 0
