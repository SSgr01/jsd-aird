from datetime import datetime, timezone
from types import SimpleNamespace
from uuid import uuid4

import pytest
from fastapi import HTTPException

from aird_agent.hosts.jsd.briefings import Briefings, coverage, ref_key, window
from aird_agent.server.security import issue, verify


def test_beijing_week_boundary_and_success_cursor():
    now = datetime(2026, 11, 8, 23, 59, tzinfo=timezone.utc)
    assert window('research-weekly', now=now)['start'] == '2026-11-08T16:00:00+00:00'
    assert window('experiment-review', now=now)['start'] == '2026-11-01T23:59:00+00:00'
    assert window('experiment-review', now=now, last_success='2026-11-06T09:31:00+00:00')['start'] == '2026-11-06T09:31:00+00:00'
    assert window('project-morning', now=now)['start'] is None


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


@pytest.mark.parametrize('status', ['FAILED', 'CANCELLED', 'UNKNOWN'])
async def test_unsuccessful_delivery_never_advances_review_position(settings, store, principal, status):
    async def java(*args): return {}
    adapter = Briefings(SimpleNamespace(settings=settings, store=store, java=java))
    plan = await adapter.install(principal)
    task = {**plan['tasks']['experiment-review'], 'native_id': 'native-review', 'status': 'active'}
    await adapter.reconcile(principal, {'tasks': [task]})
    body = {'session_id': task['session_id'], 'turn': 1, 'automation': {'builtin_id': 'experiment-review',
        'task_id': 'native-review', 'message_id': 'delivered', 'delivered_at': '2026-11-07T01:00:00Z'}}
    delivery = await adapter.binding(principal, body)
    async with store.transaction(principal, 'finish') as conn:
        await adapter.finish(principal, {'id': str(uuid4()), 'status': status, 'automation': delivery}, conn)
    saved = await adapter.owner(principal)
    assert 'last_success' not in saved['tasks']['experiment-review']
    assert (await store.get(principal, 'briefing-delivery', delivery['id']))['business_status'] == status


async def test_successful_delivery_is_bound_once_and_position_never_moves_backwards(settings, store, principal):
    async def java(*args): return {}
    adapter = Briefings(SimpleNamespace(settings=settings, store=store, java=java))
    plan = await adapter.install(principal)
    task = {**plan['tasks']['experiment-review'], 'native_id': 'native-review', 'status': 'active'}
    await adapter.reconcile(principal, {'tasks': [task]})
    body = {'session_id': task['session_id'], 'turn': 1, 'automation': {'builtin_id': 'experiment-review',
        'task_id': task['native_id'], 'message_id': 'first', 'delivered_at': '2026-11-07T01:00:00Z'}}
    delivery = await adapter.binding(principal, body)
    assert (await adapter.binding(principal, body))['id'] == delivery['id']
    with pytest.raises(HTTPException, match='DELIVERY_ALREADY_BOUND'):
        await adapter.binding(principal, {**body, 'turn': 2})
    complete = {'frozen': True, 'sections': {'EXPERIMENT': []}}
    async with store.transaction(principal, 'finish') as conn:
        await adapter.finish(principal, {'id': str(uuid4()), 'status': 'SUCCEEDED',
            'automation': delivery, 'briefing_snapshot': complete}, conn)
    position = (await adapter.owner(principal))['tasks']['experiment-review']['last_success']
    async with store.transaction(principal, 'finish') as conn:
        await adapter.finish(principal, {'id': str(uuid4()), 'status': 'SUCCEEDED',
            'automation': {**delivery, 'window': {**delivery['window'], 'end': '2020-01-01T00:00:00+00:00'}},
            'briefing_snapshot': complete}, conn)
    assert (await adapter.owner(principal))['tasks']['experiment-review']['last_success'] == position
    stale = principal.model_copy(update={'automation_grant_id': plan['id'], 'scope': 'research-briefings', 'auth_version': 2})
    with pytest.raises(HTTPException, match='BACKGROUND_AUTHORIZATION_REVOKED'):
        await adapter.check(stale)
    other = principal.model_copy(update={'actor_id': uuid4()})
    with pytest.raises(HTTPException):
        await adapter.owner(other)


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
