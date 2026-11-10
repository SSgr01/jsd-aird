import asyncio
import base64
import json
from types import SimpleNamespace

import pytest
from fastapi import HTTPException

from aird_agent.core.workspace import Workspace
from aird_agent.sandbox.broker import BrokerSettings, ContainerRunner, Execution
from aird_agent.sandbox.jobs import BrokerJobs
from aird_agent.sandbox.streams import ExecutionStreams


def test_log_cursors_are_byte_offsets_and_survive_restart_with_bounded_tail(tmp_path):
    stream = ExecutionStreams(tmp_path, maximum=20)
    first = '配方 0 g；'.encode()
    stream.append('stdout', first)
    value = stream.read('stdout')
    assert base64.b64decode(value['base64']) == first
    assert value['next_offset'] == len(first)
    stream.append('stdout', b'x' * 30)
    restored = ExecutionStreams(tmp_path)
    value = restored.read('stdout', len(first))
    assert value['lossy'] and value['from_offset'] == len(first) + 10
    assert base64.b64decode(value['base64']) == b'x' * 20
    assert value['next_offset'] == len(first) + 30
    assert restored.read('stdout', value['next_offset'])['base64'] == ''
    with pytest.raises(HTTPException, match='OUTPUT_CURSOR_AHEAD'):
        restored.read('stdout', 1000)
    with pytest.raises(HTTPException, match='INVALID_OUTPUT_CURSOR'):
        restored.read('../receipt', 0)


async def test_container_output_is_available_before_exit_and_separate_from_file_quota(tmp_path, principal):
    workspace = Workspace(tmp_path, str(principal.organization_id), str(principal.actor_id))
    workspace.prepare()
    ready = asyncio.Event()
    source = asyncio.StreamReader(limit=16 * 1024 * 1024)
    expected = ('配方 0 g；25 °C\n' * 5000).encode()

    class Input:
        def write(self, _):
            pass

        async def drain(self):
            pass

        def close(self):
            ready.set()

    async def wait():
        return 0

    async def release(_):
        return True

    slot = SimpleNamespace(process=SimpleNamespace(stdin=Input(), stdout=source, wait=wait),
                           stderr=asyncio.sleep(0, result=b''), startup_seconds=0, workspace=workspace)
    runner = ContainerRunner(BrokerSettings(token='x' * 32, workspace_root=tmp_path))
    runner.pool = SimpleNamespace(acquire=lambda: asyncio.sleep(0, result=slot), release=release)
    request = Execution(organization_id=principal.organization_id, run_id=principal.actor_id,
                        execution_id='e' * 32, script='research', language='python')
    task = asyncio.create_task(runner.execute(workspace, request))
    await ready.wait()
    for start in range(0, len(expected), 16000):
        source.feed_data((json.dumps({'event': 'output', 'stream': 'stdout',
            'base64': base64.b64encode(expected[start:start+16000]).decode()}) + '\n').encode())
    source.feed_data((json.dumps({'event': 'output', 'stream': 'stderr',
                                 'base64': base64.b64encode(b'notice').decode()}) + '\n').encode())
    await asyncio.sleep(0.02)
    logs = ExecutionStreams(workspace.root / '.streams')
    assert logs.state('stdout')['total'] == len(expected)
    assert not task.done()
    source.feed_data((json.dumps({'event': 'result', 'result': {'exit_code': 7, 'files': []}}) + '\n').encode())
    source.feed_eof()
    result = await task
    assert result['exit_code'] == 7 and result['artifacts'] == []
    assert result['truncated']
    assert (workspace.root / '.streams/stdout.log').read_bytes() == expected
    assert (workspace.root / '.streams/stderr.log').read_bytes() == b'notice'


async def test_completed_broker_logs_keep_the_same_execution_identity_after_restart(tmp_path, principal):
    class Runner:
        async def execute(self, workspace, request):
            ExecutionStreams(workspace.root / '.streams').append('stdout', b'created once')
            return {'exit_code': 0, 'artifacts': []}

    request = Execution(organization_id=principal.organization_id, run_id=principal.actor_id,
                        execution_id='c' * 32, script='research', language='python')
    jobs = BrokerJobs(tmp_path, Runner())
    await jobs.submit(request, [])
    await asyncio.gather(*jobs.tasks.values())
    restored = BrokerJobs(tmp_path, Runner())
    result = restored.output(request.execution_id)
    assert result['status'] == 'SUCCEEDED'
    assert base64.b64decode(result['streams']['stdout']['base64']) == b'created once'
    assert not restored.tasks
