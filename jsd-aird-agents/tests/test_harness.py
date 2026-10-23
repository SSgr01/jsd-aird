from dataclasses import replace

import pytest
from conftest import context, new_run

from jsd_aird_agents.adapters.fixtures import ACTOR, HIDDEN_PROJECT, TASK
from jsd_aird_agents.contracts import AnswerDocument, Claim
from jsd_aird_agents.errors import AgentError
from jsd_aird_agents.graph.validation import validate_citations
from jsd_aird_agents.models.adapters import ScriptedModel

pytestmark = pytest.mark.integration


async def test_tool_schema_scope_permissions_and_mutation_barrier(services):
    run = await new_run(services)
    ctx = context(services, run)
    for name, args, expected in [
        ("execute_sql", {}, "UNKNOWN_TOOL"),
        ("list_project_tasks", {"actor_id": "forged"}, "TOOL_ARGUMENTS_INVALID"),
        ("list_project_tasks", {"project_id": HIDDEN_PROJECT}, "SCOPE_MISMATCH"),
        (
            "update_project_task",
            {"task_id": TASK, "planned_date": "2026-11-10"},
            "MUTATION_REQUIRES_ACTION_PIPELINE",
        ),
    ]:
        with pytest.raises(AgentError) as exc:
            await services.harness.invoke(name, args, ctx, "test")
        assert exc.value.code == expected
    services.identity.principals[ACTOR] = replace(ctx.principal, permissions=())
    with pytest.raises(AgentError) as exc:
        await services.harness.invoke("list_project_tasks", {}, ctx, "test")
    assert exc.value.code == "PERMISSION_DENIED"


async def test_modified_migration_history_is_rejected(services):
    await services.db.execute("UPDATE agent_runtime.schema_version SET sha256='tampered' WHERE version=1")
    with pytest.raises(AgentError) as exc:
        await services.db.migrate()
    assert exc.value.code == "MIGRATION_CHECKSUM_MISMATCH"


async def test_forged_run_context_is_rejected_before_preview(services):
    run = await new_run(services)
    ctx = replace(context(services, run), conversation_id="99999999-0000-0000-0000-000000000000")
    with pytest.raises(AgentError) as exc:
        await services.harness.invoke("list_project_tasks", {}, ctx, "tasks")
    assert exc.value.code == "RUN_CONTEXT_MISMATCH"


async def test_tool_budget_and_cached_reads(services):
    services.settings.max_tool_calls = 1
    run = await new_run(services)
    ctx = context(services, run)
    first = await services.harness.invoke("list_project_tasks", {}, ctx, "tasks")
    duplicate = await services.harness.invoke("list_project_tasks", {}, ctx, "tasks")
    assert first == duplicate
    with pytest.raises(AgentError) as exc:
        await services.harness.invoke("get_project_snapshot", {}, ctx, "project")
    assert exc.value.code == "BUDGET_EXCEEDED"


async def test_evidence_hash_run_and_version_are_checked(services):
    run = await new_run(services)
    ctx = context(services, run)
    output = await services.harness.invoke("list_project_tasks", {}, ctx, "tasks")
    original = output.evidence[0].model_dump(mode="json")
    answer = AnswerDocument(claims=[Claim(text="示例事实", evidence_ids=[original["id"]])])
    assert not await validate_citations(
        answer, {original["id"]: original}, ctx, services.store, services.domain
    )
    for field, value in (("run_id", "forged"), ("resource_version", "99"), ("content", "tampered")):
        changed = {**original, field: value}
        assert await validate_citations(
            answer, {original["id"]: changed}, ctx, services.store, services.domain
        )


async def test_model_retry_attempts_are_counted_and_persisted(services):
    run = await new_run(services)
    ctx = context(services, run)
    services.model.adapter = ScriptedModel(
        {
            "answer": [
                AgentError("MODEL_TIMEOUT", "timeout", retryable=True),
                AnswerDocument(recommendations=["重试后的建议"]),
            ]
        }
    )
    result = await services.model.generate(ctx, "final", "answer", {"request": "test"}, AnswerDocument)
    assert result.recommendations
    assert (await services.store.run(run["id"]))["model_calls"] == 2
    call = await services.db.one(
        "SELECT attempts,status FROM agent_runtime.calls WHERE run_id=%s", (run["id"],)
    )
    assert call["attempts"] == 2 and call["status"] == "SUCCEEDED"


async def test_migrations_are_repeatable_and_checkpoints_are_isolated(services):
    await services.db.migrate()
    row = await services.db.one("SELECT count(*) AS n FROM agent_runtime.schema_version")
    assert row["n"] == 5
    tables = await services.db.all("SELECT schemaname FROM pg_tables WHERE tablename='checkpoints'")
    assert {r["schemaname"] for r in tables} == {"agent_checkpoint"}
