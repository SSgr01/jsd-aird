import copy
import json
from pathlib import Path
from unittest.mock import AsyncMock
from uuid import UUID, uuid4

import pytest
from fastapi import HTTPException

from aird_agent.hosts.jsd.composition import Service
from aird_agent.hosts.jsd.experts import expert_action, role_instructions
from aird_agent.server.contracts import RunRequest

CATALOG = json.loads((Path(__file__).parents[3] / "jsd-aird-api/src/main/resources/ai/expert-presets.json").read_text(encoding="utf-8"))


@pytest.fixture
async def experts(settings, store):
    catalog = copy.deepcopy(CATALOG)

    async def call(principal, action, args, run_id=None):
        if action == "authorize":
            return {"authorized": True}
        if action == "runtime_context":
            return {"pack_ids": ["core", "files"], "documents": []}
        assert action == "expert_catalog"
        if args["action"] == "list":
            return catalog
        item = next(row for row in catalog["experts"]
                    if args["expert_id"] in [row["id"], *row.get("aliases", [])])
        if args["action"] == "resolve" and not item["enabled"]:
            raise HTTPException(403, "EXPERT_DISABLED")
        return copy.deepcopy(item)

    host = type("Host", (), {"call": staticmethod(call)})()
    service = Service(settings, store, host=host)
    service.expert_test_catalog = catalog
    try:
        yield service
    finally:
        await service.http.aclose()


async def test_followup_runs_use_current_definition_for_the_same_expert(experts, principal):
    sid = "session-" + str(uuid4())
    bound = await expert_action(experts, principal, {"action": "bind", "session_id": sid, "expert_id": "project-assistant"})
    conversation = await experts.session_conversation(principal, sid)
    first = await experts.create_run(principal, RunRequest(request="梳理项目进展", conversation_id=UUID(conversation["id"])), str(uuid4()), dispatch=False)
    assert first["expert"] == bound["expert"]
    assert "项目助理" in experts.instructions_for(first)
    assert "project-diagnosis" in experts.instructions_for(first)
    assert first["pack_ids"] == ["core", "files"]  # Role does not grant project access.
    # Complete the synthetic run without starting a model or native runtime.
    async with experts.store.transaction(principal, first["id"]) as conn:
        first["status"] = "SUCCEEDED"
        await experts.store.update(principal, "run", first, conn)
    definition = experts.expert_test_catalog["experts"][0]
    definition["instructions"] = "当前开发配置"
    followup = await experts.create_run(principal, RunRequest(request="继续细化行动项", conversation_id=UUID(conversation["id"])), str(uuid4()), dispatch=False)
    assert followup["expert"]["id"] == first["expert"]["id"]
    assert followup["expert"]["work_config"] == first["expert"]["work_config"]
    assert "当前开发配置" in experts.instructions_for(followup)
    assert "当前开发配置" not in experts.instructions_for(first)
    restored = await expert_action(experts, principal, {"action": "session", "session_id": sid})
    assert restored["expert"] == definition
    assert "revision" not in restored["expert"]



async def test_text_only_expert_runs_leave_tool_selection_to_native_agent(experts, principal):
    sid = 'session-' + str(uuid4())
    await expert_action(experts, principal, {'action': 'bind', 'session_id': sid, 'expert_id': 'data-assistant'})
    conversation = await experts.session_conversation(principal, sid)
    first = await experts.create_run(principal, RunRequest(request='核对记录：001,0；002,5',
        conversation_id=UUID(conversation['id'])), str(uuid4()), dispatch=False)
    assert 'expert_check_tools' not in first
    assert 'expert_checks' not in experts.bindings.gate_state(first)
    assert 'record_validate' in {tool['name'] for tool in experts.tools_for(first)}
    discovered = await experts.bindings.dispatch(principal, first, {}, 'tool_search',
        {'query': 'record_validate 记录核验重复缺项'}, 'discover-record-check')
    assert 'record_validate' in discovered['activate_tools']
    async with experts.store.transaction(principal, first['id']) as conn:
        first['status'] = 'SUCCEEDED'
        first['active_tools'] = ['record_validate', 'artifact_save', 'unregistered_tool']
        await experts.store.update(principal, 'run', first, conn)
    followup = await experts.create_run(principal, RunRequest(request='第二行改成6，请继续',
        pack_ids=['core'], conversation_id=UUID(conversation['id'])), str(uuid4()), dispatch=False)
    assert 'expert_check_tools' not in followup
    assert 'expert_checks' not in experts.bindings.gate_state(followup)
    assert 'record_validate' in {tool['name'] for tool in experts.tools_for(followup)}
    assert 'record_validate' in followup['active_tools']
    assert 'tool_search' in followup['active_tools']
    assert 'artifact_save' not in followup['active_tools']
    assert 'unregistered_tool' not in followup['active_tools']
    # Existing sessions may contain a failed turn produced by the old reset.
    # Recover prior discovery from the already authorized native conversation.
    async with experts.store.transaction(principal, followup['id']) as conn:
        followup.update(status='FAILED', active_tools=[])
        await experts.store.update(principal, 'run', followup, conn)
    restored = await experts.create_run(principal, RunRequest(request='解释已有结论',
        pack_ids=['core'], conversation_id=UUID(conversation['id'])), str(uuid4()), dispatch=False)
    assert 'record_validate' in restored['active_tools']
    assert 'artifact_save' not in restored['active_tools']


async def test_runtime_context_leaves_clock_and_zone_to_native_context(experts, principal):
    context = await experts.bindings.runtime_topic(principal, {}, {})
    assert 'current_date' not in context
    assert 'time_zone' not in context
    instructions = experts.instructions_for({'pack_ids': ['core', 'files']})
    assert '原生时间上下文' in instructions
    assert 'Python datetime/zoneinfo' in instructions


async def test_binding_cannot_replace_an_expert_or_existing_general_conversation(experts, principal):
    sid = "session-" + str(uuid4())
    await expert_action(experts, principal, {"action": "bind", "session_id": sid, "expert_id": "project-assistant"})
    with pytest.raises(HTTPException, match="EXPERT_REQUIRES_NEW_SESSION"):
        await expert_action(experts, principal, {"action": "bind", "session_id": sid, "expert_id": "quality-assistant"})
    plain = await experts.ensure_conversation(principal, "session-" + str(uuid4()), "agent")
    async with experts.store.transaction(principal, plain["id"]) as conn:
        plain["messages"] = [{"text": "已有对话"}]
        await experts.store.update(principal, "conversation", plain, conn)
    with pytest.raises(HTTPException, match="EXPERT_REQUIRES_NEW_SESSION"):
        await expert_action(experts, principal, {"action": "bind", "session_id": plain["dsh_session_id"], "expert_id": "quality-assistant"})


async def test_fork_ownership_pins_and_disabled_history(experts, principal):
    sid, child = "session-" + str(uuid4()), "session-" + str(uuid4())
    body = {"action": "bind", "session_id": sid, "expert_id": "project-assistant"}
    await expert_action(experts, principal, body)
    await expert_action(experts, principal, {"action": "fork", "source_session_id": sid, "session_id": child})
    assert (await experts.session_conversation(principal, child))["expert"]["id"] == "project-assistant"
    pinned = await expert_action(experts, principal, {"action": "pin", "expert_id": "project-assistant", "pinned": True})
    assert pinned["pinned"] == ["project-assistant"]
    foreign = principal.model_copy(update={"actor_id": uuid4(), "organization_id": uuid4()})
    assert (await expert_action(experts, foreign, {"action": "session", "session_id": sid}))["expert"] is None
    assert (await expert_action(experts, foreign, {"action": "list"}))["pinned"] == []
    experts.expert_test_catalog["experts"][0]["enabled"] = False
    assert not (await expert_action(experts, principal, {"action": "session", "session_id": sid}))["expert"]["enabled"]
    conversation = await experts.session_conversation(principal, sid)
    with pytest.raises(HTTPException, match="EXPERT_DISABLED"):
        await experts.bindings.prepare_request(principal, RunRequest(request="继续", conversation_id=UUID(conversation["id"])))
    with pytest.raises(HTTPException, match="EXPERT_DISABLED"):
        await experts.authorized_topic(principal, {"expert": conversation["expert"]})


async def test_background_and_configuration_actions_are_rejected(principal):
    service = type("Service", (), {"java": AsyncMock()})()
    with pytest.raises(HTTPException, match="INTERACTIVE_AUTHORIZATION_REQUIRED"):
        await expert_action(service, principal.model_copy(update={"automation_grant_id": "background"}), {"action": "list"})
    for action in ["create", "edit", "share", "clone", "team"]:
        with pytest.raises(HTTPException, match="EXPERT_ACTION_DENIED"):
            await expert_action(service, principal, {"action": action})
    service.java.assert_not_called()
    assert role_instructions(None) == ""
