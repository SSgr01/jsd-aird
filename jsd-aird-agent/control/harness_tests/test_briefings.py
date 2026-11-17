from datetime import datetime, timezone
from types import SimpleNamespace
from uuid import uuid4
from unittest.mock import AsyncMock

import httpx
import pytest
from fastapi import HTTPException

from aird_agent.hosts.jsd.briefings import Briefings, coverage, ref_key, window
from aird_agent.server.security import issue, verify


def test_beijing_week_boundary_and_success_cursor():
    now = datetime(2026, 11, 8, 23, 59, tzinfo=timezone.utc)
    assert window('research-weekly', now=now)['start'] == '2026-11-08T16:00:00+00:00'
    assert window('experiment-review', now=now)['start'] == '2026-11-01T23:59:00+00:00'
    assert window('experiment-review', now=now, last_success='2026-11-06T09:31:00+00:00')['start'] == '2026-11-06T09:31:00+00:00'
    assert window('project-morning', now=now)['start'] == '2026-11-07T23:59:00+00:00'
    assert window('project-morning', now=now, last_success='2026-11-06T09:31:00+00:00')['start'] == '2026-11-06T09:31:00+00:00'


def test_old_interactive_tokens_and_scoped_background_tokens(principal):
    secret = 's' * 32
    ticket = issue(secret, principal, 'control')
    assert verify(secret, ticket, 'control')[0] == principal
    scoped = principal.model_copy(update={'automation_grant_id': str(uuid4()), 'scope': 'research-briefings'})
    assert verify(secret, issue(secret, scoped, 'control', ttl=300), 'control')[0] == scoped
    with pytest.raises(HTTPException):
        verify(secret, ticket, 'automation-exchange')


def test_partial_experiment_pages_and_filtered_rows_do_not_count_as_complete():
    ref = {'type': 'EXPERIMENT', 'id': 'experiment', 'version_id': 'version-one'}
    snapshot = {'frozen': True, 'sections': {'EXPERIMENT': [{'ref': ref}]}, 'seen': {'EXPERIMENT': [0]}, 'reads': {}}
    run = {'briefing_snapshot': snapshot}
    Briefings.record_read(run, 'business_read', {'ref': ref}, {'candidates': [{}], 'reading': {'section': 'overview', 'section_counts': {'samples': 2, 'formulaItems': 9, 'events': 0}}})
    assert not coverage(snapshot)['complete']
    Briefings.record_read(run, 'business_read', {'ref': ref, 'section': 'samples'}, {'candidates': [{}], 'reading': {'section': 'samples'}})
    Briefings.record_read(run, 'business_read', {'ref': ref, 'section': 'formulaItems', 'offset': 8}, {'candidates': [{}], 'reading': {'section': 'formulaItems'}})
    assert not coverage(snapshot)['complete']
    Briefings.record_read(run, 'business_read', {'ref': ref, 'section': 'formulaItems', 'sample_key': 'A'}, {'candidates': [{}], 'reading': {'section': 'formulaItems'}})
    assert not coverage(snapshot)['complete']
    Briefings.record_read(run, 'business_read', {'ref': ref, 'section': 'formulaItems'}, {'candidates': [{}], 'reading': {'section': 'formulaItems', 'next_offset': 8}})
    assert coverage(snapshot)['complete']
    other = {**ref, 'version_id': 'version-two'}
    assert ref_key(ref) != ref_key(other)


def test_overlapping_pages_cover_real_rows_but_gaps_do_not():
    ref = {'type': 'EXPERIMENT', 'id': 'experiment', 'version_id': 'version'}
    snapshot = {'frozen': True, 'sections': {'EXPERIMENT': [{'ref': ref}]}, 'seen': {'EXPERIMENT': [0]},
        'reads': {ref_key(ref): {'overview': True, 'counts': {'testResults': 40},
            'pages': {'testResults': {'0': 19, '18': 37, '36': None}}}}}
    assert coverage(snapshot)['complete']
    snapshot['reads'][ref_key(ref)]['pages']['testResults'].pop('18')
    assert not coverage(snapshot)['complete']


def test_saved_experiment_body_requires_all_pages_and_download_does_not_replace_reading():
    ref = {'type': 'EXPERIMENT', 'id': 'experiment', 'version_id': 'version'}
    snapshot = {'frozen': True, 'sections': {'EXPERIMENT': [{'ref': ref}]},
        'seen': {'EXPERIMENT': [0]}, 'reads': {}}
    run = {'briefing_snapshot': snapshot}
    Briefings.record_read(run, 'business_read', {'ref': ref}, {'candidates': [{}],
        'reading': {'section': 'overview', 'section_counts': {'content': 6, 'events': 0}}})
    Briefings.record_read(run, 'business_read', {'ref': ref, 'include_file': True},
        {'candidates': [{}], 'business_file': {'name': 'original.xlsx'},
         'reading': {'section': 'overview', 'section_counts': {'content': 6, 'events': 0}}})
    assert not coverage(snapshot)['complete']
    for offset, next_offset in ((0, 2), (4, None)):
        reading = {'section': 'content', 'total': 6, 'returned': 2}
        if next_offset is not None:
            reading['next_offset'] = next_offset
        Briefings.record_read(run, 'business_read', {'ref': ref, 'section': 'content', 'offset': offset},
            {'candidates': [{}], 'reading': reading})
    assert not coverage(snapshot)['complete']
    Briefings.record_read(run, 'business_read', {'ref': ref, 'section': 'content', 'offset': 2},
        {'candidates': [{}], 'reading': {'section': 'content', 'next_offset': 4, 'total': 6, 'returned': 2}})
    assert coverage(snapshot)['complete']


def test_complete_version_read_counts_all_server_confirmed_sections():
    ref = {'type': 'EXPERIMENT', 'id': 'experiment', 'version_id': 'version'}
    snapshot = {'frozen': True, 'sections': {'EXPERIMENT': [{'ref': ref}]}, 'seen': {'EXPERIMENT': [0]}}
    run = {'briefing_snapshot': snapshot}
    result = {'candidates': [{}], 'reading': {'section': 'record', 'complete': True,
        'section_counts': {'samples': 2, 'formulaItems': 6, 'testResults': 102}}}
    Briefings.record_read(run, 'business_read', {'ref': ref, 'section': 'record'}, result)
    assert coverage(snapshot)['complete']
    result['reading']['complete'] = False
    run['briefing_snapshot']['reads'] = {}
    Briefings.record_read(run, 'business_read', {'ref': ref, 'section': 'record'}, result)
    assert not coverage(snapshot)['complete']


def test_mutable_draft_pins_come_from_server_and_a_changed_body_never_counts():
    ref = {'type': 'EXPERIMENT', 'id': 'experiment', 'version_id': 'version', 'version_hash': 'first'}
    snapshot = {'frozen': True, 'sections': {'EXPERIMENT': [{'ref': ref}]}, 'seen': {'EXPERIMENT': [0]}}
    run = {'briefing_snapshot': snapshot}
    args = {'ref': {'type': 'EXPERIMENT', 'id': 'experiment'}, 'section': 'record'}
    assert Briefings.read_request(run, args)['ref'] == ref
    assert args['ref'] == {'type': 'EXPERIMENT', 'id': 'experiment'}
    with pytest.raises(HTTPException, match='BRIEFING_VERSION_CHANGED'):
        Briefings.read_request(run, {'ref': {**ref, 'version_hash': 'changed'}})
    result = {'candidates': [{'ref': {**ref, 'version_hash': 'changed'}}],
        'reading': {'section': 'record', 'complete': True, 'section_counts': {'samples': 2}}}
    Briefings.record_read(run, 'business_read', args, result)
    assert not coverage(snapshot)['complete']
    result['candidates'][0]['ref'] = ref
    Briefings.record_read(run, 'business_read', args, result)
    assert coverage(snapshot)['complete']


async def test_default_install_persists_deletion_and_rejects_revoked_grants(settings, store, principal):
    async def java(*args):
        return {'pack_ids': ['core', 'business-data']}
    service = SimpleNamespace(settings=settings, store=store, java=java)
    adapter = Briefings(service)
    plan = await adapter.install(principal)
    again = await adapter.install(principal)
    assert plan['id'] == again['id'] and plan['tasks'] == again['tasks']
    task = {**plan['tasks']['project-morning'], 'native_id': 'native-task', 'status': 'deleted'}
    await adapter.reconcile(principal, {'tasks': [task]})
    assert (await adapter.install(principal))['tasks']['project-morning']['status'] == 'deleted'
    scoped = principal.model_copy(update={'automation_grant_id': plan['id'], 'scope': 'research-briefings'})
    await adapter.check(scoped)
    with pytest.raises(HTTPException):
        await adapter.binding(scoped, {'session_id': task['session_id'], 'turn': 1})
    await adapter.revoke(principal)
    with pytest.raises(HTTPException):
        await adapter.check(scoped)
    assert (await adapter.install(principal))['grant_status'] == 'REVOKED'


async def test_confirmed_iam_refusal_stops_renewals_and_interactive_reauthorization_keeps_tasks(settings, store, principal):
    java = AsyncMock(return_value={})
    post = AsyncMock(return_value=httpx.Response(403, json={'code': 'PERMISSION_DENIED',
        'data': {'reason': 'BACKGROUND_AUTHORIZATION_REQUIRED'}}))
    adapter = Briefings(SimpleNamespace(settings=settings, store=store, java=java, http=SimpleNamespace(post=post)))
    plan = await adapter.install(principal)
    async with store.transaction(principal, 'configure') as conn:
        owner = await adapter.owner(principal, conn)
        owner['tasks']['project-morning'].update(native_id='morning', status='paused', last_success='2026-11-13T01:00:00+00:00')
        owner['tasks']['research-weekly'].update(native_id='weekly', status='deleted')
        owner = await store.update(principal, 'briefing-owner', owner, conn)
    tasks = owner['tasks']
    with pytest.raises(HTTPException, match='BACKGROUND_AUTHORIZATION_REQUIRED'):
        await adapter.ticket(plan['id'])
    assert (await adapter.owner(principal))['grant_status'] == 'AUTHORIZATION_REQUIRED'
    assert plan['id'] not in [item['id'] for item in (await adapter.owners())['owners']]
    with pytest.raises(HTTPException):
        await adapter.ticket(plan['id'])
    assert post.await_count == 1
    current = principal.model_copy(update={'auth_version': principal.auth_version + 1})
    renewed = await adapter.install(current)
    assert renewed['grant_status'] == 'ACTIVE' and renewed['auth_version'] == current.auth_version
    assert renewed['principal']['auth_version'] == current.auth_version
    assert renewed['grant_generation'] != plan['grant_generation'] and renewed['tasks'] == tasks
    with pytest.raises(HTTPException, match='STALE_INTERACTIVE_AUTHORIZATION'):
        await adapter.install(principal)
    assert (await adapter.owner(current))['auth_version'] == current.auth_version
    # A failed foreground permission check must not reactivate the grant.
    with pytest.raises(HTTPException):
        await adapter.ticket(plan['id'])
    java.side_effect = HTTPException(403, 'PERMISSION_DENIED')
    with pytest.raises(HTTPException):
        await adapter.install(current)
    assert (await adapter.owner(current))['grant_status'] == 'AUTHORIZATION_REQUIRED'


@pytest.mark.parametrize('status,body', [(401, {'code': 'AUTH_REQUIRED'}), (403, {'code': 'PERMISSION_DENIED'}),
    (503, {'data': {'reason': 'BACKGROUND_AUTHORIZATION_REQUIRED'}}), (502, None)])
async def test_service_refusal_does_not_deactivate_user_grant(settings, store, principal, status, body):
    post = AsyncMock(return_value=httpx.Response(status, json=body))
    adapter = Briefings(SimpleNamespace(settings=settings, store=store, java=AsyncMock(return_value={}), http=SimpleNamespace(post=post)))
    plan = await adapter.install(principal)
    with pytest.raises(HTTPException) as error:
        await adapter.ticket(plan['id'])
    assert error.value.status_code == status
    assert (await adapter.owner(principal))['grant_status'] == 'ACTIVE'


async def test_late_iam_refusal_cannot_disable_new_grant_at_same_auth_version(settings, store, principal):
    adapter = Briefings(SimpleNamespace(settings=settings, store=store, java=AsyncMock(return_value={})))
    plan = await adapter.install(principal)
    async def exchange(*args, **kwargs):
        async with store.transaction(principal, 'pause') as conn:
            current = await adapter.owner(principal, conn)
            current['grant_status'] = 'AUTHORIZATION_REQUIRED'
            await store.update(principal, 'briefing-owner', current, conn)
        await adapter.install(principal)
        return httpx.Response(403, json={'data': {'reason': 'BACKGROUND_AUTHORIZATION_REQUIRED'}})
    adapter.service.http = SimpleNamespace(post=exchange)
    with pytest.raises(HTTPException):
        await adapter.ticket(plan['id'])
    current = await adapter.owner(principal)
    assert current['grant_status'] == 'ACTIVE' and current['grant_generation'] != plan['grant_generation']


async def test_exchange_success_cannot_outlive_concurrent_revocation(settings, store, principal):
    adapter = Briefings(SimpleNamespace(settings=settings, store=store, java=AsyncMock(return_value={})))
    plan = await adapter.install(principal)
    async def exchange(*args, **kwargs):
        await adapter.revoke(principal)
        return httpx.Response(200, json={'ticket': 'must-not-be-returned'})
    adapter.service.http = SimpleNamespace(post=exchange)
    with pytest.raises(HTTPException, match='BACKGROUND_AUTHORIZATION_CHANGED'):
        await adapter.ticket(plan['id'])


async def test_connection_outage_keeps_grant_and_current_exchange_can_succeed(settings, store, principal):
    scoped = principal.model_dump(mode='json')
    post = AsyncMock(side_effect=[httpx.ConnectError('connection reset'), httpx.Response(200, json={**scoped, 'ticket': 'current'})])
    adapter = Briefings(SimpleNamespace(settings=settings, store=store, java=AsyncMock(return_value={}), http=SimpleNamespace(post=post)))
    plan = await adapter.install(principal)
    with pytest.raises(httpx.ConnectError):
        await adapter.ticket(plan['id'])
    assert (await adapter.owner(principal))['grant_status'] == 'ACTIVE'
    assert (await adapter.ticket(plan['id']))['ticket'] == 'current'


@pytest.mark.parametrize('kind', ['experiment-review', 'project-morning'])
@pytest.mark.parametrize('status', ['FAILED', 'CANCELLED', 'UNKNOWN'])
async def test_unsuccessful_delivery_never_advances_review_position(settings, store, principal, status, kind):
    async def java(*args): return {}
    adapter = Briefings(SimpleNamespace(settings=settings, store=store, java=java))
    plan = await adapter.install(principal)
    task = {**plan['tasks'][kind], 'native_id': 'native-review', 'status': 'active'}
    await adapter.reconcile(principal, {'tasks': [task]})
    body = {'session_id': task['session_id'], 'turn': 1, 'automation': {'builtin_id': kind,
        'task_id': 'native-review', 'message_id': 'delivered', 'delivered_at': '2026-11-07T01:00:00Z'}}
    delivery = await adapter.binding(principal, body)
    async with store.transaction(principal, 'finish') as conn:
        await adapter.finish(principal, {'id': str(uuid4()), 'status': status, 'automation': delivery}, conn)
    saved = await adapter.owner(principal)
    assert 'last_success' not in saved['tasks'][kind]
    assert (await store.get(principal, 'briefing-delivery', delivery['id']))['business_status'] == status


@pytest.mark.parametrize('kind', ['experiment-review', 'project-morning'])
async def test_successful_delivery_is_bound_once_and_position_never_moves_backwards(settings, store, principal, kind):
    async def java(*args): return {}
    adapter = Briefings(SimpleNamespace(settings=settings, store=store, java=java))
    plan = await adapter.install(principal)
    task = {**plan['tasks'][kind], 'native_id': 'native-review', 'status': 'active'}
    await adapter.reconcile(principal, {'tasks': [task]})
    body = {'session_id': task['session_id'], 'turn': 1, 'automation': {'builtin_id': kind,
        'task_id': task['native_id'], 'message_id': 'first', 'delivered_at': '2026-11-07T01:00:00Z'}}
    delivery = await adapter.binding(principal, body)
    assert (await adapter.binding(principal, body))['id'] == delivery['id']
    with pytest.raises(HTTPException, match='DELIVERY_ALREADY_BOUND'):
        await adapter.binding(principal, {**body, 'turn': 2})
    complete = {'frozen': True, 'sections': {'EXPERIMENT': []}}
    async with store.transaction(principal, 'finish') as conn:
        await adapter.finish(principal, {'id': str(uuid4()), 'status': 'SUCCEEDED',
            'automation': delivery, 'briefing_snapshot': complete}, conn)
    position = (await adapter.owner(principal))['tasks'][kind]['last_success']
    async with store.transaction(principal, 'finish') as conn:
        await adapter.finish(principal, {'id': str(uuid4()), 'status': 'SUCCEEDED',
            'automation': {**delivery, 'window': {**delivery['window'], 'end': '2020-01-01T00:00:00+00:00'}},
            'briefing_snapshot': complete}, conn)
    assert (await adapter.owner(principal))['tasks'][kind]['last_success'] == position
    stale = principal.model_copy(update={'automation_grant_id': plan['id'], 'scope': 'research-briefings', 'auth_version': 2})
    with pytest.raises(HTTPException, match='BACKGROUND_AUTHORIZATION_REVOKED'):
        await adapter.check(stale)
    other = principal.model_copy(update={'actor_id': uuid4()})
    with pytest.raises(HTTPException):
        await adapter.owner(other)


async def test_truncated_daily_index_never_advances_and_next_day_retry_keeps_last_success(settings, store, principal, monkeypatch):
    now = datetime(2026, 11, 13, 0, 0, tzinfo=timezone.utc)
    monkeypatch.setattr('aird_agent.hosts.jsd.briefings.window',
        lambda kind, last_success=None: window(kind, now=now, last_success=last_success))
    async def java(*args): return {}
    adapter = Briefings(SimpleNamespace(settings=settings, store=store, java=java))
    plan = await adapter.install(principal)
    task = {**plan['tasks']['project-morning'], 'native_id': 'native-morning', 'status': 'active'}
    await adapter.reconcile(principal, {'tasks': [task]})
    async with store.transaction(principal, 'last-success') as conn:
        owner = await adapter.owner(principal, conn)
        owner['tasks']['project-morning']['last_success'] = '2026-11-12T00:00:00+00:00'
        await store.update(principal, 'briefing-owner', owner, conn)
    body = {'session_id': task['session_id'], 'turn': 1, 'automation': {'builtin_id': 'project-morning',
        'task_id': task['native_id'], 'message_id': 'morning', 'delivered_at': '2026-11-13T00:00:00Z'}}
    delivery = await adapter.binding(principal, body)
    assert delivery['window']['start'] == '2026-11-12T00:00:00+00:00'
    async with store.transaction(principal, 'finish') as conn:
        await adapter.finish(principal, {'id': str(uuid4()), 'status': 'SUCCEEDED', 'automation': delivery,
            'briefing_snapshot': {'frozen': True, 'sections': {'PROJECT': [], 'RELATED_ACTIVITY': []}, 'truncated': True}}, conn)
    assert (await adapter.owner(principal))['tasks']['project-morning']['last_success'] == delivery['window']['start']
    now = datetime(2026, 11, 14, 0, 0, tzinfo=timezone.utc)
    retry = await adapter.binding(principal, {**body, 'turn': 2, 'automation': {**body['automation'],
        'message_id': 'next-morning', 'delivered_at': '2026-11-14T00:00:00Z'}})
    assert retry['window']['start'] == delivery['window']['start']
    assert retry['window']['end'] == '2026-11-14T00:00:00+00:00'


async def test_activity_index_change_during_pagination_never_freezes_incomplete_context(settings, store, principal):
    async def host_call(user, action, args, run_id):
        assert action == 'get_research_briefing_context'
        ref = {'type': 'EXPERIMENT', 'id': str(uuid4()), 'version_id': str(uuid4())}
        return {'candidates': [{'ref': ref}], 'index_revision': 'first' if args['offset'] == 0 else 'changed',
            **({'next_offset': 20} if args['offset'] == 0 else {})}
    adapter = Briefings(SimpleNamespace(settings=settings, store=store, host_call=host_call))
    async with store.transaction(principal, 'context') as conn:
        run = await store.create(principal, 'run', {'status': 'RUNNING'}, conn)
    with pytest.raises(HTTPException, match='BRIEFING_INDEX_CHANGED'):
        await adapter.context(SimpleNamespace(run=run, principal=principal), {'task_type': 'experiment-review'})
    assert 'briefing_snapshot' not in await store.get(principal, 'run', run['id'])


@pytest.mark.parametrize('sizes', [(3, 3, 9, 8), (0, 0, 0, 0)])
async def test_activity_totals_use_all_frozen_sections_not_the_selected_page(settings, store, principal, sizes):
    sections = dict(zip(('PROJECT', 'STAGE', 'TASK', 'EXPERIMENT'), sizes))
    sections['RELATED_ACTIVITY'] = 0

    async def host_call(user, action, args, run_id):
        assert action == 'get_research_briefing_context'
        kind = args['section']
        return {'index_revision': kind, 'candidates': [
            {'ref': {'type': kind, 'id': f'{kind}-{index}'}} for index in range(sections[kind])
        ]}

    adapter = Briefings(SimpleNamespace(settings=settings, store=store, host_call=host_call))
    async with store.transaction(principal, 'context') as conn:
        run = await store.create(principal, 'run', {'status': 'RUNNING'}, conn)
    context = SimpleNamespace(run=run, principal=principal)
    first = await adapter.context(context, {'task_type': 'research-weekly', 'section': 'EXPERIMENT', 'limit': 2})
    assert len(first['candidates']) == min(sizes[3], 2)
    assert first['index_summary']['counts'] == sections
    assert first['index_summary']['total'] == sum(sizes)
    assert f'共 {sum(sizes)} 个业务对象' in first['index_summary']['text']
    assert first['index_summary']['complete'] is True
    following = await adapter.context(context, {'task_type': 'research-weekly', 'section': 'PROJECT', 'offset': 2, 'limit': 1})
    assert following['index_summary'] == first['index_summary']


@pytest.mark.parametrize('kind', ['project-morning', 'research-weekly'])
async def test_child_only_activity_merges_unchanged_parent_and_only_requires_index_pages(settings, store, principal, kind):
    project = {'ref': {'type': 'PROJECT', 'id': str(uuid4()), 'revision': '7'}}
    events = [{'project_id': project['ref']['id'], 'resource_type': 'MEETING_MINUTES',
        'title': f'项目例会 {index}', 'ref': {'type': 'MEETING_MINUTES', 'id': str(uuid4()), 'project_id': project['ref']['id']}}
        for index in range(3)]

    async def host_call(user, action, args, run_id):
        if args['section'] != 'RELATED_ACTIVITY':
            return {'candidates': [], 'index_revision': 'empty'}
        start = args['offset']
        return {'candidates': events[start:start+2], 'related_projects': [project], 'index_revision': 'children',
            **({'next_offset': 2} if start == 0 else {})}

    adapter = Briefings(SimpleNamespace(settings=settings, store=store, host_call=host_call))
    async with store.transaction(principal, 'context') as conn:
        run = await store.create(principal, 'run', {'status': 'RUNNING'}, conn)
    context = SimpleNamespace(run=run, principal=principal)
    result = await adapter.context(context, {'task_type': kind, 'section': 'RELATED_ACTIVITY', 'limit': 2})
    assert result['candidates'] == [] and result['activities'] == events[:2] and result['next_offset'] == 2
    assert result['next_request'] == {'task_type': kind, 'section': 'RELATED_ACTIVITY', 'offset': 2, 'limit': 2}
    assert result['next_request'] in result['pending_index_requests']
    assert result['index_summary']['counts']['PROJECT'] == 1
    assert not result['coverage']['complete']
    result = await adapter.context(context, result['next_request'])
    result = await adapter.context(context, {'task_type': kind, 'section': 'PROJECT'})
    assert result['candidates'] == [project]
    current = await store.get(principal, 'run', run['id'])
    Briefings.record_read(current, 'business_read', {'ref': project['ref']}, {'candidates': [project]})
    assert coverage(current['briefing_snapshot'])['complete']  # Attachments are the Agent's choice.
    current['briefing_snapshot']['truncated'] = True
    assert not coverage(current['briefing_snapshot'])['complete']


async def test_only_completed_bound_periods_use_historical_access(settings, store, principal):
    from aird_agent.hosts.jsd.bindings import JsdBindings

    async def java(*args): return {}
    service = SimpleNamespace(settings=settings, store=store, java=java)
    service.briefings = adapter = Briefings(service)
    plan = await adapter.install(principal)
    task = {**plan['tasks']['experiment-review'], 'native_id': 'native-review', 'status': 'active'}
    await adapter.reconcile(principal, {'tasks': [task]})
    body = {'session_id': task['session_id'], 'turn': 1, 'automation': {'builtin_id': 'experiment-review',
        'task_id': task['native_id'], 'message_id': 'first', 'delivered_at': '2026-11-07T01:00:00Z'}}
    delivery = await adapter.binding(principal, body)
    run = {'status': 'SUCCEEDED', 'automation': delivery, 'dsh_session_id': task['session_id'], 'dsh_turn': 1,
        'source_refs': [{'kind': 'BUSINESS_RECORD', 'resource_type': 'EXPERIMENT', 'content_sha256': 'old'}]}
    bindings = JsdBindings()
    bindings.runtime = service
    assert (await bindings.source_authorization(principal, run, historical=True))['purpose'] == 'briefing-history'
    assert 'purpose' not in await bindings.source_authorization(principal, run)
    for changed in ({'status': 'RUNNING'}, {'dsh_turn': 2}, {'dsh_session_id': 'ordinary-session'}, {'automation': None}):
        assert 'purpose' not in await bindings.source_authorization(principal, {**run, **changed}, historical=True)
