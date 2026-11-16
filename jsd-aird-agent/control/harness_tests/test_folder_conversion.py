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
