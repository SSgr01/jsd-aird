import asyncio
from dataclasses import replace

import pytest
from conftest import approve, context, new_run
from psycopg.types.json import Jsonb

from jsd_aird_agents.adapters.fixtures import ACTOR, APPROVER, OTHER_PROJECT, OUTSIDER, TASK
from jsd_aird_agents.contracts import RunContext
from jsd_aird_agents.errors import AgentError

pytestmark = pytest.mark.integration


async def proposed(services):
    run = await new_run(services)
    ctx = context(services, run)
    action = await services.domain.preview(
        ctx, "deadline", "update_project_task", {"task_id": TASK, "planned_date": "2026-11-10"}
    )
    return ctx, action


async def test_no_approval_and_unknown_actions_cannot_write(services):
    ctx, action = await proposed(services)
    with pytest.raises(AgentError) as exc:
        await services.domain.execute(ctx, action["id"])
    assert exc.value.code == "APPROVAL_REQUIRED"
    assert (await services.db.one("SELECT version FROM mock_domain.tasks WHERE id=%s", (TASK,)))[
        "version"
    ] == 0
    with pytest.raises(AgentError):
        await services.domain.execute(ctx, "99999999-0000-0000-0000-000000000000")


async def test_concurrent_execution_consumes_approval_exactly_once(services):
    ctx, action = await proposed(services)
    await approve(services, action)
    receipts = await asyncio.gather(*(services.domain.execute(ctx, action["id"]) for _ in range(8)))
    assert all(r == receipts[0] and r["verified"] for r in receipts)
    task = await services.db.one("SELECT * FROM mock_domain.tasks WHERE id=%s", (TASK,))
    assert task["version"] == 1 and str(task["planned_date"]) == "2026-11-10"
    count = await services.db.one(
        "SELECT count(*) AS n FROM agent_runtime.events WHERE kind='action.executed'"
    )
    assert count["n"] == 1
    with pytest.raises(AgentError):
        await approve(services, action)


@pytest.mark.parametrize(
    "mode,expected",
    [
        ("parameters", "ACTION_CHANGED"),
        ("risk", "ACTION_CHANGED"),
        ("version", "VERSION_CONFLICT"),
        ("expiry", "APPROVAL_EXPIRED"),
    ],
)
async def test_approval_binding_changes_invalidate_execution(services, mode, expected):
    ctx, action = await proposed(services)
    await approve(services, action)
    if mode == "parameters":
        await services.db.execute(
            "UPDATE mock_domain.actions SET arguments=arguments||%s WHERE id=%s",
            (Jsonb({"planned_date": "2026-12-01"}), action["id"]),
        )
    elif mode == "risk":
        await services.db.execute("UPDATE mock_domain.actions SET risk='LOW' WHERE id=%s", (action["id"],))
    elif mode == "version":
        await services.db.execute("UPDATE mock_domain.tasks SET version=version+1 WHERE id=%s", (TASK,))
    else:
        await services.db.execute(
            "UPDATE mock_domain.actions SET expires_at=now()-interval '1 second' WHERE id=%s", (action["id"],)
        )
    with pytest.raises(AgentError) as exc:
        await services.domain.execute(ctx, action["id"])
    assert exc.value.code == expected
    task = await services.db.one("SELECT planned_date FROM mock_domain.tasks WHERE id=%s", (TASK,))
    assert str(task["planned_date"]) == "2026-10-20"
    assert (await services.domain.get(action["id"], ctx.principal))["execution_status"] == "CONFLICTED"


@pytest.mark.parametrize("decision", ["REJECTED", "REVOKED"])
async def test_rejected_and_revoked_actions_cannot_execute(services, decision):
    ctx, action = await proposed(services)
    if decision == "REVOKED":
        await approve(services, action)
    await approve(services, action, decision)
    with pytest.raises(AgentError):
        await services.domain.execute(ctx, action["id"])


async def test_write_verification_failure_rolls_back_everything(services, monkeypatch):
    ctx, action = await proposed(services)
    await approve(services, action)
    monkeypatch.setattr(services.domain, "verify", lambda *a: False)
    with pytest.raises(AgentError, match="写后验证"):
        await services.domain.execute(ctx, action["id"])
    task = await services.db.one("SELECT version FROM mock_domain.tasks WHERE id=%s", (TASK,))
    assert task["version"] == 0
    assert (await services.domain.get(action["id"], ctx.principal))["approval_status"] == "APPROVED"


async def test_permissions_and_approver_are_revalidated_at_execution(services):
    ctx, action = await proposed(services)
    with pytest.raises(AgentError):
        await services.domain.decide(action["id"], ctx.principal, "APPROVED", action["request_hash"])
    await approve(services, action)
    services.identity.principals[APPROVER] = replace(services.identity.lookup(APPROVER), can_approve=False)
    with pytest.raises(AgentError):
        await services.domain.execute(ctx, action["id"])
    services.identity.principals[APPROVER] = replace(services.identity.lookup(APPROVER), can_approve=True)
    services.identity.principals[ACTOR] = replace(ctx.principal, permissions=("project.view",))
    with pytest.raises(AgentError):
        await services.domain.execute(ctx, action["id"])


async def test_action_identity_scope_and_stale_worker_are_rejected(services):
    ctx, action = await proposed(services)
    await approve(services, action)
    foreign = RunContext(services.identity.lookup(OUTSIDER), ctx.conversation_id, ctx.run_id, OTHER_PROJECT)
    with pytest.raises(AgentError):
        await services.domain.execute(foreign, action["id"])
    await services.db.execute(
        "UPDATE agent_runtime.runs SET worker_id='new-owner',lease_epoch=2 WHERE id=%s", (ctx.run_id,)
    )
    with pytest.raises(AgentError) as exc:
        await services.domain.execute(replace(ctx, worker_id="old-owner", lease_epoch=1), action["id"])
    assert exc.value.code == "LEASE_LOST"


async def test_preview_is_stable_and_changed_proposal_conflicts(services):
    ctx, action = await proposed(services)
    duplicate = await services.domain.preview(
        ctx, "deadline", "update_project_task", {"task_id": TASK, "planned_date": "2026-11-10"}
    )
    assert action["id"] == duplicate["id"]
    with pytest.raises(AgentError) as exc:
        await services.domain.preview(
            ctx, "deadline", "update_project_task", {"task_id": TASK, "planned_date": "2026-12-01"}
        )
    assert exc.value.code == "ACTION_CHANGED"
