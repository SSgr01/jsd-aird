import copy
from dataclasses import replace
from pathlib import Path
from uuid import uuid4

import pytest
from conftest import AUTH, FOREIGN, context, new_run
from pydantic import ValidationError

from jsd_aird_agents.adapters.fixtures import ACTOR, APPROVER, PROJECT
from jsd_aird_agents.app.platform.evaluation import evaluate
from jsd_aird_agents.app.platform.packs import VerticalPack, VerticalRegistry
from jsd_aird_agents.app.platform.skills.frameworks import langchain_tools
from jsd_aird_agents.app.verticals.quality.gateway import DATASET, OTHER_DATASET
from jsd_aird_agents.contracts import CreateRun, IntentPlan, RunContext, Step
from jsd_aird_agents.errors import AgentError
from jsd_aird_agents.graph.validation import validate_plan
from jsd_aird_agents.models.adapters import ScriptedModel
from jsd_aird_agents.worker import Worker

PREFIX = "/internal/v1/agents"
ROOT = Path(__file__).parents[1] / "src/jsd_aird_agents/app/verticals"


@pytest.mark.parametrize(
    "mutation",
    [
        lambda m: m.update(required_permissions=["quality.superuser"]),
        lambda m: m["workflows"][0].update(tools=["execute_sql"]),
        lambda m: m["scheduled_jobs"][0].update(workflow="missing"),
        lambda m: m["tools"][0].update(risk="CRITICAL"),
        lambda m: m.update(graph_version="arbitrary-import"),
        lambda m: m.update(python_entrypoint="os.system"),
    ],
)
def test_manifest_fails_closed(mutation):
    manifest = VerticalPack.load(ROOT / "quality/manifest.yaml").model_dump(mode="json")
    mutation(manifest)
    with pytest.raises(ValidationError):
        VerticalPack.model_validate(manifest)


def test_duplicate_yaml_and_outside_skill_paths_are_rejected(tmp_path):
    file = tmp_path / "manifest.yaml"
    file.write_text("id: project\nid: quality\n", encoding="utf-8")
    with pytest.raises(ValueError):
        VerticalPack.load(file)


@pytest.mark.integration
async def test_registration_does_not_grant_tools_or_permissions(services):
    registry = VerticalRegistry()
    binding = services.packs.require("quality")
    registry.register(binding)
    with pytest.raises(ValueError):
        registry.register(binding)
    changed = copy.copy(binding)
    changed.tools = [replace(binding.tools[0], permission="project.update")]
    with pytest.raises(ValueError):
        VerticalRegistry().register(changed)
    actor = services.identity.lookup(ACTOR)
    services.identity.principals[ACTOR] = replace(actor, permissions=("project.view", "ai.use"))
    with pytest.raises(AgentError, match="权限"):
        await quality_run(services)


async def quality_run(services):
    return await services.store.create_run(
        services.identity.lookup(ACTOR),
        CreateRun(request="检查质量异常", pack_ids=["quality"], resource_scopes={"quality": DATASET}),
        str(uuid4()),
    )


@pytest.mark.integration
async def test_quality_reuses_runtime_skills_sse_artifacts_and_eval(services, client, monkeypatch):
    async def forbidden(*args):
        raise AssertionError("Quality must not query the project adapter")

    monkeypatch.setattr(services.domain, "read", forbidden)
    scan = await services.skills.sync()
    for item in scan["registered"]:
        await services.skills.set_enabled(item["name"], item["sha256"], True)
    run = await quality_run(services)
    await Worker(services).tick()
    snapshot = await services.store.snapshot(run["id"], services.identity.lookup(ACTOR))
    assert snapshot["status"] == "SUCCEEDED", snapshot["error"]
    result = snapshot["result"]
    assert result["receipts"] == []
    assert result["citations"] and all(
        e["pack_id"] == "quality" and e["project_id"] is None for e in result["citations"]
    )
    assert [s["name"] for s in result["trace"]["skills"]] == ["quality-anomaly-review"]
    assert evaluate(services.packs.require("quality").manifest, "quality-fixture", result)["passed"]
    stream = await client.get(PREFIX + f"/runs/{run['id']}/events", headers=AUTH)
    assert "answer.delta" in stream.text and "附着力" in stream.text
    artifact = result["artifact"]["id"]
    assert (await client.get(PREFIX + f"/artifacts/{artifact}", headers=AUTH)).status_code == 200
    assert (await client.get(PREFIX + f"/artifacts/{artifact}", headers=FOREIGN)).status_code == 404


@pytest.mark.integration
async def test_cross_pack_tools_are_denied_even_with_forged_context(services):
    run = await new_run(services)
    forged = replace(
        context(services, run), pack_id="quality", pack_ids=("project", "quality"), resource_id=DATASET
    )
    with pytest.raises(AgentError) as error:
        await services.harness.invoke("list_quality_anomalies", {}, forged, "forged")
    assert error.value.code == "PACK_TOOL_FORBIDDEN"
    run = await quality_run(services)
    ctx = RunContext(services.identity.lookup(ACTOR), run["conversation_id"], run["id"], PROJECT)
    with pytest.raises(AgentError) as error:
        await services.harness.invoke("list_project_tasks", {}, ctx, "forged")
    assert error.value.code == "PACK_TOOL_FORBIDDEN"
    with pytest.raises(AgentError) as error:
        await services.harness.invoke("list_quality_anomalies", {"organization_id": "forged"}, ctx, "args")
    assert error.value.code == "TOOL_ARGUMENTS_INVALID"


@pytest.mark.integration
async def test_quality_scope_is_not_organization_or_project_access(services):
    actor = services.identity.lookup(ACTOR)
    for scope in (OTHER_DATASET, PROJECT):
        with pytest.raises(AgentError):
            await services.store.create_run(
                actor,
                CreateRun(request="检查质量", pack_ids=["quality"], resource_scopes={"quality": scope}),
                str(uuid4()),
            )
    # Even granting this dataset identifier cannot authorize data in another organization.
    services.identity.principals[ACTOR] = replace(actor, resource_scopes=(("quality", OTHER_DATASET),))
    with pytest.raises(AgentError):
        await services.store.create_run(
            services.identity.lookup(ACTOR),
            CreateRun(request="检查质量", pack_ids=["quality"], resource_scopes={"quality": OTHER_DATASET}),
            str(uuid4()),
        )


@pytest.mark.integration
async def test_unregistered_pack_and_undeclared_step_owner_are_rejected(services, client):
    response = await client.post(
        PREFIX + "/runs",
        headers={**AUTH, "Idempotency-Key": "missing-pack"},
        json={"request": "检查", "pack_ids": ["unregistered"]},
    )
    assert response.status_code == 403
    plan = IntentPlan(
        steps=[Step(id="read", kind="FACT_QUERY", pack_id="project", tool="list_quality_anomalies")]
    )
    with pytest.raises(AgentError):
        validate_plan(
            plan,
            services.registry,
            12,
            allowed_tools=services.packs.tools_for(["quality"]),
            pack_ids=["quality"],
        )


@pytest.mark.integration
async def test_module_version_changes_block_resume_before_any_tool(services):
    run = await quality_run(services)
    binding = services.packs.require("quality")
    binding.manifest = binding.manifest.model_copy(update={"version": "0.2.0"})
    await Worker(services).tick()
    row = await services.store.run(run["id"])
    assert row["status"] == "FAILED" and row["error"]["code"] == "PACK_VERSION_MISMATCH"
    assert row["tool_calls"] == 0


@pytest.mark.integration
async def test_explicit_cross_pack_dependency_graph_uses_separate_permissions(services):
    run = await services.store.create_run(
        services.identity.lookup(ACTOR),
        CreateRun(
            request="分析项目与质量",
            pack_ids=["project", "quality"],
            resource_scopes={"project": PROJECT, "quality": DATASET},
        ),
        str(uuid4()),
    )
    services.model.adapter = ScriptedModel(
        {
            "plan": IntentPlan(
                steps=[
                    Step(id="tasks", kind="FACT_QUERY", tool="list_project_tasks"),
                    Step(
                        id="anomalies", kind="FACT_QUERY", tool="list_quality_anomalies", depends_on=["tasks"]
                    ),
                    Step(id="analysis", kind="ANALYSIS", depends_on=["tasks", "anomalies"]),
                ]
            )
        }
    )
    await Worker(services).tick()
    row = await services.store.snapshot(run["id"], services.identity.lookup(ACTOR))
    assert row["status"] == "SUCCEEDED", row["error"]
    assert {e["pack_id"] for e in row["evidence"]} == {"project", "quality"}
    assert row["plan"]["steps"][1]["depends_on"] == ["tasks"]
    assert row["pack_versions"].keys() == {"project", "quality"}


@pytest.mark.integration
async def test_skill_metadata_does_not_cross_pack_boundary(services):
    scan = await services.skills.sync()
    for item in scan["registered"]:
        await services.skills.set_enabled(item["name"], item["sha256"], True)
    run = await new_run(services)
    ctx = context(services, run)
    offered = await services.skills.catalog(enabled_only=True, ctx=ctx)
    assert "quality-anomaly-review" not in {s["name"] for s in offered}
    # A forged offered list still cannot trick the registry into selecting another pack's skill.
    with pytest.raises(AgentError) as error:
        await services.skills.pin(
            ctx, ["quality-anomaly-review"], await services.skills.catalog(enabled_only=True)
        )
    assert error.value.code == "SKILL_PACK_FORBIDDEN"


@pytest.mark.integration
async def test_langchain_tool_bridge_preserves_harness_isolation(services):
    run = await quality_run(services)
    ctx = RunContext(services.identity.lookup(ACTOR), run["conversation_id"], run["id"])
    tools = await langchain_tools(services.harness, ctx)
    assert {t.name for t in tools} == {"list_quality_anomalies", "load_skill_resource"}
    output = await next(t for t in tools if t.name == "list_quality_anomalies").ainvoke({})
    assert output["evidence"][0]["pack_id"] == "quality"


@pytest.mark.integration
async def test_approver_does_not_automatically_gain_quality_access(services):
    with pytest.raises(AgentError):
        await services.store.create_run(
            services.identity.lookup(APPROVER),
            CreateRun(request="检查质量", pack_ids=["quality"], resource_scopes={"quality": DATASET}),
            str(uuid4()),
        )


@pytest.mark.integration
async def test_third_pack_needs_only_binding_and_manifest_not_core_changes(services, settings):
    from jsd_aird_agents.app.platform.packs import PackBinding
    from jsd_aird_agents.app.platform.scheduler import CreateSchedule, Scheduler
    from jsd_aird_agents.app.verticals.quality.gateway import MockQualityGateway
    from jsd_aird_agents.app.verticals.quality.pack import QualityBusiness, QualityScriptedModel
    from jsd_aird_agents.services import Services

    template = services.packs.require("quality")
    data = template.manifest.model_dump(mode="json")
    data.update(id="extension-sample", name="Minimal explicit extension", skills=[])
    data["tools"][0]["name"] = "list_sample_checks"
    data["workflows"][0]["tools"] = ["list_sample_checks"]
    manifest = VerticalPack.model_validate(data)

    class Gateway(MockQualityGateway):
        async def read(self, name, arguments, ctx):
            assert name == "list_sample_checks"
            return await super().read("list_quality_anomalies", arguments, ctx)

    class Model(QualityScriptedModel):
        async def generate(self, kind, payload, schema):
            value, usage = await super().generate(kind, payload, schema)
            if kind == "plan":
                value.steps[0].tool = "list_sample_checks"
            return value, usage

    def factory(store, identity):
        def graph_factory(workflow, checkpointer):
            # A code-approved graph factory is discovered via the pack, never a manifest import.
            compiled.append(True)
            return workflow.compile(checkpointer)

        return PackBinding(
            manifest,
            template.root,
            [replace(template.tools[0], name="list_sample_checks", pack_id="extension-sample")],
            QualityBusiness(),
            Gateway(store, identity),
            {},
            Model(),
            workflow_factories={manifest.workflows[0].id: graph_factory},
        )

    compiled = []
    extended = Services(settings, extra_pack_factories=(factory,))
    await extended.open()
    assert compiled == [True]
    try:
        scheduler = Scheduler(extended)
        schedule = await scheduler.create(
            extended.identity.lookup(ACTOR),
            CreateSchedule(
                pack_id="extension-sample", job_id=manifest.scheduled_jobs[0].id, scope_id=DATASET, max_runs=1
            ),
            "sample",
        )
        await scheduler.tick()
        item = await extended.db.one(
            "SELECT run_id FROM agent_runtime.schedule_occurrences WHERE schedule_id=%s", (schedule["id"],)
        )
        await Worker(extended).tick()
        run = await extended.store.run(str(item["run_id"]))
        assert run["status"] == "SUCCEEDED", run["error"]
        assert all(e["pack_id"] == "extension-sample" for e in run["result"]["citations"])
        assert evaluate(manifest, "quality-fixture", run["result"])["passed"]
        cross = await extended.store.create_run(
            extended.identity.lookup(ACTOR),
            CreateRun(
                request="联合检查",
                pack_ids=["extension-sample", "quality"],
                resource_scopes={"extension-sample": DATASET, "quality": DATASET},
            ),
            str(uuid4()),
        )
        await Worker(extended).tick()
        cross = await extended.store.run(cross["id"])
        assert cross["status"] == "SUCCEEDED", cross["error"]
        assert {e["pack_id"] for e in cross["result"]["citations"]} == {"extension-sample", "quality"}

    finally:
        await extended.close()


@pytest.mark.integration
async def test_revocation_between_cross_pack_reads_and_analysis_blocks_publication(services, monkeypatch):
    run = await services.store.create_run(
        services.identity.lookup(ACTOR),
        CreateRun(
            request="分析项目与质量",
            pack_ids=["project", "quality"],
            resource_scopes={"project": PROJECT, "quality": DATASET},
        ),
        str(uuid4()),
    )
    gateway = services.packs.require("quality").gateway
    original = gateway.read

    async def revoke_after_read(*args):
        result = await original(*args)
        principal = services.identity.lookup(ACTOR)
        services.identity.principals[ACTOR] = replace(principal, permissions=("ai.use", "project.view"))
        return result

    monkeypatch.setattr(gateway, "read", revoke_after_read)
    await Worker(services).tick()
    final = await services.store.run(run["id"])
    assert final["status"] == "FAILED" and final["error"]["code"] == "PERMISSION_DENIED"
    assert "answer.delta" not in [e["kind"] for e in await services.store.events(run["id"])]


@pytest.mark.integration
async def test_simulated_action_authority_also_checks_pack_and_workflow(services):
    from jsd_aird_agents.adapters.fixtures import TASK

    run = await quality_run(services)
    forged = RunContext(services.identity.lookup(ACTOR), run["conversation_id"], run["id"], PROJECT)
    with pytest.raises(AgentError) as error:
        await services.domain.preview(
            forged, "forged", "update_project_task", {"task_id": TASK, "planned_date": "2026-11-10"}
        )
    assert error.value.code == "PACK_TOOL_FORBIDDEN"
    run = await services.store.create_run(
        services.identity.lookup(ACTOR),
        CreateRun(request="巡检", project_id=PROJECT, workflow_id="project-health-check"),
        str(uuid4()),
    )
    ctx = context(services, run)
    with pytest.raises(AgentError) as error:
        await services.domain.preview(
            ctx, "forged", "update_project_task", {"task_id": TASK, "planned_date": "2026-11-10"}
        )
    assert error.value.code == "PACK_TOOL_FORBIDDEN"


@pytest.mark.integration
async def test_original_project_api_idempotency_hash_survives_optional_pack_fields(services):
    from jsd_aird_agents.contracts import digest

    command = CreateRun(request="分析项目延期", project_id=PROJECT)
    run = await services.store.create_run(services.identity.lookup(ACTOR), command, "original-key")
    old_hash = digest({"request": command.request, "project_id": PROJECT, "conversation_id": None})
    assert run["request_hash"] == old_hash
    assert (await services.store.create_run(services.identity.lookup(ACTOR), command, "original-key"))[
        "id"
    ] == run["id"]


@pytest.mark.integration
async def test_pack_cannot_smuggle_undeclared_skill_into_platform_scope(services, tmp_path):
    from skill_helpers import make_skill

    binding = services.packs.require("quality")
    binding.root = tmp_path
    root = tmp_path / "skills"
    make_skill(root, "quality-anomaly-review", allowed="list_quality_anomalies")
    make_skill(root, "unlisted-quality-helper", allowed="get_project_snapshot")
    services.settings.skill_roots = [root]
    result = await services.skills.sync()
    assert [p["name"] for p in result["registered"]] == ["quality-anomaly-review"]
    assert result["errors"][0]["code"] == "SKILL_UNDECLARED"


@pytest.mark.integration
async def test_quality_skill_cannot_request_project_tools(services, tmp_path):
    from skill_helpers import make_skill

    binding = services.packs.require("quality")
    binding.root = tmp_path
    root = tmp_path / "skills"
    make_skill(root, "quality-anomaly-review", allowed="get_project_snapshot")
    services.settings.skill_roots = [root]
    scan = await services.skills.sync()
    entry = scan["registered"][0]
    await services.skills.set_enabled(entry["name"], entry["sha256"], True)
    run = await quality_run(services)
    await Worker(services).tick()
    final = await services.store.run(run["id"])
    assert final["error"]["code"] == "SKILL_PACK_TOOL_FORBIDDEN"
    assert final["tool_calls"] == 0


@pytest.mark.integration
async def test_old_project_evidence_payload_remains_valid_after_upgrade(services):
    from psycopg.types.json import Jsonb

    from jsd_aird_agents.contracts import AnswerDocument, Claim, Evidence
    from jsd_aird_agents.graph.validation import validate_citations

    run = await new_run(services)
    ctx = context(services, run)
    output = await services.harness.invoke("list_project_tasks", {}, ctx, "tasks")
    original = output.evidence[0].model_dump(mode="json")
    legacy = {k: v for k, v in original.items() if k not in {"pack_id", "scope_id"}}
    await services.db.execute(
        "UPDATE agent_runtime.evidence SET payload=%s WHERE id=%s", (Jsonb(legacy), original["id"])
    )
    # Cached old tool results receive new optional schema defaults during replay.
    replayed = Evidence.model_validate(legacy).model_dump(mode="json")
    answer = AnswerDocument(claims=[Claim(text="历史快照事实", evidence_ids=[original["id"]])])
    assert not await validate_citations(
        answer, {original["id"]: replayed}, ctx, services.store, services.gateway
    )


@pytest.mark.integration
async def test_entity_discovery_refreshes_revoked_resource_scope(services):
    from jsd_aird_agents.adapters.fixtures import SECOND_PROJECT

    prior = services.identity.lookup(ACTOR)
    services.identity.principals[ACTOR] = replace(prior, projects=(SECOND_PROJECT,))
    assert [p["id"] for p in await services.domain.projects(prior)] == [SECOND_PROJECT]
