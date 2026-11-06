from types import SimpleNamespace
from unittest.mock import AsyncMock

import httpx
import pytest

from aird_agent.server.api import create_app
from aird_agent.server.security import issue
from aird_agent.hosts.jsd.bindings import JsdBindings
from aird_agent.core.business_service import BusinessService


@pytest.mark.parametrize('valid', [False, True])
async def test_delivery_preflight_leaves_the_run_active_until_native_stop_commits(store, principal, valid, settings):
    async with store.transaction(principal, 'native-preflight') as conn:
        run = await store.create(principal, 'run', {
            'status':'RUNNING', 'dsh_session_id':'native-preflight', 'dsh_turn':1,
            'evidence':{}, 'last_text':'answer', 'active_seconds':0, 'started_at':None,
        }, conn)
    bindings = SimpleNamespace(validate_output=lambda run, text:(text,valid),
        validate_delivery=AsyncMock(), default_completion=JsdBindings().default_completion)
    service = SimpleNamespace(store=store, settings=settings, session_run=AsyncMock(return_value=run),
        authorized_topic=AsyncMock(), bindings=bindings)
    body={'run_id':run['id'], 'session_id':'native-preflight', 'turn':1}
    checked=await BusinessService.complete_dsh(service,principal,{**body,'check_only':True})
    assert checked['status'] == ('SUCCEEDED' if valid else 'FAILED')
    current=await store.get(principal,'run',run['id'])
    assert current['status']=='RUNNING'
    assert not current.get('dsh_completed_turn')
    assert not current.get('completion')
    committed=await BusinessService.complete_dsh(service,principal,body)
    assert committed['status']==checked['status']
    current=await store.get(principal,'run',run['id'])
    assert current['dsh_completed_turn']==1
    assert current['error']==(None if valid else 'CITATION_INVALID')


async def test_read_only_delivery_does_not_require_an_unrelated_sandbox_command():
    conn = SimpleNamespace(execute=AsyncMock(return_value=SimpleNamespace(fetchall=AsyncMock(return_value=[
        {"status": "FAILED", "result": {"exit_code": 2, "error": "directory unavailable"}}]))))
    await JsdBindings().validate_delivery(None, {"id": "read-only", "pack_ids": ["projects"],
        "registered_outputs": [], "evidence": {}}, conn)


@pytest.mark.parametrize('cancelled,completed,error', [
    (False, True, 'SOURCE_CITATIONS_REQUIRED'),
    (False, False, 'DSH_EXECUTION_INTERRUPTED'),
    (True, True, 'DSH_EXECUTION_INTERRUPTED'),
])
async def test_native_failure_preserves_business_validation_diagnosis(settings, store, principal, cancelled, completed, error):
    async with store.transaction(principal, 'native-failure') as conn:
        run = await store.create(principal, 'run', {
            'status': 'FAILED' if completed else 'RUNNING', 'dsh_session_id': 'native',
            'dsh_turn': 1, 'dsh_completed_turn': 1 if completed else None,
            'error': 'SOURCE_CITATIONS_REQUIRED' if completed else None,
        }, conn)
    service = SimpleNamespace(session_run=AsyncMock(return_value=run))
    app = create_app(settings, store, service)
    headers = {'X-Jsd-Agent-Ticket': issue(settings.shared_secret.get_secret_value(), principal, 'control'),
               'Authorization': 'Bearer '+settings.runtime_secret.get_secret_value()}
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url='http://harness.test') as client:
        response = await client.post('/internal/v1/harness/dsh/failure', headers=headers,
                                     json={'run_id': run['id'], 'session_id': 'native', 'turn': 1, 'cancelled': cancelled})
    assert response.status_code == 200
    updated = await store.get(principal, 'run', run['id'])
    assert updated['status'] == ('CANCELLED' if cancelled else 'FAILED')
    assert updated['error'] == error


async def test_native_budget_failure_does_not_become_an_unknown_interruption(settings, store, principal):
    async with store.transaction(principal, 'native-budget-failure') as conn:
        run = await store.create(principal, 'run', {'status': 'RUNNING', 'dsh_session_id': 'native', 'dsh_turn': 1}, conn)
    app = create_app(settings, store, SimpleNamespace(session_run=AsyncMock(return_value=run)))
    headers = {'X-Jsd-Agent-Ticket': issue(settings.shared_secret.get_secret_value(), principal, 'control'),
               'Authorization': 'Bearer '+settings.runtime_secret.get_secret_value()}
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url='http://harness.test') as client:
        response = await client.post('/internal/v1/harness/dsh/failure', headers=headers,
            json={'run_id':run['id'], 'session_id':'native', 'turn':1, 'error_code':'TOKEN_BUDGET_EXCEEDED'})
    assert response.status_code == 200
    assert (await store.get(principal, 'run', run['id']))['error'] == 'TOKEN_BUDGET_EXCEEDED'


async def test_native_output_truncation_is_never_a_successful_delivery(store, principal, settings):
    async with store.transaction(principal, 'native-truncated') as conn:
        run = await store.create(principal, 'run', {
            'status':'RUNNING', 'dsh_session_id':'native-truncated', 'dsh_turn':1,
            'evidence':{}, 'last_text':'partial answer', 'active_seconds':0, 'started_at':None,
        }, conn)
    service = SimpleNamespace(store=store, settings=settings, session_run=AsyncMock(return_value=run),
        authorized_topic=AsyncMock(), bindings=SimpleNamespace(
            validate_output=lambda run,text:(text,True), validate_delivery=AsyncMock(),
            default_completion=JsdBindings().default_completion))
    result = await BusinessService.complete_dsh(service, principal, {
        'run_id':run['id'], 'session_id':'native-truncated', 'turn':1, 'stop_reason':'max-tokens'})
    assert result['status']=='FAILED'
    assert result['completion']['outcome']=='PARTIAL'
    assert result['completion']['gaps']==['OUTPUT_LIMIT_EXCEEDED']


async def test_native_present_failure_cannot_become_a_successful_text_only_turn(store, principal):
    from aird_agent.hosts.jsd.task_state import check_delivery

    async with store.transaction(principal, 'native-present') as conn:
        run = await store.create(principal, 'run', {
            'status':'RUNNING', 'dsh_session_id':'native-present', 'dsh_turn':1,
            'evidence':{}, 'registered_outputs':[], 'last_text':'文件登记失败。',
        }, conn)
        conversation = await store.create(principal, 'conversation', {
            'messages':[], 'active_run':run['id'], 'dsh_session_id':'native-present',
        }, conn)
    bindings = SimpleNamespace(normalize_output=lambda text,evidence:text,
        validate_output=lambda run,text:(text,True),
        validate_delivery=AsyncMock(side_effect=lambda principal,run,conn:check_delivery(run)),
        default_completion=JsdBindings().default_completion)
    async def mapped_run(principal, body):
        return await store.get(principal,'run',body['run_id'])
    service = SimpleNamespace(store=store, bindings=bindings,
        session_conversation=AsyncMock(return_value=conversation),
        session_run=AsyncMock(side_effect=mapped_run),
        authorized_topic=AsyncMock())
    event = {'seq':5,'type':'assistant/message','data':{'turn':1,'message':{
        'content':[{'type':'tool-call','name':'present','arguments':'{"files":[{"path":"output/report.docx"},{"path":"output/report.pdf"}]}'}]}}}
    body = {'session_id':'native-present','event':event}
    await BusinessService.project_event(service,principal,body)
    await BusinessService.project_event(service,principal,body)  # reconnect replay
    current = await store.get(principal,'run',run['id'])
    assert current['required_artifacts']==['report.docx','report.pdf']
    checked = await BusinessService.complete_dsh(service,principal,{
        'run_id':run['id'],'session_id':'native-present','turn':1,'check_only':True})
    assert checked['status']=='FAILED'
    assert checked['completion']['gaps'][0].startswith('DELIVERABLE_FILES_MISSING:')
