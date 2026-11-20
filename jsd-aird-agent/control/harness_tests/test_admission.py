"""Real PostgreSQL admission races, fairness and crash recovery; no model calls."""

import asyncio
from types import SimpleNamespace
from uuid import uuid4

import pytest
from fastapi import HTTPException

from aird_agent.core.admission import RunAdmission
from aird_agent.core.state import initial_run_state


@pytest.fixture
async def admission(store, settings):
    settings = settings.model_copy(update={"max_running_runs": 2, "max_running_runs_per_user": 1})
    service = SimpleNamespace(store=store, settings=settings)
    calls = []

    async def authorize(*args, **kwargs):
        pass

    async def dispatch(principal, rid):
        calls.append(("dispatch", rid))

    async def runtime(rid, action, body=None):
        calls.append((action, rid, body))
        return {"running": False, "pending": False}

    service.authorized_topic = authorize
    service._dispatch_run = dispatch
    service.runtime = runtime
    value = RunAdmission(service)
    value.calls = calls
    value.seeded = []
    try:
        yield value
    finally:
        await asyncio.gather(*value.tasks, return_exceptions=True)
        async with store.pool.connection() as conn:
            # This module uses its own database and runs serially. Preserve
            # records while relinquishing only its synthetic run leases.
            for rid in value.seeded:
                await conn.execute(
                    "UPDATE agent_runtime.harness_admission SET state='RELEASED' WHERE run_id=%s", (rid,)
                )


async def queued(admission, principal, origin="native"):
    if not hasattr(admission, "seeded"):
        admission.seeded = []
    async with admission.store.transaction(principal, str(uuid4())) as conn:
        run = await admission.store.create(
            principal,
            "run",
            {
                **initial_run_state(principal),
                "request": "Read the retained original request",
                "profile": "agent",
                "dsh_session_id": "session-" + str(uuid4()),
            },
            conn,
        )
    admission.seeded.append(run["id"])
    await admission.enqueue(principal, run, origin=origin)
    return run


async def finish(admission, principal, rid):
    async with admission.store.transaction(principal, rid) as conn:
        run = await admission.store.get(principal, "run", rid, conn)
        run["status"] = "SUCCEEDED"
        await admission.store.update(principal, "run", run, conn)
    await admission.release(principal, rid, stopped=True)


async def test_busy_requests_really_wait_then_auto_dispatch_once(admission, principal):
    first = await queued(admission, principal, "api")
    second = await queued(admission, principal, "api")
    await admission.cycle()
    await asyncio.gather(*admission.tasks)
    assert admission.calls == [("dispatch", first["id"])]
    assert (await admission.state(principal, second["id"]))["admission"] == "QUEUED"
    assert (await admission.store.get(principal, "run", second["id"]))["started_at"] is None
    await finish(admission, principal, first["id"])
    restarted = RunAdmission(admission.service)
    await restarted.recover()
    await restarted.cycle()
    await asyncio.gather(*restarted.tasks)
    assert admission.calls == [("dispatch", first["id"]), ("dispatch", second["id"])]
    await restarted.cycle()
    await asyncio.gather(*restarted.tasks)
    assert len(admission.calls) == 2


async def test_two_workers_never_oversubscribe_or_dispatch_twice(admission, principal):
    owners = [principal.model_copy(update={"actor_id": uuid4()}) for _ in range(4)]
    runs = [await queued(admission, p, "api") for p in owners]
    other = RunAdmission(admission.service)
    await asyncio.gather(admission.cycle(), other.cycle())
    await asyncio.gather(*admission.tasks, *other.tasks)
    states = [await admission.state(p, r["id"]) for p, r in zip(owners, runs)]
    assert sum(s["admission"] == "GRANTED" for s in states) == 2
    assert len(admission.calls) == 2
    assert len({call[1] for call in admission.calls}) == 2


async def test_background_reports_reserve_same_user_interactive_capacity_without_preemption(admission, principal):
    admission.settings = admission.settings.model_copy(update={"max_running_runs": 4, "max_running_runs_per_user": 2})
    background = principal.model_copy(update={"scope": "research-briefings", "automation_grant_id": str(uuid4())})
    first = await queued(admission, background)
    second = await queued(admission, background)
    interactive = await queued(admission, principal)
    assert await admission.grant(background, first["id"])
    assert not await admission.grant(background, second["id"])
    assert await admission.grant(principal, interactive["id"])
    assert (await admission.state(background, first["id"]))["admission"] == "GRANTED"
    await finish(admission, principal, interactive["id"])
    assert not await admission.grant(background, second["id"])
    await finish(admission, background, first["id"])
    assert await admission.grant(background, second["id"])


async def test_background_global_reservation_is_atomic_across_workers(admission, principal):
    admission.settings = admission.settings.model_copy(update={"max_running_runs": 4, "max_running_runs_per_user": 2})
    owners = [principal.model_copy(update={"organization_id": uuid4(), "actor_id": uuid4(),
        "scope": "research-briefings", "automation_grant_id": str(uuid4())}) for _ in range(4)]
    reports = [await queued(admission, owner) for owner in owners]
    other = RunAdmission(admission.service)
    other.settings = admission.settings
    await asyncio.gather(admission.cycle(), other.cycle())
    await asyncio.gather(*admission.tasks, *other.tasks)
    states = [await admission.state(owner, run["id"]) for owner, run in zip(owners, reports)]
    assert sum(state["admission"] == "GRANTED" for state in states) == 3
    interactive = await queued(admission, principal)
    assert await admission.grant(principal, interactive["id"])
    assert sum(state["admission"] == "GRANTED" for state in states) + 1 == 4


async def test_single_slot_still_runs_background_reports_serially(admission, principal):
    admission.settings = admission.settings.model_copy(update={"max_running_runs": 1})
    background = principal.model_copy(update={"scope": "research-briefings", "automation_grant_id": str(uuid4())})
    report = await queued(admission, background)
    interactive = await queued(admission, principal)
    assert await admission.grant(background, report["id"])
    assert not await admission.grant(principal, interactive["id"])
    await finish(admission, background, report["id"])
    assert await admission.grant(principal, interactive["id"])


async def test_organization_user_round_robin_and_fifo_survive_restart(admission, principal):
    admission.settings = admission.settings.model_copy(update={"max_running_runs": 1})
    p2 = principal.model_copy(update={"actor_id": uuid4()})
    p3 = principal.model_copy(update={"organization_id": uuid4(), "actor_id": uuid4()})
    a = await queued(admission, principal)
    b = await queued(admission, principal)
    c = await queued(admission, p2)
    d = await queued(admission, p3)
    order = []
    for owner, expected in [(principal, a), (p3, d), (p2, c), (principal, b)]:
        await admission.cycle()
        state = await admission.state(owner, expected["id"])
        assert state["admission"] == "GRANTED"
        order.append(expected["id"])
        await finish(admission, owner, expected["id"])
        admission.worker_id = str(uuid4())
    assert order == [a["id"], d["id"], c["id"], b["id"]]


async def test_request_deduplication_cancel_and_owner_isolation(admission, principal):
    run = await queued(admission, principal)
    await asyncio.gather(*[admission.enqueue(principal, run, origin="native") for _ in range(5)])
    await admission.release(principal, run["id"], cancelled=True, stopped=True)
    await admission.cycle()
    assert admission.calls == []
    assert (await admission.state(principal, run["id"]))["status"] == "CANCELLED"
    with pytest.raises(HTTPException) as failure:
        await admission.state(principal.model_copy(update={"actor_id": uuid4()}), run["id"])
    assert failure.value.status_code == 404


async def test_expired_execution_is_fenced_before_recovery(admission, principal):
    run = await queued(admission, principal)
    assert await admission.grant(principal, run["id"])
    epoch = (await admission.state(principal, run["id"]))["epoch"]
    async with admission.store.pool.connection() as conn:
        await conn.execute(
            "UPDATE agent_runtime.harness_admission SET lease_until=now()-interval '1 second' WHERE run_id=%s",
            (run["id"],),
        )
        row = await (
            await conn.execute("SELECT * FROM agent_runtime.harness_admission WHERE run_id=%s", (run["id"],))
        ).fetchone()
    with pytest.raises(HTTPException):
        await admission.require(principal, run["id"], epoch)
    await admission.reconcile(row)
    recovered = await admission.store.get(principal, "run", run["id"])
    assert recovered["status"] == "QUEUED" and recovered["epoch"] > epoch
    assert recovered["admission_resume"]
    await admission.cycle()
    await asyncio.gather(*admission.tasks)
    resumed = [call for call in admission.calls if call[0] == "resume"]
    assert len(resumed) == 1
    assert resumed[0][2]["text"] == run["request"]
    assert resumed[0][2]["request_id"] == recovered["admission_resume"]
    assert not (await admission.release(principal, run["id"], epoch, stopped=True))["accepted"]


async def test_unknown_side_effect_requires_reconciliation_and_is_not_replayed(admission, principal):
    run = await queued(admission, principal)
    await admission.grant(principal, run["id"])
    async with admission.store.pool.connection() as conn:
        await conn.execute(
            "UPDATE agent_runtime.harness_admission SET lease_until=now()-interval '1 second' WHERE run_id=%s",
            (run["id"],),
        )
        await conn.execute(
            "INSERT INTO agent_runtime.harness_calls(run_id,call_id,request_hash,tool_name,status) VALUES(%s,'write','hash','note_save','RUNNING')",
            (run["id"],),
        )
        row = await (
            await conn.execute("SELECT * FROM agent_runtime.harness_admission WHERE run_id=%s", (run["id"],))
        ).fetchone()
    await admission.reconcile(row)
    value = await admission.store.get(principal, "run", run["id"])
    assert value["status"] == "FAILED" and value["error"] == "SIDE_EFFECT_RECONCILIATION_REQUIRED"
    await admission.cycle()
    assert not [call for call in admission.calls if call[0] in {"resume", "dispatch"}]


async def test_cancellation_crossing_grant_never_leaks_a_slot(admission, principal):
    run = await queued(admission, principal)
    waiting = await admission.state(principal, run["id"])
    assert await admission.grant(principal, run["id"])
    assert (await admission.release(principal, run["id"], waiting["epoch"], cancelled=True, stopped=True))[
        "accepted"
    ]
    assert (await admission.state(principal, run["id"]))["status"] == "CANCELLED"
    next_run = await queued(admission, principal)
    assert await admission.grant(principal, next_run["id"])


async def test_confirmed_missing_native_log_releases_slot_without_replaying_work(admission, principal):
    run = await queued(admission, principal)
    await admission.grant(principal, run["id"])
    async with admission.store.pool.connection() as conn:
        await conn.execute(
            "UPDATE agent_runtime.harness_admission SET lease_until=now()-interval '1 second' WHERE run_id=%s",
            (run["id"],),
        )
        row = await (await conn.execute(
            "SELECT * FROM agent_runtime.harness_admission WHERE run_id=%s", (run["id"],)
        )).fetchone()

    async def runtime(*args):
        return {"running": False, "missing": True}

    admission.service.runtime = runtime
    await admission.reconcile(row)
    recovered = await admission.store.get(principal, "run", run["id"])
    assert recovered["status"] == "FAILED" and recovered["error"] == "DSH_SESSION_LOST"
    assert recovered["request"] == run["request"]
    assert (await admission.state(principal, run["id"]))["admission"] == "RELEASED"
    await admission.cycle()
    await asyncio.gather(*admission.tasks)
    assert not admission.calls


async def test_organization_limit_does_not_block_other_organizations(admission, principal):
    admission.settings = admission.settings.model_copy(update={"max_running_runs_per_organization": 1})
    second = principal.model_copy(update={"actor_id": uuid4()})
    external = principal.model_copy(update={"organization_id": uuid4(), "actor_id": uuid4()})
    a = await queued(admission, principal)
    b = await queued(admission, second)
    c = await queued(admission, external)
    await admission.cycle()
    assert (await admission.state(principal, a["id"]))["admission"] == "GRANTED"
    assert (await admission.state(second, b["id"]))["admission"] == "QUEUED"
    assert (await admission.state(external, c["id"]))["admission"] == "GRANTED"


async def test_lost_dispatch_reply_reuses_run_identity_and_stops_retrying_after_acceptance(
    admission, principal
):
    run = await queued(admission, principal, "api")
    attempts = []

    async def dispatch(owner, rid):
        attempts.append(rid)
        if len(attempts) == 1:
            raise HTTPException(503, "DSH_RUNTIME_UNAVAILABLE")

    admission.service._dispatch_run = dispatch
    await admission.cycle()
    await asyncio.gather(*admission.tasks)
    assert attempts == [run["id"]]
    async with admission.store.pool.connection() as conn:
        await conn.execute(
            "UPDATE agent_runtime.harness_admission SET retry_at=now() WHERE run_id=%s", (run["id"],)
        )
    await admission.cycle()
    await asyncio.gather(*admission.tasks)
    await admission.cycle()
    await asyncio.gather(*admission.tasks)
    assert attempts == [run["id"], run["id"]]


async def test_unrecoverable_dispatch_exposes_retryable_failure_instead_of_infinite_queue(
    admission, principal
):
    run = await queued(admission, principal, "api")

    async def dispatch(*_):
        raise HTTPException(422, "BAD_RUNTIME_CONFIGURATION")

    admission.service._dispatch_run = dispatch
    await admission.cycle()
    await asyncio.gather(*admission.tasks)
    value = await admission.store.get(principal, "run", run["id"])
    assert value["status"] == "FAILED" and value["error"] == "RUNTIME_EXECUTION_FAILED"
    assert value["failure_diagnostic"]["code"] == "BAD_RUNTIME_CONFIGURATION"
    await admission.cycle()
    await asyncio.gather(*admission.tasks)
    assert (await admission.state(principal, run["id"]))["admission"] == "RELEASED"


async def test_concurrent_recovery_changes_generation_once_and_preserves_execution_time(admission, principal):
    run = await queued(admission, principal)
    await admission.grant(principal, run["id"])
    async with admission.store.transaction(principal, run["id"]) as conn:
        value = await admission.store.get(principal, "run", run["id"], conn)
        import time

        value.update(status="RUNNING", started_at=time.time() - 4, active_seconds=3, dsh_turn=1)
        await admission.store.update(principal, "run", value, conn)
        await conn.execute(
            "UPDATE agent_runtime.harness_admission SET lease_until=now()-interval '1 second' WHERE run_id=%s",
            (run["id"],),
        )
        row = await (
            await conn.execute("SELECT * FROM agent_runtime.harness_admission WHERE run_id=%s", (run["id"],))
        ).fetchone()
    other = RunAdmission(admission.service)
    await asyncio.gather(admission.reconcile(row), other.reconcile(row))
    recovered = await admission.store.get(principal, "run", run["id"])
    assert recovered["epoch"] == row["epoch"] + 1
    assert recovered["active_seconds"] >= 7 and recovered["started_at"] is None
    assert "dsh_turn" not in recovered


async def test_unreachable_host_does_not_stall_another_organizations_admission(admission, principal):
    stale = await queued(admission, principal)
    await admission.grant(principal, stale["id"])
    async with admission.store.pool.connection() as conn:
        await conn.execute(
            "UPDATE agent_runtime.harness_admission SET lease_until=now()-interval '1 second' WHERE run_id=%s",
            (stale["id"],),
        )
    external = principal.model_copy(update={"organization_id": uuid4(), "actor_id": uuid4()})
    fresh = await queued(admission, external, "api")
    disconnected = asyncio.Event()
    confirm_stopped = asyncio.Event()
    dispatched = asyncio.Event()
    original = admission.service.runtime

    async def dispatch(owner, rid):
        admission.calls.append(("dispatch", rid))
        dispatched.set()

    async def runtime(rid, action, body=None):
        if rid == stale["id"] and action == "state":
            disconnected.set()
            await confirm_stopped.wait()
        return await original(rid, action, body)

    admission.service.runtime = runtime
    admission.service._dispatch_run = dispatch
    try:
        await asyncio.wait_for(admission.cycle(), timeout=2)
        await asyncio.wait_for(disconnected.wait(), timeout=2)
        assert (await admission.state(external, fresh["id"]))["admission"] == "GRANTED"
        dispatches = [task for task in admission.tasks if not task.done()]
        assert dispatches  # Host confirmation remains pending while the other owner enters.
        await asyncio.wait_for(dispatched.wait(), timeout=2)
        assert ("dispatch", fresh["id"]) in admission.calls
    finally:
        confirm_stopped.set()
        await asyncio.gather(*admission.tasks)


@pytest.mark.parametrize("unresolved,ended", [(False, True), (True, True), (False, False)])
async def test_terminal_native_ending_releases_only_settled_old_run(admission, principal, unresolved, ended):
    run = await queued(admission, principal)
    assert await admission.grant(principal, run["id"])
    async with admission.store.transaction(principal, run["id"]) as conn:
        current = await admission.store.get(principal, "run", run["id"], conn)
        current.update(status="FAILED", error="DSH_EXECUTION_INTERRUPTED", dsh_turn=3)
        if ended:
            current["dsh_stop_reason"] = {"kind": "error", "error": {"code": "TOOL_ACTIVATION_FAILED"}}
        await admission.store.update(principal, "run", current, conn)
        if unresolved:
            await conn.execute(
                "INSERT INTO agent_runtime.harness_calls(run_id,call_id,request_hash,tool_name,status) "
                "VALUES(%s,'command','hash','dsh_execute','UNKNOWN')", (run["id"],),
            )
        await conn.execute("UPDATE agent_runtime.harness_admission SET lease_until=now()-interval '1 second' WHERE run_id=%s", (run["id"],))
    async def unavailable(*_):
        raise HTTPException(403, "OLD_DELIVERY_AUTHORITY_EXPIRED")
    admission.service.runtime = unavailable
    await admission.cycle()
    await asyncio.gather(*admission.tasks, return_exceptions=True)
    state = await admission.state(principal, run["id"])
    assert state["admission"] == ("RELEASED" if ended and not unresolved else "RELEASING")
    if unresolved or not ended:
        # A refusal retains the slot and backs off, rather than spinning each tick.
        await admission.cycle()
        assert not admission.tasks
    assert (await admission.store.get(principal, "run", run["id"]))["epoch"] == run["epoch"] + 1
