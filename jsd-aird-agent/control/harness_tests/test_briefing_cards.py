from contextlib import asynccontextmanager
from copy import deepcopy
from types import SimpleNamespace
from unittest.mock import AsyncMock
from uuid import uuid4

import httpx
import pytest
from fastapi import HTTPException

from aird_agent.hosts.jsd.briefing_cards import cards, excerpt, open_result
from aird_agent.server.api import create_app
from aird_agent.server.security import issue


def briefing_run(kind='project-morning', status='SUCCEEDED', turn=1):
    project = str(uuid4())
    key = 'e' + '1' * 24
    return {
        'id': str(uuid4()), 'created_at': '2026-11-11T01:00:00+00:00',
        'updated_at': '2026-11-11T01:03:00+00:00', 'status': status,
        'dsh_session_id': 'briefing-owned', 'dsh_turn': turn,
        'automation': {'builtin_id': kind, 'task_id': 'native-owned', 'session_id': 'briefing-owned',
                       'turn': turn, 'window': {'start': None, 'end': '2026-11-11T01:00:00+00:00', 'time_zone': 'Asia/Shanghai'}},
        'briefing_snapshot': {'frozen': True, 'sections': {'PROJECT': []}},
        'result': f'## 本期重点\n\n先补齐固化条件，再比较实验结果。\n\n## 建议行动\n\n1. 确认条件。[E:{key}]\n2. 核对记录。\n\n## 说明\n\n其他资料。',
        'evidence': {key: {'source': {'kind': 'BUSINESS_RECORD', 'resource_type': 'PROJECT', 'resource_id': project}}},
    }


class ReadStore:
    """Exercise selection/projection without any business database or model."""
    def __init__(self, principal, runs):
        self.principal, self.runs, self.queries = principal, runs, []

    @asynccontextmanager
    async def connection(self):
        yield self

    async def execute(self, query, params):
        self.queries.append((query, params))
        assert params[:2] == self.principal.key
        matching = [r for r in self.runs if (r['dsh_session_id'], r['automation']['builtin_id'], r['automation']['task_id']) == params[2:]]
        if "payload->>'status'='SUCCEEDED'" in query:
            matching = [r for r in matching if r['status'] == 'SUCCEEDED']
        matching = [r for r in matching if not r.get('session_deleted')]
        value = {'id': matching[-1]['id']} if matching else None
        return SimpleNamespace(fetchone=AsyncMock(return_value=value))

    async def get(self, user, kind, rid):
        if user.key != self.principal.key:
            raise HTTPException(404)
        return deepcopy(next(r for r in self.runs if r['id'] == rid))


def make_service(principal, runs, kind='project-morning'):
    owner = {'tasks': {kind: {'session_id': 'briefing-owned', 'native_id': 'native-owned', 'status': 'active'}}}
    return SimpleNamespace(store=ReadStore(principal, runs), java=AsyncMock(return_value={}),
                           briefings=SimpleNamespace(owner=AsyncMock(return_value=owner)),
                           authorized_topic=AsyncMock(return_value={}))


def test_excerpt_uses_existing_prose_and_only_authorized_business_destinations():
    run = briefing_run()
    value = excerpt(run)
    assert value['summary'] == '先补齐固化条件，再比较实验结果。'
    assert value['actions'][0]['href'].startswith('/projects/')
    assert 'href' not in value['actions'][1]
    assert '[E:' not in str(value)
    run['result'] = '## 本期重点\n\n**判断**：先补齐条件。\n\n## 建议行动\n\n1. [点击](https://evil.example/path)'
    value = excerpt(run)
    assert value['summary'] == '先补齐条件。'
    assert value['actions'] == [{'text': '点击'}]


async def test_latest_failure_retains_successful_period_without_copying_body(principal):
    completed = briefing_run()
    failure = briefing_run(status='FAILED', turn=2)
    service = make_service(principal, [completed, failure])
    result = await cards(service, principal)
    first = result['items'][0]
    assert first['state'] == 'FAILED'
    assert first['result']['run_id'] == completed['id']
    assert first['latest']['status'] == 'FAILED'
    assert 'result' not in first['result'] and 'evidence' not in first['result']
    service.authorized_topic.assert_awaited_once_with(principal, completed, execution=False)
    for query, params in service.store.queries:
        assert 'organization_id=%s' in query and 'actor_id=%s' in query
        assert params[:2] == principal.key


@pytest.mark.parametrize('code', [403, 404, 409, 410])
async def test_changed_source_access_hides_all_summary_action_and_session_data(principal, code):
    service = make_service(principal, [briefing_run()])
    service.authorized_topic.side_effect = HTTPException(code)
    first = (await cards(service, principal))['items'][0]
    assert first['state'] == 'UNAVAILABLE'
    assert first['result'] is None and first['latest'] is None
    assert 'session_id' not in first


async def test_no_ownership_does_not_install_tasks_or_start_runs(principal):
    service = make_service(principal, [])
    service.briefings.owner.side_effect = HTTPException(404)
    result = await cards(service, principal)
    assert len(result['items']) == 3
    assert all(item['state'] == 'NOT_GENERATED' and item['result'] is None for item in result['items'])
    assert not service.store.queries
    service.java.assert_awaited_once()


@pytest.mark.parametrize('state', ['RUNNING', 'QUEUED', 'FAILED', 'CANCELLED'])
async def test_incomplete_and_in_progress_outputs_are_never_homepage_conclusions(principal, state):
    service = make_service(principal, [briefing_run(status=state)])
    first = (await cards(service, principal))['items'][0]
    assert first['state'] == state and first['result'] is None


async def test_empty_review_never_reuses_prior_experiment_conclusions(principal):
    old = briefing_run('experiment-review')
    empty = briefing_run('experiment-review', turn=2)
    empty['briefing_snapshot']['sections'] = {'EXPERIMENT': []}
    service = make_service(principal, [old, empty], kind='experiment-review')
    item = (await cards(service, principal))['items'][2]
    assert item['state'] == 'NO_ACTIVITY'
    assert item['result']['run_id'] == empty['id']
    assert item['result']['headline'] == '本期无新增实验活动'
    assert item['result']['actions'] == []


async def test_open_rechecks_owner_period_and_current_source_access(principal):
    run = briefing_run()
    service = make_service(principal, [run])
    opened = await open_result(service, principal, 'project-morning', run['id'])
    assert opened['session_id'] == 'briefing-owned' and opened['turn'] == 1
    service.authorized_topic.side_effect = HTTPException(403)
    with pytest.raises(HTTPException) as denied:
        await open_result(service, principal, 'project-morning', run['id'])
    assert denied.value.status_code == 403
    with pytest.raises(HTTPException):
        await open_result(service, principal.model_copy(update={'actor_id': uuid4()}), 'project-morning', run['id'])


@pytest.mark.parametrize('change', [{'dsh_session_id': 'other'}, {'dsh_turn': 3}, {'session_deleted': True}])
async def test_open_cannot_substitute_unrelated_or_deleted_session(principal, change):
    run = {**briefing_run(), **change}
    service = make_service(principal, [run])
    with pytest.raises(HTTPException):
        await open_result(service, principal, 'project-morning', run['id'])
    service.authorized_topic.assert_not_awaited()


async def test_http_endpoint_uses_delegated_owner_and_rejects_background_scope(settings, principal):
    run = briefing_run()
    service = make_service(principal, [run])
    service.settings = settings
    app = create_app(settings=settings, store=service.store, service=service)
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url='http://test') as client:
        response = await client.get('/internal/v1/harness/briefings', headers={
            'X-Jsd-Agent-Ticket': issue(settings.shared_secret.get_secret_value(), principal, 'control')})
        assert response.status_code == 200 and response.json()['items'][0]['state'] == 'READY'
        response = await client.get('/internal/v1/harness/briefings')
        assert response.status_code == 401
        background = principal.model_copy(update={'scope': 'research-briefings', 'automation_grant_id': str(uuid4())})
        response = await client.get('/internal/v1/harness/briefings', headers={
            'X-Jsd-Agent-Ticket': issue(settings.shared_secret.get_secret_value(), background, 'control')})
        assert response.status_code == 403
