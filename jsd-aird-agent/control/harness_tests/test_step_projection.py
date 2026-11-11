"""Incremental transport must preserve business admission and durable receipts."""

import copy
import json
from uuid import uuid4

import httpx
import pytest
from fastapi import HTTPException
from test_business_dispatch import read_source
from test_business_dispatch import setup as setup

from aird_agent.evidence.links import citation_links
from aird_agent.server.api import create_app
from aird_agent.server.security import issue


async def bind(store, principal, run):
    async with store.transaction(principal, run['id']) as conn:
        current = await store.get(principal, 'run', run['id'], conn)
        current.update(dsh_generation_started=True, retrieval_snapshots={'large': 'x' * 1500000})
        current = await store.update(principal, 'run', current, conn)
        conversation = await store.get(principal, 'conversation', run['conversation_id'], conn)
        conversation.update(profile='agent', dsh_session_id=run['dsh_session_id'], active_run=run['id'])
        await store.update(principal, 'conversation', conversation, conn)
    return current


async def test_native_admission_survives_a_concurrent_inbox_projection(setup, principal, store, monkeypatch):
    service, _, run, _ = setup
    await bind(store, principal, run)
    async with store.transaction(principal, run['id']) as conn:
        current = await store.get(principal, 'run', run['id'], conn)
        current.update(status='CANCELLED',dsh_turn=1)
        await store.update(principal, 'run', current, conn)
        conversation = await store.get(principal, 'conversation', run['conversation_id'], conn)
        conversation['engine_version']='dsh-v1'
        await store.update(principal, 'conversation', conversation, conn)
    original = service.create_run

    async def project_then_create(owner, request, key, **kwargs):
        # Queue metadata changes after register_step read the conversation.
        await service.project_event(owner, {'session_id':run['dsh_session_id'],
            'event':{'seq':100,'type':'agent/inbox/spliced','data':{}}})
        return await original(owner, request, key, **kwargs)

    monkeypatch.setattr(service, 'create_run', project_then_create)
    admitted = await service.register_step(principal, {'session_id':run['dsh_session_id'],
        'turn':2,'profile':'agent','request':'Only acknowledge the new input',
        'input_signature':str(uuid4()),'admission_only':True})
    assert admitted['status']=='QUEUED' and admitted['run_id'] != run['id']


async def test_lost_admission_reply_and_cancel_reuse_the_durable_input_identity(setup, principal, store):
    service, _, run, _ = setup
    await bind(store,principal,run)
    async with store.transaction(principal,run['id']) as conn:
        current=await store.get(principal,'run',run['id'],conn)
        current.update(status='QUEUED',started_at=None)
        await store.update(principal,'run',current,conn)
    request={'session_id':run['dsh_session_id'],'turn':1,'profile':'agent',
        'input_signature':str(uuid4()),'admission_only':True}
    admitted=await service.register_step(principal,request)
    cancelled=await service.register_step(principal,{**request,'cancelled':True})
    retried=await service.register_step(principal,request)
    assert admitted['run_id']==cancelled['run_id']==retried['run_id']
    assert cancelled['status']==retried['status']=='CANCELLED'
    assert retried['admission']=='RELEASED'


async def test_binding_delta_omits_ledger_and_repeated_links_but_keeps_authorization(setup, principal, store):
    service, host, run, _ = setup
    await read_source(service, host, principal, run)
    current = await bind(store, principal, run)
    body = {'session_id':run['dsh_session_id'], 'turn':1, 'profile':'agent', 'binding_protocol':2}
    first = await service.register_step(principal, body)
    assert not {'evidence', 'retrieval_snapshots', 'principal', 'dsh_usage'} & first.keys()
    assert first['citation_links'] == citation_links(current['evidence'], service.settings.public_url, quote_aliases=True)
    assert len(json.dumps(first)) < 50000
    cursor = {**body, 'binding_etags':first['binding_etags'], 'known_citation_evidence_ids':first['citation_evidence_ids']}
    second = await service.register_step(principal, cursor)
    assert not {'tools', 'active_tools', 'instructions', 'context'} & second.keys()
    assert second['citation_links'] == {} and second['citation_evidence_ids'] == []
    assert len(json.dumps(second)) < 1500
    assert (await store.get(principal, 'run', run['id']))['revision'] == current['revision']
    async with store.transaction(principal, run['id']) as conn:
        changed = await store.get(principal, 'run', run['id'], conn)
        old = next(iter(changed['evidence'].values()))
        added = copy.deepcopy(old)
        added['source']['anchor']['page'] = 3
        changed['evidence']['e' + 'a' * 24] = added
        changed['active_tools'] = ['document_read']
        changed['registered_outputs'] = [{'kind':'note', 'id':'saved-receipt'}]
        await store.update(principal, 'run', changed, conn)
    third = await service.register_step(principal, cursor)
    assert third['active_tools'] == ['document_read']
    assert third['citation_evidence_ids'] == ['e' + 'a' * 24]
    assert '#page=3' in third['citation_links']['e' + 'a' * 24]['url']
    assert 'context' in third
    # A cursor loss requests a fresh binding, including quote aliases.
    recovered = await service.register_step(principal, body)
    assert all(recovered['citation_links'].get(key) == value for key, value in first['citation_links'].items())
    host.denied = True
    with pytest.raises(HTTPException, match='TEST_ACCESS_REVOKED'):
        await service.register_step(principal, cursor)


@pytest.mark.parametrize('reason,status', [('aborted','CANCELLED'), ('error','FAILED')])
async def test_progress_keeps_run_untouched_until_critical_event(setup, principal, store, reason, status):
    service, _, run, _ = setup
    current = await bind(store, principal, run)
    sid = run['dsh_session_id']
    async def event(seq, kind, **data):
        return await service.project_event(principal, {'session_id':sid, 'event':{'seq':seq,'type':kind,'data':{'turn':1, **data}}})
    await event(100, 'request/context', large='discarded')
    await event(99, 'tool/result')  # replay is idempotent
    unchanged = await store.get(principal, 'run', run['id'])
    assert unchanged == current
    conversation = await store.get(principal, 'conversation', run['conversation_id'])
    assert conversation['dsh_seq'] == 100
    pending = await service.project_event(principal, {'session_id':sid, 'event':{'seq':101, 'type':'step/start','data':{'turn':2}}})
    assert pending['pending_registration']
    assert (await store.get(principal, 'conversation', run['conversation_id']))['dsh_seq'] == 100
    await event(102, 'assistant/message', message={'content':[{'type':'tool-call','name':'present','arguments':{'files':[{'path':'out/result.pdf'}]}}]})
    assert (await store.get(principal, 'run', run['id']))['required_artifacts'] == ['result.pdf']
    await event(103, 'turn/end', reason={'kind':reason})
    final = await store.get(principal, 'run', run['id'])
    assert final['status'] == status and final['dsh_seq'] == 103
    assert final['retrieval_snapshots'] == current['retrieval_snapshots']


async def test_progress_is_owner_scoped_and_historical_turn_safe(setup, principal, store):
    service, _, run, _ = setup
    current = await bind(store, principal, run)
    other = principal.model_copy(update={'actor_id':uuid4()})
    body = {'session_id':run['dsh_session_id'], 'event':{'seq':10, 'type':'step/end', 'data':{'turn':1}}}
    await service.project_event(other, body)
    assert 'dsh_seq' not in await store.get(principal, 'conversation', run['conversation_id'])
    async with store.transaction(principal, run['id']) as conn:
        newer = await store.create(principal, 'run', {**{k:v for k,v in current.items() if k not in {'id','revision','created_at','updated_at'}}, 'dsh_turn':2}, conn)
        convo = await store.get(principal, 'conversation', run['conversation_id'], conn)
        convo['active_run'] = newer['id']
        await store.update(principal, 'conversation', convo, conn)
    await service.project_event(principal, body)
    assert (await store.get(principal, 'conversation', run['conversation_id']))['dsh_seq'] == 10
    assert (await store.get(principal, 'run', run['id'])) == current


async def test_stream_reads_only_binding_and_keeps_session_and_turn_guards(setup, principal, store, monkeypatch):
    service, _, run, _ = setup
    current = await bind(store, principal, run)
    body = {'run_id':run['id'], 'session_id':run['dsh_session_id'], 'turn':1,
        'epoch':current['epoch'],
        'frames':[{'type':'chunk','chunk':{'type':'text-delta','text':'final text'}}]}
    original_get = store.get
    async def forbid_full_read(*args, **kwargs):
        raise AssertionError('stream projection fetched a full ledger')
    monkeypatch.setattr(store, 'get', forbid_full_read)
    await service.project_stream(principal, body)
    for changed, code in [({'session_id':'other'},403), ({'turn':2},409), ({'epoch':current['epoch']+1},409)]:
        with pytest.raises(HTTPException) as error:
            await service.project_stream(principal, {**body, **changed})
        assert error.value.status_code == code
    monkeypatch.setattr(store, 'get', original_get)
    assert (await store.get(principal, 'run', run['id'])) == current
    assert (await store.events(principal, run['id']))[-1]['payload'] == {'delta':'final text'}


async def test_ownership_checks_permissions_without_returning_the_run_ledger(setup, principal, store, settings):
    service, host, run, _ = setup
    await bind(store, principal, run)
    headers = {'X-Jsd-Agent-Ticket':issue(settings.shared_secret.get_secret_value(), principal, 'control'),
        'Authorization':'Bearer '+settings.runtime_secret.get_secret_value()}
    async with httpx.AsyncClient(transport=httpx.ASGITransport(create_app(settings,store,service)),base_url='http://harness.test') as client:
        response = await client.post('/internal/v1/harness/dsh/ownership',headers=headers,
            json={'session_id':run['dsh_session_id'], 'profile':'agent'})
        assert response.status_code == 200 and response.json() == {'allowed':True}
        host.denied = True
        denied = await client.post('/internal/v1/harness/dsh/ownership',headers=headers,
            json={'session_id':run['dsh_session_id'], 'profile':'agent'})
        assert denied.status_code == 403
