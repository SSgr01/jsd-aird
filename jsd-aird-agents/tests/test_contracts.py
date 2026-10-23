import pytest
from pydantic import ValidationError

from jsd_aird_agents.contracts import CreateRun, IntentPlan, Step
from jsd_aird_agents.errors import AgentError
from jsd_aird_agents.graph.validation import resolve_arguments, validate_plan
from jsd_aird_agents.harness.tools import ToolRegistry, ToolSpec, default_registry
from jsd_aird_agents.settings import Settings


def plan(*steps):
    return IntentPlan(steps=list(steps))


def test_plan_supports_dependency_dag_and_result_references():
    value = plan(
        Step(id="read", kind="FACT_QUERY", tool="get_project_snapshot"),
        Step(
            id="analyse",
            kind="ANALYSIS",
            depends_on=["read"],
            arguments={"input": {"$ref": "read.data.name"}},
        ),
    )
    validate_plan(value, default_registry(), 12)
    assert resolve_arguments(value.steps[1].arguments, {"read": {"data": {"name": "项目"}}}) == {
        "input": "项目"
    }


@pytest.mark.parametrize(
    "steps",
    [
        [Step(id="a", kind="ANALYSIS", depends_on=["missing"])],
        [Step(id="a", kind="ANALYSIS", depends_on=["b"]), Step(id="b", kind="ANALYSIS", depends_on=["a"])],
        [Step(id="a", kind="ANALYSIS"), Step(id="a", kind="ANALYSIS")],
        [Step(id="a", kind="FACT_QUERY", tool="update_project_task")],
        [Step(id="a", kind="ACTION_PROPOSAL", tool="update_project_task")],
        [
            Step(
                id="a",
                kind="FACT_QUERY",
                tool="get_project_snapshot",
                arguments={"organization_id": "forged"},
            )
        ],
        [Step(id="a", kind="ANALYSIS", arguments={"value": {"$ref": "unknown.data"}})],
    ],
)
def test_invalid_plan_is_rejected(steps):
    with pytest.raises(AgentError):
        validate_plan(plan(*steps), default_registry(), 12)


def test_plan_limits_and_unknown_tools():
    with pytest.raises(AgentError):
        validate_plan(
            plan(Step(id="a", kind="ANALYSIS"), Step(id="b", kind="ANALYSIS")), default_registry(), 1
        )
    with pytest.raises(AgentError, match="未注册"):
        validate_plan(plan(Step(id="a", kind="FACT_QUERY", tool="execute_sql")), default_registry(), 12)
    with pytest.raises(AgentError):
        resolve_arguments({"$ref": "a.missing"}, {"a": {}})


def test_registry_and_request_identity_are_not_model_controlled():
    registry = default_registry()
    with pytest.raises(ValueError):
        registry.register(registry.require("get_task_detail"))
    with pytest.raises(ValueError):
        ToolRegistry().register(ToolSpec("shell", CreateRun, "project.update", True, "CRITICAL"))
    with pytest.raises(ValidationError):
        CreateRun(request="hello", actor_id="forged")


@pytest.mark.parametrize(
    "overrides",
    [
        {"database_url": "postgresql://user@localhost/jsd_aird"},
        {"domain_backend": "java"},
        {"host": "0.0.0.0"},
        {"actor_token": "short"},
        {"model_backend": "automatic_fallback"},
    ],
)
def test_settings_fail_closed_without_touching_business_database(overrides):
    args = {
        "database_url": "postgresql://user@localhost/jsd_aird_agents_test",
        "actor_token": "actor-token-123456789",
        "approver_token": "approver-token-123456789",
        "_env_file": None,
        **overrides,
    }
    with pytest.raises(ValidationError):
        Settings(**args)


def test_api_can_be_constructed_before_the_asgi_event_loop_starts():
    from jsd_aird_agents.api import create_app
    from jsd_aird_agents.settings import Settings

    app = create_app(
        Settings(
            database_url="postgresql://fixture@127.0.0.1/jsd_aird_agents_test",
            actor_token="fixture-actor-token-123456",
            approver_token="fixture-approver-token-123456",
            model_backend="scripted",
            _env_file=None,
        )
    )
    assert app.state.services.graph is None
    assert "/internal/v1/agents/runs" in app.openapi()["paths"]


def test_module_model_configuration_precedes_legacy_process_variables(tmp_path, monkeypatch):
    path = tmp_path / ".env"
    path.write_text("JSD_AIRD_AGENTS_MODEL_NAME=module-file-model\n", encoding="utf-8")
    monkeypatch.setenv("JSD_AIRD_MODEL_NAME", "legacy-process-model")
    monkeypatch.delenv("JSD_AIRD_AGENTS_MODEL_NAME", raising=False)
    kwargs = dict(
        database_url="postgresql://fixture@127.0.0.1/jsd_aird_agents_test",
        actor_token="fixture-actor-token-123456",
        approver_token="fixture-approver-token-123456",
    )
    assert Settings(**kwargs, _env_file=path).model_name == "module-file-model"
    monkeypatch.setenv("JSD_AIRD_AGENTS_MODEL_NAME", "module-process-model")
    assert Settings(**kwargs, _env_file=path).model_name == "module-process-model"
    assert Settings(**kwargs, model_name="explicit-model", _env_file=path).model_name == "explicit-model"


@pytest.mark.parametrize(
    "arguments", [{"user_id": "invented"}, {"project_id": "not-a-uuid"}, {"unknown": True}]
)
def test_planner_validates_static_tool_parameters_before_execution(arguments):
    with pytest.raises(AgentError) as exc:
        validate_plan(
            plan(Step(id="tasks", kind="FACT_QUERY", tool="list_project_tasks", arguments=arguments)),
            default_registry(),
            12,
        )
    assert exc.value.code == "PLAN_INVALID"
