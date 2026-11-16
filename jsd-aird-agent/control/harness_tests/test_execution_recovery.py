import asyncio
import time
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from fastapi import HTTPException
from psycopg.types.json import Jsonb

from aird_agent.core.business_service import BusinessService
from aird_agent.sandbox.broker import Execution
from aird_agent.sandbox.jobs import BrokerJobs
from aird_agent.sandbox.recovery import CleanupPending
from aird_agent.sandbox.broker import BrokerSettings, ContainerRunner
from aird_agent.core.workspace import Workspace


@pytest.mark.parametrize('exit_code', [0, 1])
async def test_cleanup_pending_keeps_exact_result_but_blocks_delivery_until_confirmed(tmp_path, principal, exit_code):
    class Runner:
        calls = 0
        cleaned = False

        async def execute(self, workspace, request):
            self.calls += 1
            file = workspace.write('output/result.txt', 'unchanged bytes')
            raise CleanupPending('original-container', result={
                'exit_code': exit_code, 'output': 'ValueError: wrong cell' if exit_code else 'done', 'artifacts': [file]})

        def cleanup_confirmed(self, name):
            assert name == 'original-container'
            return self.cleaned

    runner = Runner()
    jobs = BrokerJobs(tmp_path, runner)
    request = Execution(organization_id=principal.organization_id, run_id=principal.actor_id,
        execution_id='a'*32, script='original command', language='python')
    await jobs.submit(request, [])
    await jobs.tasks[request.execution_id]
    before = jobs.get(request.execution_id)
    assert before['status'] == 'UNKNOWN' and 'result' not in before
    assert before['cleanup_pending']['result']['exit_code'] == exit_code
    assert 'result' not in jobs.output(request.execution_id)
    assert (await jobs.submit(request, []))['status'] == 'UNKNOWN'
    assert runner.calls == 1
    runner.cleaned = True
    after = jobs.get(request.execution_id)
    assert after['status'] == 'SUCCEEDED' and after['cleanup_confirmed']
    assert after['result']['exit_code'] == exit_code
    assert after['result']['files'][0]['sha256'] == before['cleanup_pending']['result']['files'][0]['sha256']
    assert runner.calls == 1
    restored = BrokerJobs(tmp_path, SimpleNamespace())
    assert restored.get(request.execution_id) == after


async def test_cleanup_failure_retains_original_timeout_and_does_not_guess_after_restart(tmp_path, principal):
    class Runner:
        async def execute(self, *args):
            raise CleanupPending('original-container', failure=HTTPException(504, 'SANDBOX_TIMEOUT'))

    jobs = BrokerJobs(tmp_path, Runner())
    request = Execution(organization_id=principal.organization_id, run_id=principal.actor_id,
        execution_id='b'*32, script='original command', language='python')
    await jobs.submit(request, [])
    await jobs.tasks[request.execution_id]
    record = jobs.get(request.execution_id)
    assert record['status'] == 'UNKNOWN'
    assert record['cleanup_pending']['original_error'] == 'SANDBOX_TIMEOUT'
    assert BrokerJobs(tmp_path, Runner()).get(request.execution_id)['status'] == 'UNKNOWN'
    jobs.runner.cleanup_confirmed = lambda name: True
    assert jobs.get(request.execution_id)['status'] == 'FAILED'
    assert jobs.get(request.execution_id)['error'] == 'SANDBOX_TIMEOUT'


@pytest.mark.parametrize('change', ['changed', 'deleted'])
async def test_pending_cleanup_cannot_publish_a_changed_or_missing_result(tmp_path, principal, change):
    class Runner:
        cleaned = False
        async def execute(self, workspace, request):
            artifact = workspace.write('output/result.txt', 'original bytes')
            if change == 'deleted':
                workspace.resolve(artifact['path'], writable=True).unlink()
            else:
                workspace.write('output/result.txt', 'different bytes')
            raise CleanupPending('original-container', result={'exit_code':0, 'artifacts':[artifact]})
        def cleanup_confirmed(self, name):
            return self.cleaned
    runner = Runner()
    jobs = BrokerJobs(tmp_path, runner)
    request = Execution(organization_id=principal.organization_id, run_id=principal.actor_id,
        execution_id='d'*32, script='original command', language='python')
    await jobs.submit(request, [])
    await jobs.tasks[request.execution_id]
    assert jobs.get(request.execution_id)['status'] == 'UNKNOWN'
    runner.cleaned = True
    confirmed = jobs.get(request.execution_id)
    assert confirmed['status'] == 'FAILED' and 'result' not in confirmed
    assert confirmed['error'] == 'INVALID_SANDBOX_OUTPUT'


async def test_unknown_effect_fences_writes_but_keeps_the_agent_and_read_only_jobs_running(store, principal, settings):
    async with store.transaction(principal, 'recoverable-run') as conn:
        run = await store.create(principal, 'run', {
            'status':'RUNNING', 'epoch':1, 'dsh_session_id':'recoverable-run', 'dsh_turn':1,
            'active_seconds':0, 'started_at':time.time(), 'steps':0, 'model_calls':0, 'tool_calls':0,
        }, conn)
        await conn.execute("INSERT INTO agent_runtime.harness_calls(run_id,call_id,request_hash,tool_name,status,arguments,execution_epoch,result) "
            "VALUES(%s,'original','hash','dsh_execute','UNKNOWN',%s,1,%s)",
            (run['id'], Jsonb({}), Jsonb({'requires_reconciliation':True})))
    async def active(user, rid, epoch, conn):
        return await store.get(user, 'run', rid, conn)
    service = SimpleNamespace(store=store, settings=settings, session_run=AsyncMock(return_value=run),
        active=active, authorized_topic=AsyncMock())
    for kind, name in [('step',None),('model',None),('native-tool','job_output'),('native-tool','job_list')]:
        assert (await BusinessService.gate_dsh(service, principal, {
            'run_id':run['id'], 'kind':kind, 'native_name':name, 'call_id':'read-'+str(name)}))['allowed']
    for kind, name in [('tool','bash'),('tool','present'),('native-tool','write'),('native-tool','edit')]:
        with pytest.raises(HTTPException, match='SIDE_EFFECT_RECONCILIATION_REQUIRED'):
            await BusinessService.gate_dsh(service, principal, {'run_id':run['id'], 'kind':kind, 'native_name':name})
    assert (await store.get(principal, 'run', run['id']))['status'] == 'RUNNING'


async def test_background_allows_native_file_reading_but_keeps_execution_mutation_and_delivery_disabled(store, principal, settings):
    async with store.transaction(principal, 'report-read') as conn:
        run = await store.create(principal, 'run', {
            'status': 'RUNNING', 'epoch': 1, 'dsh_turn': 1, 'automation': {'builtin_id': 'project-morning'},
            'active_seconds': 0, 'started_at': time.time(), 'steps': 0, 'model_calls': 0, 'tool_calls': 0,
        }, conn)
    async def active(user, rid, epoch, conn):
        return await store.get(user, 'run', rid, conn)
    service = SimpleNamespace(store=store, settings=settings, session_run=AsyncMock(return_value=run),
        active=active, authorized_topic=AsyncMock())
    for name in ['read', 'read_image', 'glob', 'grep']:
        result = await BusinessService.gate_dsh(service, principal, {
            'run_id': run['id'], 'kind': 'native-tool', 'native_name': name, 'call_id': 'read-'+name})
        assert result['allowed']
    for name in ['bash', 'write', 'edit', 'apply_patch', 'present', 'job_kill']:
        with pytest.raises(HTTPException, match='BACKGROUND_READ_ONLY'):
            await BusinessService.gate_dsh(service, principal, {'run_id': run['id'], 'kind': 'native-tool', 'native_name': name})


@pytest.mark.parametrize('hang_after_result', [False, True])
async def test_runner_keeps_result_frame_and_original_failure_when_cleanup_also_fails(tmp_path, principal, monkeypatch, hang_after_result):
    from aird_agent.sandbox import broker
    output = asyncio.StreamReader()
    output.feed_data(b'{"event":"result","result":{"exit_code":7,"files":[]}}\n')
    output.feed_eof()
    stderr = asyncio.get_running_loop().create_future()
    stderr.set_result(b'')
    async def wait():
        if hang_after_result:
            await asyncio.Event().wait()
        return 0
    process = SimpleNamespace(stdout=output, wait=wait,
        stdin=SimpleNamespace(write=lambda _:None, drain=AsyncMock(), close=lambda:None))
    workspace = Workspace(tmp_path, str(principal.organization_id), str(principal.actor_id))
    workspace.prepare()
    slot = SimpleNamespace(name='original-container', workspace=workspace, process=process, stderr=stderr, startup_seconds=0)
    runner = ContainerRunner(BrokerSettings(token='x'*32, workspace_root=tmp_path))
    runner.pool = SimpleNamespace(acquire=AsyncMock(return_value=slot), release=AsyncMock(return_value=False))
    monkeypatch.setattr(broker, 'stage_inputs', lambda *args:None)
    request = Execution(organization_id=principal.organization_id, run_id=principal.actor_id,
        execution_id='c'*32, script='original command', language='python', timeout_ms=5000)
    with pytest.raises(CleanupPending) as caught:
        await runner.execute(workspace, request)
    assert caught.value.observed_exit_code == 7
    if hang_after_result:
        assert caught.value.result is None
        assert caught.value.failure.detail == 'SANDBOX_TIMEOUT'
    else:
        assert caught.value.result['exit_code'] == 7
        assert caught.value.failure is None


async def test_native_output_reconciles_the_original_ledger_and_recovers_bytes_without_dispatch(store, principal, settings):
    import base64
    import io
    import zipfile
    from aird_agent.hosts.jsd.native_files import native_output
    async with store.transaction(principal, 'output-recovery') as conn:
        run = await store.create(principal, 'run', {'status':'RUNNING', 'epoch':1,
            'dsh_session_id':'output-recovery', 'dsh_turn':1, 'pack_ids':['files'], 'profile':'agent'}, conn)
        await conn.execute("INSERT INTO agent_runtime.harness_calls(run_id,call_id,request_hash,tool_name,status,arguments,execution_epoch,result) "
            "VALUES(%s,'original','hash','dsh_execute','UNKNOWN',%s,1,%s)",
            (run['id'], Jsonb({'files':[]}), Jsonb({'requires_reconciliation':True})))
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w') as archive:
        archive.writestr('recovered.txt', 'original execution bytes')
    workspace = Workspace(settings.storage_root/'workspaces', str(principal.organization_id), run['id'])
    workspace.prepare()
    artifact = workspace.write_bytes('output/native-files.zip', buffer.getvalue())
    sandbox = SimpleNamespace(
        reconcile=AsyncMock(return_value={'status':'SUCCEEDED','result':{
            'id':'authority', 'exit_code':0, 'receipt':{'status':'SUCCEEDED'}, 'artifacts':[artifact]}}),
        prepare=lambda *args:{'files':[]},
        output=AsyncMock(return_value={'status':'SUCCEEDED','streams':{}}))
    service = SimpleNamespace(settings=settings, store=store, session_run=AsyncMock(return_value=run),
        authorized_topic=AsyncMock(), bindings=SimpleNamespace(sandbox=sandbox, output_receipt=lambda *args:None))
    result = await native_output(service, principal, {'run_id':run['id'], 'call_id':'original'})
    assert base64.b64decode(result['reconciled_result']['files'][0]['base64']) == b'original execution bytes'
    assert result['reconciled_result']['receipt']['status'] == 'SUCCEEDED'
    await native_output(service, principal, {'run_id':run['id'], 'call_id':'original'})
    sandbox.reconcile.assert_awaited_once()
    async with store.pool.connection() as conn:
        operation = await (await conn.execute('SELECT status FROM agent_runtime.harness_calls WHERE run_id=%s', (run['id'],))).fetchone()
        assert operation['status'] == 'SUCCEEDED'


async def test_cleanup_readback_waits_for_original_create_and_survives_receipt_acknowledgement(tmp_path, monkeypatch):
    from aird_agent.sandbox import containers
    pool = containers.ContainerPool(BrokerSettings(token='x'*32, workspace_root=tmp_path), None)
    first = SimpleNamespace(process=SimpleNamespace(returncode=None), cleanup_complete=False)
    second = SimpleNamespace(process=SimpleNamespace(returncode=0), cleanup_complete=False)
    pool.pending = {'jsd-analysis-first': first, 'jsd-analysis-second': second}
    docker = AsyncMock(return_value=(0, b'', b''))
    monkeypatch.setattr(containers, 'docker', docker)
    async def remove(slot):
        assert slot.cleanup_complete
        # A concurrent receipt read may acknowledge another confirmed slot.
        pool.pending.pop('jsd-analysis-second', None)
    monkeypatch.setattr(pool, 'remove_staging', remove)
    await pool.reap_once()
    assert not first.cleanup_complete and second.cleanup_complete
    first.process.returncode = -9
    await pool.reap_once()
    assert first.cleanup_complete
    assert all(call.args[0] == 'ps' for call in docker.await_args_list)
