from types import SimpleNamespace
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread

import httpx
import pytest
from fastapi import HTTPException
from pydantic import SecretStr

from aird_agent.core.workspace import Workspace
from aird_agent.sandbox.client import Sandbox


@pytest.mark.parametrize('lost', ['submission', 'receipt', 'receipt-503', 'unknown'])
async def test_lost_responses_query_the_same_receipt_without_resubmitting(tmp_path, principal, monkeypatch, lost):
    execution_id = 'f' * 32
    calls = []
    receipt = {'execution_id': execution_id, 'request_hash': 'request', 'status': 'SUCCEEDED',
        'finished_at': 1, 'result': {'exit_code': 0, 'output': 'created once', 'files': []}}

    def handle(request):
        calls.append(request.method)
        if request.method == 'POST':
            if lost in {'submission', 'unknown'}:
                raise httpx.ReadError('response lost after acceptance', request=request)
            return httpx.Response(202, json={**receipt, 'status': 'RUNNING'})
        if lost == 'unknown':
            return httpx.Response(404)
        if calls.count('GET') == 1 and lost == 'receipt':
            raise httpx.ReadError('transient lost receipt response', request=request)
        if calls.count('GET') == 1 and lost == 'receipt-503':
            return httpx.Response(503)
        return httpx.Response(200, json=receipt)

    original = httpx.AsyncClient
    monkeypatch.setattr(httpx, 'AsyncClient', lambda **kwargs: original(transport=httpx.MockTransport(handle), **kwargs))
    settings = SimpleNamespace(sandbox_enabled=True, sandbox_timeout_seconds=1,
        sandbox_url='https://sandbox.test', sandbox_secret=SecretStr('x' * 32))
    workspace = Workspace(tmp_path, str(principal.organization_id), str(principal.actor_id))
    workspace.prepare()
    if lost == 'unknown':
        with pytest.raises(HTTPException, match='SANDBOX_RESULT_UNKNOWN'):
            await Sandbox(settings).execute(workspace, 'create once', 'shell', execution_id, [])
    else:
        result = await Sandbox(settings).execute(workspace, 'create once', 'shell', execution_id, [])
        assert result['output'] == 'created once'
        assert result['receipt']['execution_id'] == execution_id
    assert calls.count('POST') == 1
    assert calls.count('GET') == (2 if lost in {'receipt', 'receipt-503'} else 1)


async def test_internal_submission_receipt_and_cancel_ignore_desktop_proxy(tmp_path, principal, monkeypatch):
    """A desktop proxy must never receive broker commands or its bearer token."""
    execution_id = 'c' * 32
    receipt = {'execution_id': execution_id, 'request_hash': 'request', 'status': 'SUCCEEDED',
        'finished_at': 1, 'result': {'exit_code': 0, 'output': 'direct broker', 'files': []}}
    received = []

    class Broker(BaseHTTPRequestHandler):
        def respond(self):
            self.rfile.read(int(self.headers.get('Content-Length', '0')))
            received.append((self.command, self.path, self.headers.get('Authorization')))
            body = json.dumps(receipt).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        do_POST = do_GET = do_DELETE = respond
        def log_message(self, *args):
            pass

    for name in ('HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'http_proxy', 'https_proxy', 'all_proxy'):
        monkeypatch.setenv(name, 'http://127.0.0.1:1')
    for name in ('NO_PROXY', 'no_proxy'):
        monkeypatch.setenv(name, '')
    with ThreadingHTTPServer(('127.0.0.1', 0), Broker) as broker:
        thread = Thread(target=broker.serve_forever, kwargs={'poll_interval': 0.05}, daemon=True)
        thread.start()
        settings = SimpleNamespace(sandbox_enabled=True, sandbox_timeout_seconds=1,
            sandbox_url=f'http://127.0.0.1:{broker.server_port}', sandbox_secret=SecretStr('test-broker'))
        workspace = Workspace(tmp_path, str(principal.organization_id), str(principal.actor_id))
        workspace.prepare()
        try:
            sandbox = Sandbox(settings)
            assert (await sandbox.execute(workspace, 'echo test', 'shell', execution_id, []))['output'] == 'direct broker'
            assert (await sandbox.reconcile(workspace, execution_id))['status'] == 'SUCCEEDED'
            await sandbox.cancel(execution_id)
        finally:
            broker.shutdown()
            thread.join(timeout=1)
    assert received == [('POST', '/executions', 'Bearer test-broker'),
        ('GET', '/executions/' + execution_id, 'Bearer test-broker'),
        ('DELETE', '/executions/' + execution_id, 'Bearer test-broker')]
