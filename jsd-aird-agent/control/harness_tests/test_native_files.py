import base64
import hashlib
import io
import stat
import zipfile
from types import SimpleNamespace

import pytest
from fastapi import HTTPException

from aird_agent.core.contracts import digest
from aird_agent.core.workspace import Workspace
from aird_agent.hosts.jsd.native_files import accept_native_result, read_export, reconcile_native, storage_path
from aird_agent.hosts.jsd.native_files import native_execute
from aird_agent.sandbox.client import Sandbox
from unittest.mock import AsyncMock
from uuid import uuid4
from aird_agent.capabilities.files.handler import FileTools


@pytest.mark.parametrize('directory', ['', 'default', 'default/乳液 工艺'])
async def test_native_shell_creates_empty_working_directory_before_launch(tmp_path, principal, directory):
    captured = {}

    async def execute(workspace, script, language, execution_id, selected, **kwargs):
        captured.update(script=script, selected=selected)
        return {'error': 'SANDBOX_UNAVAILABLE'}

    service = SimpleNamespace(settings=SimpleNamespace(storage_root=tmp_path),
                              bindings=SimpleNamespace(sandbox=SimpleNamespace(execute=execute)))
    await native_execute(service, principal, {'id': str(uuid4()), 'epoch': 1},
                         {'command': 'python3 -c "import openpyxl"', 'cwd': directory, 'files': [],
                          'env': {'PWD': 'C:/host/workspace'}}, 'empty')
    # Execute the actual setup prefix against a temporary directory; a file-only
    # transfer has nothing from which to reconstruct this nested cwd.
    setup = captured['script'].split('code=subprocess.run', 1)[0]
    setup = setup.replace("'/work/scratch/native'", repr(str(tmp_path / 'native')))
    namespace = {}
    exec(setup, namespace)
    assert namespace['cwd'] == tmp_path / 'native' / directory
    assert namespace['cwd'].is_dir()
    assert namespace['env']['PWD'] == '/workspaces' + ('/' + directory if directory else '')
    assert captured['selected'] == []


async def test_native_transfer_flattens_long_session_paths(tmp_path, principal):
    name = 'tasks/session-conversion-' + str(uuid4()) + '/' + str(uuid4()) + '/' + 'nested/' * 30 + '原件.xlsx'
    content = b'original workbook'
    async def execute(workspace, script, language, execution_id, selected, **kwargs):
        assert len(selected) == 1
        assert len(selected[0]) < 60
        assert workspace.resolve(selected[0]).read_bytes() == content
        setup = script.split('cwd=root/', 1)[0]
        setup = setup.replace("'/work/scratch/native'", repr(str(tmp_path / 'native')))
        setup = setup.replace("'/work/'+source", repr(str(workspace.root)) + "+'/'+source")
        # The restored native path belongs to Linux; verify the mapping without
        # creating that deliberately oversized path on the Windows test host.
        assert repr(name) in setup
        return {'error': 'SANDBOX_UNAVAILABLE'}
    service = SimpleNamespace(settings=SimpleNamespace(storage_root=tmp_path),
        bindings=SimpleNamespace(sandbox=SimpleNamespace(execute=execute)))
    await native_execute(service, principal, {'id': str(uuid4()), 'epoch': 1},
        {'command': 'true', 'files': [{'path': name, 'base64': base64.b64encode(content).decode(),
            'sha256': hashlib.sha256(content).hexdigest()}]}, 'long-input')


async def test_preparation_failure_is_known_before_submission(tmp_path, principal, monkeypatch):
    execute = AsyncMock()
    def fail(*args, **kwargs):
        raise FileNotFoundError('path too long')
    monkeypatch.setattr(Workspace, 'write_bytes', fail)
    service = SimpleNamespace(settings=SimpleNamespace(storage_root=tmp_path),
        bindings=SimpleNamespace(sandbox=SimpleNamespace(execute=execute)))
    with pytest.raises(HTTPException, match='SANDBOX_INPUT_PREPARATION_FAILED') as caught:
        await native_execute(service, principal, {'id': str(uuid4()), 'epoch': 1},
            {'command': 'true', 'files': [{'path': 'a', 'base64': 'YQ==',
                'sha256': hashlib.sha256(b'a').hexdigest()}]}, 'failed-input')
    assert caught.value.status_code == 422
    execute.assert_not_awaited()


async def test_binary_attachment_lineage_uses_authorized_parsed_version_without_claiming_model_reading(principal):
    document, version = str(uuid4()), str(uuid4())
    binary_hash = hashlib.sha256(b'original PDF bytes').hexdigest()
    text = 'Parsed original page; this is not a model reading receipt.'
    source = {'kind': 'RUN_UPLOAD', 'document_id': document, 'version_id': version,
        'source_node_key': 'page-1', 'content_sha256': hashlib.sha256(text.encode()).hexdigest(),
        'anchor': {'page': 1}, 'title': 'paper.pdf'}
    conversation = {'native_attachments': [{'id': document, 'version_id': version, 'sha256': binary_hash}]}
    host = AsyncMock(side_effect=[{'sha256': binary_hash, 'version_id': version, 'status': 'READY'},
        {'candidates': [{'text': text, 'source': source}]}])
    files = FileTools(None, SimpleNamespace(get=AsyncMock(return_value=conversation)), host_call=host,
        sandbox=None, character_budget=None, source_policy=None)
    run = {'id': str(uuid4()), 'conversation_id': str(uuid4()), 'evidence': {}}
    assert await files.attachment_sources(principal, run, {'files': [{'sha256': binary_hash}]}) == [source]
    assert run['evidence'] == {}
    assert host.await_args_list[0].args[1] == 'private_source_get'
    assert host.await_args_list[1].args[2]['conversation_id'] == run['conversation_id']
    host.reset_mock(side_effect=True)
    assert await files.attachment_sources(principal, run, {'files': [{'sha256': 'not the attachment'}]}) == []
    host.assert_not_awaited()
    host.return_value = {'sha256': binary_hash, 'version_id': str(uuid4()), 'status': 'READY'}
    with pytest.raises(HTTPException, match='NATIVE_ATTACHMENT_SOURCE_CHANGED'):
        await files.attachment_sources(principal, run, {'files': [{'sha256': binary_hash}]})


def test_native_shell_can_transport_an_office_script_larger_than_business_text_fields():
    from jsonschema import validate, ValidationError
    from aird_agent.hosts.jsd.native_files import native_spec
    command = 'python - <<\'PY\'\n' + '# office content\n' * 1000 + 'PY'
    assert len(command)>12000
    spec = native_spec('dsh_execute')['parameters']
    validate({'command':command, 'files':[]}, spec)
    validate({'command':command, 'files':[], 'timeout_ms':300000}, spec)
    with pytest.raises(ValidationError):
        validate({'command':command, 'files':[], 'timeout_ms':600001}, spec)
    with pytest.raises(ValidationError):
        validate({'command':'x'*(128*1024+1), 'files':[]}, spec)


def export_zip(entries):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w') as archive:
        for name, content in entries:
            if isinstance(name, str) and '\\' in name:
                info = zipfile.ZipInfo(name)
                info.filename = info.orig_filename = name
                name = info
            archive.writestr(name, content)
    return stream.getvalue()


@pytest.mark.parametrize('name', ['../secret', '/etc/passwd', 'other/../secret', 'x\\y', 'x:ads'])
def test_native_export_rejects_escaped_paths(name):
    with pytest.raises(HTTPException):
        read_export(export_zip([(name, b'denied')]))


def test_native_export_rejects_symlinks_and_duplicates():
    link = zipfile.ZipInfo('link')
    link.create_system = 3
    link.external_attr = (stat.S_IFLNK | 0o777) << 16
    with pytest.raises(HTTPException, match='INVALID_NATIVE_EXPORT'):
        read_export(export_zip([(link, b'/etc/passwd')]))
    with pytest.warns(UserWarning), pytest.raises(HTTPException, match='INVALID_NATIVE_EXPORT'):
        read_export(export_zip([('same', b'one'), ('same', b'two')]))


def test_long_report_export_uses_short_host_blob_but_preserves_native_name(tmp_path, principal):
    workspace = Workspace(tmp_path, str(principal.organization_id), str(principal.actor_id))
    workspace.prepare()
    name = 'tasks/conversion/verifications/' + 'a' * 64 + '-' + 'b' * 64 + '.json'
    export = workspace.write_bytes('output/native-files.zip', export_zip([(name, b'{"status":"validated"}')]))
    result = accept_native_result(workspace, {'artifacts': [export], 'receipt': {'status': 'SUCCEEDED'}})
    assert result['files'][0]['path'] == name
    assert len(result['artifacts'][0]['path']) < 80
    assert result['artifacts'][0]['path'].endswith('.json')
    assert workspace.resolve(result['artifacts'][0]['path']).read_bytes() == b'{"status":"validated"}'


async def test_reconcile_native_restores_hash_bound_outputs_without_running_command(tmp_path, principal):
    run = {'id': str(principal.actor_id)}
    operation = {'call_id': 'native-command', 'execution_epoch': 2, 'arguments': {
        'command': 'must never execute again', 'files': [{'path': 'work/input.txt',
        'base64': base64.b64encode(b'original').decode(), 'sha256': hashlib.sha256(b'original').hexdigest()}]}}
    calls = []

    class Broker:
        prepare = Sandbox.prepare
        inputs = Sandbox.inputs
        _inputs = Sandbox._inputs

        async def reconcile(self, workspace, execution_id):
            calls.append(execution_id)
            workspace.prepare()
            archive = workspace.write_bytes('output/native-files.zip', export_zip([('work/result.docx', b'exported bytes')]))
            return {'status': 'SUCCEEDED', 'result': {'id': execution_id, 'exit_code': 0, 'artifacts': [archive],
                'receipt': {'execution_id': execution_id, 'status': 'SUCCEEDED'}}}

        async def execute(self, *args):
            pytest.fail('Reconciliation must never execute the command')

    service = SimpleNamespace(settings=SimpleNamespace(storage_root=tmp_path), bindings=SimpleNamespace(sandbox=Broker()))
    result = (await reconcile_native(service, principal, run, operation))['result']
    assert calls == [digest([run['id'], 2, 'native-command'])[:32]]
    assert result['files'][0]['path'] == 'work/result.docx'
    assert result['inputs']['files'][0]['sha256'] == hashlib.sha256(b'original').hexdigest()
    assert result['artifacts'][0]['path'] == storage_path('work/result.docx')
    workspace = Workspace(tmp_path / 'workspaces', str(principal.organization_id), run['id'])
    assert workspace.resolve(result['artifacts'][0]['path']).read_bytes() == b'exported bytes'


def test_native_transfer_cannot_accept_a_previous_or_modified_export(tmp_path, principal):
    workspace = Workspace(tmp_path, str(principal.organization_id), str(principal.actor_id))
    workspace.prepare()
    artifact = workspace.write_bytes('output/native-files.zip', export_zip([('a.txt', b'previous')]))
    with pytest.raises(HTTPException, match='NATIVE_EXPORT_RECEIPT_MISSING'):
        accept_native_result(workspace, {'artifacts': [], 'receipt': {'status': 'SUCCEEDED'}})
    workspace.write_bytes(artifact['path'], export_zip([('a.txt', b'modified')]))
    with pytest.raises(HTTPException, match='NATIVE_EXPORT_HASH_MISMATCH'):
        accept_native_result(workspace, {'artifacts': [artifact], 'receipt': {'status': 'SUCCEEDED'}})
