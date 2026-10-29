from concurrent.futures import ThreadPoolExecutor
from io import BytesIO
from threading import Event
from fastapi.testclient import TestClient
import pytest
from jsd_aird_ai.api import create_app
from jsd_aird_ai.settings import Settings
from jsd_aird_ai.service_v2 import FormulaModelV2Service
from jsd_aird_ai.errors import FormulaModelError


def test_artifact_limit_is_checked_before_reading_the_entire_input():
    service = FormulaModelV2Service(Settings(maximum_artifact_bytes=65536))
    source = BytesIO(b'x' * 200000)
    with pytest.raises(FormulaModelError):
        service._bounded_artifact(source)
    assert source.tell() == 131072
    assert service._bounded_artifact(BytesIO(b'x' * 65536)) == b'x' * 65536


def test_busy_computation_does_not_block_health_or_leak_its_slot(monkeypatch):
    started, release = Event(), Event()

    def slow(self, payload):
        started.set()
        assert release.wait(5)
        return {'ok': True}

    monkeypatch.setattr(FormulaModelV2Service, 'train_workbench', slow)
    with TestClient(create_app(Settings())) as client, ThreadPoolExecutor(max_workers=1) as pool:
        first = pool.submit(client.post, '/internal/v2/modeling/workbench/train', json={})
        assert started.wait(3)
        denied = client.post('/internal/v2/modeling/workbench/train', json={})
        assert denied.status_code == 503
        assert denied.headers['retry-after'] == '2'
        assert client.get('/internal/v2/health/live').status_code == 200
        release.set()
        assert first.result().status_code == 200
        assert client.post('/internal/v2/modeling/workbench/train', json={}).status_code == 200


def test_oversized_json_is_rejected_and_validation_releases_slot():
    with TestClient(create_app(Settings(maximum_json_bytes=32))) as client:
        assert client.post('/internal/v2/modeling/workbench/train', content=b'x' * 33).status_code == 413
        assert client.post('/internal/v2/modeling/workbench/train', json={}).status_code == 422
        assert client.post('/internal/v2/modeling/workbench/train', json={}).status_code == 422


def test_chunked_json_cannot_bypass_limit():
    with TestClient(create_app(Settings(maximum_json_bytes=32))) as client:
        response = client.post('/internal/v2/modeling/workbench/train',
                               content=iter([b'{"value":"', b'x' * 64, b'"}']),
                               headers={'content-type': 'application/json'})
        assert response.status_code == 413
        assert response.json()['code'] == 'PAYLOAD_TOO_LARGE'
        assert client.post('/internal/v2/modeling/workbench/train', json={}).status_code == 422
