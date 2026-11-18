import base64
import copy
import json
import os
from contextlib import asynccontextmanager
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

import pytest
from fastapi import HTTPException

from aird_agent.hosts.jsd.folder_conversion import FolderConversions, bounded, contained, roots, sha


@pytest.fixture
def configured(tmp_path, monkeypatch, principal):
    config = {name: tmp_path/name for name in ('source', 'templates', 'output')}
    for path in config.values():
        path.mkdir()
    for kind in ('test', 'synthesis'):
        (config['templates']/(kind+'.xlsx')).write_bytes(kind.encode())
    value = {**{k: str(v) for k, v in config.items()}, 'organization_id': str(principal.organization_id),
             'administrator_ids': [str(principal.actor_id)]}
    settings = tmp_path/'roots.json'
    settings.write_text(json.dumps({'history': value}), encoding='utf-8')
    monkeypatch.setenv('JSD_AIRD_CONVERSION_ROOTS_FILE', str(settings))
    return roots(principal)['history']


def test_aliases_are_owner_bound_and_output_cannot_be_scanned(configured, principal, monkeypatch, tmp_path):
    other = principal.model_copy(update={'actor_id': uuid4()})
    assert roots(other) == {}
    config = {**configured, 'output': configured['source']/'results'}
    p = tmp_path/'invalid.json'
    p.write_text(json.dumps({'history': config}, default=str), encoding='utf-8')
    monkeypatch.setenv('JSD_AIRD_CONVERSION_ROOTS_FILE', str(p))
    with pytest.raises(HTTPException, match='FOLDER_ROOTS_OVERLAP'):
        roots(principal)


def test_traversal_and_links_are_rejected(configured, tmp_path):
    with pytest.raises(HTTPException):
        contained(configured['source'], configured['source']/'..'/'secret.xlsx')
    link = configured['source']/'linked'
    try:
        link.symlink_to(tmp_path, target_is_directory=True)
    except OSError:
        pytest.skip('OS does not permit test symlinks')
    with pytest.raises(HTTPException, match='FOLDER_LINK_DENIED'):
        contained(configured['source'], link/'secret.xlsx')


def test_inventory_5000_is_lazy_and_preserves_same_stem_and_duplicate_content(configured):
    for index in range(5000):
        (configured['source']/f'{index//4}.{("xlsx", "xls", "doc", "docx")[index%4]}').write_bytes(b'original')
    (configured['source']/'unsupported.pdf').write_bytes(b'ignored')
    items = FolderConversions.inventory(configured)
    assert len(items) == 5000
    assert len({item['relative_path'] for item in items}) == 5000
    assert all(item['status'] == 'QUEUED' for item in items)
    assert all('session_id' not in item for item in items)
    assert (configured['source']/'0.xls').read_bytes() == b'original'


def test_oversized_file_is_an_individual_blocker(configured):
    with (configured['source']/'big.doc').open('wb') as stream:
        stream.truncate(8*1024*1024+1)
    (configured['source']/'small.xlsx').write_bytes(b'valid-size')
    entries = FolderConversions.inventory(configured)
    assert [e['status'] for e in entries] == ['FAILED', 'QUEUED']
    with pytest.raises(HTTPException):
        bounded(configured['source']/'big.doc')


class MemoryStore:
    def __init__(self, records):
        self.records = records

    @asynccontextmanager
    async def transaction(self, *_):
        yield None

    async def get(self, user, kind, key, conn=None):
        if (kind,key) not in self.records:
            raise HTTPException(404, 'missing')
        return copy.deepcopy(self.records[(kind, key)])

    async def create(self, user, kind, payload, conn, rid=None):
        record = {**payload, 'id':rid or str(uuid4())}
        self.records[(kind,record['id'])] = copy.deepcopy(record)
        return record

    async def update(self, user, kind, record, conn):
        self.records[(kind, record['id'])] = copy.deepcopy(record)
        return record

    async def page(self, user, kind, *, cursor=None, limit=100):
        rows = [copy.deepcopy(value) for (stored_kind, _), value in self.records.items() if stored_kind == kind]
        start = int(cursor or 0)
        return {'items': rows[start:start+limit], 'next_cursor': str(start+limit) if start+limit < len(rows) else None}


@pytest.mark.asyncio
async def test_dispatch_limits_two_and_pause_does_not_admit_more(configured, principal):
    bid = str(uuid4())
    batch = {'id': bid, 'status': 'RUNNING'}
    items = [{'id': str(uuid4()), 'batch_id': bid, 'status': 'QUEUED'} for _ in range(5000)]
    records = {('folder-batch', bid): batch, **{('folder-item', item['id']): item for item in items}}
    store = MemoryStore(records)
    runtime = FolderConversions(SimpleNamespace(store=store))
    async def check(_):
        return store.records[('folder-batch', bid)]
    async def rows(*_):
        return [copy.deepcopy(value) for (kind, _), value in records.items() if kind == 'folder-item']
    async def get_batch(*_):
        return batch, configured
    runtime.check, runtime.rows, runtime.batch = check, rows, get_batch
    first = await runtime.dispatch(principal)
    assert len(first['items']) == 2
    assert (await runtime.dispatch(principal))['items'] == first['items']
    records[('folder-batch', bid)]['status'] = 'PAUSED'
    records[('folder-item', first['items'][0]['id'])]['status'] = 'SUCCEEDED'
    assert len((await runtime.dispatch(principal))['items']) == 1


@pytest.mark.asyncio
async def test_legacy_source_uses_strict_adapter_and_keeps_original_identity(configured, principal):
    bid, iid = str(uuid4()), str(uuid4())
    original, working = b'legacy binary', b'normalized OOXML'
    (configured['source']/'record.doc').write_bytes(original)
    item = {'id': iid, 'relative_path': 'record.doc', 'source_sha256': sha(original), 'status': 'RUNNING'}
    batch = {'id': bid, 'templates': {kind: sha(kind.encode()) for kind in ('test', 'synthesis')}}
    store = MemoryStore({('folder-item', iid): item})
    calls = []
    async def java(user, action, args):
        calls.append((action, args))
        return {'name': 'record.docx', 'base64': base64.b64encode(working).decode(), 'status': 'NORMALIZED'}
    runtime = FolderConversions(SimpleNamespace(store=store, java=java))
    async def get_batch(*_):
        return batch, configured
    async def get_item(*_):
        return copy.deepcopy(item)
    runtime.batch, runtime.item_for_session = get_batch, get_item
    result = await runtime.handle(principal, {'dsh_session_id': 'folder-'+iid}, {'action':'source','batch_id':bid}, 'source')
    assert calls[0][0] == 'normalize_conversion_office'
    assert result['working_sha256'] == sha(working)
    assert result['original_sha256'] == sha(original)
    second = await runtime.handle(principal, {'dsh_session_id':'folder-'+iid}, {'action':'source','batch_id':bid}, 'read-again')
    assert second['working_sha256'] == result['working_sha256']
    assert len(calls) == 1
    assert (configured['source']/'record.doc').read_bytes() == original
    (configured['source']/'record.doc').write_bytes(b'edited')
    with pytest.raises(HTTPException, match='FOLDER_SOURCE_CHANGED'):
        await runtime.handle(principal, {'dsh_session_id':'folder-'+iid}, {'action':'source','batch_id':bid}, 'source2')


@pytest.mark.asyncio
async def test_export_requires_execution_receipts_is_idempotent_and_never_overwrites(configured, principal):
    bid, iid = str(uuid4()), str(uuid4())
    source = b'original'
    (configured['source']/'record.xlsx').write_bytes(source)
    batch = {'id':bid, 'templates':{kind:sha(kind.encode()) for kind in ('test','synthesis')}}
    item = {'id':iid, 'relative_path':'record.xlsx', 'source_sha256':sha(source), 'working_sha256':sha(source),
            'normalization':{'status':'PASSTHROUGH'}, 'status':'RUNNING'}
    store = MemoryStore({('folder-item',iid):item})
    output = (Path(__file__).parents[2]/'enterprise-plugin/skills/jsd-experiment-converter/templates/test.xlsx').read_bytes()
    plan = json.dumps({'source_sha256':sha(source), 'template_sha256':sha(b'test')}).encode()
    report = json.dumps({'status':'validated', 'source_sha256':sha(source), 'template_sha256':sha(b'test'),
                         'output_sha256':sha(output), 'plan_sha256':sha(plan), 'human_review':'pending'}).encode()
    paths = {'output':'result.xlsx', 'plan':'mapping.json', 'report':'verification.json'}
    body = {'session_id':'folder-'+iid, 'paths':paths, 'files':[
        {'path':path, 'base64':base64.b64encode(data).decode(), 'sha256':sha(data)}
        for path,data in zip(paths.values(),[output,plan,report])]}
    receipt = None
    async def lineage(*args):
        return receipt, {}, []
    async def session_run(*args):
        return {'id':str(uuid4())}
    async def authorized(*args):
        pass
    service = SimpleNamespace(store=store, settings=SimpleNamespace(storage_root=configured['output']),
                              session_run=session_run, authorized_topic=authorized,
                              bindings=SimpleNamespace(files=SimpleNamespace(lineage=lineage)))
    runtime = FolderConversions(service)
    async def get_batch(*args):
        return batch, configured
    async def get_item(*args):
        return copy.deepcopy(item)
    runtime.batch, runtime.item_for_session = get_batch, get_item
    with pytest.raises(HTTPException, match='FOLDER_EXECUTION_RECEIPT_REQUIRED'):
        await runtime.export(principal, body)
    assert not (configured['output']/bid).exists()
    receipt = {'call_id':'actual-execution', 'status':'SUCCEEDED'}
    assert (await runtime.export(principal, body))['saved']
    assert (await runtime.export(principal, body))['saved']
    target = runtime.directory(configured,batch,item)
    saved = json.loads((target/'verification.json').read_bytes())
    assert 'human_review' not in saved
    assert saved['provenance']['original_sha256'] == sha(source)
    (target/'result.xlsx').write_bytes(b'externally edited')
    with pytest.raises(HTTPException, match='FOLDER_RESULT_EXISTS_OR_CHANGED'):
        await runtime.export(principal, body)
    assert (target/'result.xlsx').read_bytes() == b'externally edited'


@pytest.mark.asyncio
async def test_start_returns_before_scan_and_scanning_recovers_in_another_runtime(configured, principal):
    store = MemoryStore({})
    service = SimpleNamespace(store=store)
    runtime = FolderConversions(service)
    def forbidden_scan(config):
        raise AssertionError('interactive start must not scan the directory')
    runtime.inventory = forbidden_scan
    result = await runtime.handle(principal, {'id':'parent-run'}, {'action':'start','alias':'history'}, 'call-1')
    assert result['status'] == 'SCANNING'
    assert len(store.records) == 1
    assert (await runtime.handle(principal, {'id':'parent-run'}, {'action':'start','alias':'history'}, 'call-1'))['batch_id'] == result['batch_id']
    # New process has no in-memory scan task, only the durable batch.
    recovered = FolderConversions(service)
    recovered.inventory = lambda config: [{'relative_path':'a.xlsx','source_sha256':sha(b'a'),'status':'QUEUED'}]
    user = principal.model_copy(update={'scope':'folder-conversion','automation_grant_id':result['batch_id']})
    assert (await recovered.dispatch(user))['items'] == []
    import asyncio
    await asyncio.gather(*list(recovered.scans.values()))
    batch = store.records[('folder-batch',result['batch_id'])]
    assert batch['status'] == 'RUNNING'
    assert batch['count'] == 1
    assert sum(kind == 'folder-item' for kind,_ in store.records) == 1


def test_folder_capability_is_additive_and_does_not_change_the_upload_file_pack():
    from aird_agent.capabilities.registry import default_registry
    registry = default_registry()
    assert [tool['name'] for tool in registry.tools(['files'])] == ['artifact_save']
    assert registry.pin(['files'])['files']['version'] == '1'
    legacy = registry.pin(['core','files'])
    registry.verify(['core','files'], legacy)
    assert [tool['name'] for tool in registry.tools(['folder-conversion'])] == ['folder_conversion']
    assert registry.pin(['core','files','folder-conversion'])['files'] == legacy['files']


def test_linux_default_configuration_needs_no_environment_variable(configured, principal, monkeypatch):
    from aird_agent.hosts.jsd import folder_conversion
    installed_config = Path(os.environ['JSD_AIRD_CONVERSION_ROOTS_FILE'])
    assert folder_conversion.DEFAULT_CONFIG_PATH.as_posix() == '/etc/jsd-aird/experiment-conversion-roots.json'
    monkeypatch.delenv('JSD_AIRD_CONVERSION_ROOTS_FILE', raising=False)
    monkeypatch.delenv('JSD_AIRD_HARNESS_CONVERSION_ROOTS_FILE', raising=False)
    monkeypatch.setattr(folder_conversion, 'DEFAULT_CONFIG_PATH', installed_config)
    assert roots(principal)['history'] == configured
    assert FolderConversions(SimpleNamespace(store=None)).roots(principal)['history'] == configured
    assert roots(principal.model_copy(update={'actor_id':uuid4()})) == {}


def test_missing_default_configuration_grants_no_access(principal, tmp_path, monkeypatch):
    from aird_agent.hosts.jsd import folder_conversion
    monkeypatch.delenv('JSD_AIRD_CONVERSION_ROOTS_FILE', raising=False)
    monkeypatch.delenv('JSD_AIRD_HARNESS_CONVERSION_ROOTS_FILE', raising=False)
    monkeypatch.setattr(folder_conversion, 'DEFAULT_CONFIG_PATH', tmp_path/'not-installed.json')
    assert roots(principal) == {}
    with pytest.raises(HTTPException, match='FOLDER_CONFIGURATION_UNAVAILABLE'):
        roots(principal, tmp_path/'explicitly-configured-but-missing.json')


def test_explicit_configuration_still_overrides_linux_default(configured, principal, tmp_path, monkeypatch):
    from aird_agent.hosts.jsd import folder_conversion
    monkeypatch.setattr(folder_conversion, 'DEFAULT_CONFIG_PATH', tmp_path/'not-installed.json')
    assert roots(principal)['history'] == configured


def test_deployment_sample_uses_fixed_absolute_linux_directories():
    from pathlib import PurePosixPath
    sample_path = Path(__file__).parents[3]/'deployment/experiment-conversion-roots.example.json'
    sample = json.loads(sample_path.read_text(encoding='utf-8'))['历史实验资料']
    for key, leaf in [('source','originals'),('templates','templates'),('output','results')]:
        path = PurePosixPath(sample[key])
        assert path.is_absolute()
        assert path == PurePosixPath('/srv/jsd-aird/experiment-history')/leaf


async def test_another_chat_reuses_the_active_directory_and_can_find_progress(configured, principal):
    from aird_agent.hosts.jsd.folder_conversion import digest
    bid = str(uuid4())
    batch = {'id': bid, 'alias': 'history', 'root_hash': digest(configured), 'status': 'RUNNING',
             'auth_version': principal.auth_version, 'count': 0}
    store = MemoryStore({('folder-batch', bid): batch})
    runtime = FolderConversions(SimpleNamespace(store=store))
    async def rows(*args): return []
    runtime.rows = rows
    result = await runtime.handle(principal, {'id': 'another-chat-run'}, {'action': 'start', 'alias': 'history'}, 'new-call')
    assert result['batch_id'] == bid and result['reused_active']
    assert len(store.records) == 1
    found = await runtime.handle(principal, {}, {'action': 'list'}, 'list')
    assert found['batches'][0]['id'] == bid
    status = await runtime.handle(principal, {}, {'action': 'status'}, 'status')
    assert status['batch_id'] == bid
    other = principal.model_copy(update={'actor_id': uuid4()})
    assert (await runtime.handle(other, {}, {'action': 'list'}, 'list'))['batches'] == []


async def test_continue_completed_directory_starts_a_new_incremental_scan(configured, principal):
    from aird_agent.hosts.jsd.folder_conversion import digest
    bid = str(uuid4())
    batch = {'id': bid, 'alias': 'history', 'root_hash': digest(configured), 'status': 'COMPLETED'}
    store = MemoryStore({('folder-batch', bid): batch})
    runtime = FolderConversions(SimpleNamespace(store=store))
    result = await runtime.handle(principal, {'id': 'new-chat-run'}, {'action': 'continue'}, 'continue')
    assert result['batch_id'] != bid and result['status'] == 'SCANNING'
    assert store.records[('folder-batch', bid)]['status'] == 'COMPLETED'


def test_incremental_reuse_requires_same_path_source_and_intact_results(configured):
    bid = str(uuid4())
    previous = {'batch_id': bid, 'relative_path': 'old.xlsx', 'source_sha256': sha(b'old')}
    target = FolderConversions.directory(configured, {'id': bid}, previous)
    target.mkdir(parents=True)
    values = {'result.xlsx': b'xlsx', 'mapping.json': b'mapping', 'verification.json': b'report'}
    for name, value in values.items(): (target/name).write_bytes(value)
    previous['result_hashes'] = {name: sha(value) for name, value in values.items()}
    entries = [{'relative_path': name, 'source_sha256': sha(value), 'status': 'QUEUED'}
               for name, value in [('old.xlsx', b'old'), ('new.xlsx', b'old'), ('old.xlsx', b'changed')]]
    result = FolderConversions.reuse_inventory(configured, copy.deepcopy(entries), [previous])
    assert [row['status'] for row in result] == ['SKIPPED', 'QUEUED', 'QUEUED']
    assert result[0]['reused_batch_id'] == bid
    (target/'result.xlsx').write_bytes(b'edited')
    assert FolderConversions.reuse_inventory(configured, copy.deepcopy(entries), [previous])[0]['status'] == 'QUEUED'
    (target/'result.xlsx').unlink()
    assert FolderConversions.reuse_inventory(configured, copy.deepcopy(entries), [previous])[0]['status'] == 'QUEUED'


async def test_multiple_directories_require_selection_and_background_cannot_list(configured, principal):
    from aird_agent.hosts.jsd.folder_conversion import digest
    store = MemoryStore({})
    runtime = FolderConversions(SimpleNamespace(store=store))
    runtime.roots = lambda user: {'first': configured, 'second': configured}
    for alias in ('first', 'second'):
        bid = str(uuid4())
        store.records[('folder-batch', bid)] = {'id': bid, 'alias': alias, 'root_hash': digest(configured), 'status': 'COMPLETED'}
    result = await runtime.handle(principal, {}, {'action': 'continue'}, 'continue')
    assert result['selection_required']
    assert len(result['batches']) == 2
    with pytest.raises(HTTPException, match='INTERACTIVE_AUTHORIZATION_REQUIRED'):
        await runtime.handle(principal.model_copy(update={'automation_grant_id': 'background'}), {}, {'action': 'list'}, 'list')


async def test_real_store_cross_chat_start_serializes_and_incremental_scan_reuses_outputs(configured, principal, store):
    import asyncio
    runtime = FolderConversions(SimpleNamespace(store=store))
    another = FolderConversions(SimpleNamespace(store=store))
    async def start(engine, run_id, call):
        return await asyncio.wait_for(engine.handle(principal, {'id': run_id}, {'action': 'start', 'alias': 'history'}, call), timeout=10)
    first, simultaneous = await asyncio.gather(start(runtime, 'chat-one', 'start'), start(another, 'chat-two', 'start'))
    assert first['batch_id'] == simultaneous['batch_id']
    bid = first['batch_id']
    (configured['source']/'old.xlsx').write_bytes(b'original')
    (configured['source']/'new.docx').write_bytes(b'new')
    batch = await store.get(principal, 'folder-batch', bid)
    batch.update(status='COMPLETED', engine_hash='same-engine', count=1)
    item = {'batch_id': bid, 'relative_path': 'old.xlsx', 'source_sha256': sha(b'original'), 'status': 'SUCCEEDED'}
    target = runtime.directory(configured, batch, item)
    target.mkdir(parents=True)
    outputs = {'result.xlsx': b'xlsx', 'mapping.json': b'plan', 'verification.json': b'report'}
    for name, value in outputs.items(): (target/name).write_bytes(value)
    item['result_hashes'] = {name: sha(value) for name, value in outputs.items()}
    async with store.transaction(principal, 'fixture') as conn:
        await store.update(principal, 'folder-batch', batch, conn)
        await store.create(principal, 'folder-item', item, conn)
    assert (await another.handle(principal, {}, {'action': 'list'}, 'list'))['batches'][0]['id'] == bid
    # Engine/template changes invalidate old successes, even if the original stayed identical.
    assert len(await runtime.reusable_items(principal, batch)) == 1
    assert await runtime.reusable_items(principal, {**batch, 'engine_hash': 'new-engine'}) == []
    assert await runtime.reusable_items(principal, {**batch, 'templates': {'test': 'changed'}}) == []
    foreign = principal.model_copy(update={'actor_id': uuid4()})
    assert await runtime.reusable_items(foreign, batch) == []
    next_batch = await asyncio.wait_for(another.handle(principal, {'id': 'chat-three'}, {'action': 'continue'}, 'continue'), timeout=10)
    user = principal.model_copy(update={'scope': 'folder-conversion', 'automation_grant_id': next_batch['batch_id']})
    await another.dispatch(user, {'engine_hash': 'same-engine'})
    await asyncio.wait_for(asyncio.gather(*list(another.scans.values())), timeout=10)
    rows = await another.rows(principal, next_batch['batch_id'])
    assert {row['relative_path']: row['status'] for row in rows} == {'old.xlsx': 'SKIPPED', 'new.docx': 'QUEUED'}
    status = await runtime.handle(principal, {}, {'action': 'status'}, 'status')
    assert status['counts']['SKIPPED'] == 1 and status['counts']['QUEUED'] == 1
    old = next(row for row in status['files'] if row['relative_path'] == 'old.xlsx')
    assert old['session_id'] is None and old['result_directory'] == str(target)
    # Only the new file is dispatched. No native/model session is created by this test.
    dispatched = await another.dispatch(user, {'engine_hash': 'same-engine'})
    assert len(dispatched['items']) == 1
    assert (target/'result.xlsx').read_bytes() == outputs['result.xlsx']


async def test_paused_batch_can_resume_or_start_a_new_scan_without_overwriting_history(configured, principal):
    from aird_agent.hosts.jsd.folder_conversion import digest
    bid = str(uuid4())
    batch = {'id': bid, 'alias': 'history', 'root_hash': digest(configured), 'status': 'PAUSED'}
    store = MemoryStore({('folder-batch', bid): batch})
    runtime = FolderConversions(SimpleNamespace(store=store))
    resumed = await runtime.handle(principal, {}, {'action': 'continue', 'batch_id': bid}, 'resume')
    assert resumed['batch_id'] == bid and resumed['status'] == 'RUNNING'
    assert len(store.records) == 1
    await runtime.handle(principal, {}, {'action': 'pause', 'batch_id': bid}, 'pause')
    restarted = await runtime.handle(principal, {'id': 'explicit-new-scan'}, {'action': 'start', 'alias': 'history'}, 'start')
    assert restarted['batch_id'] != bid and restarted['status'] == 'SCANNING'
    assert store.records[('folder-batch', bid)]['status'] == 'PAUSED'
