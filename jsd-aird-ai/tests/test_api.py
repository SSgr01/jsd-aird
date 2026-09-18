from __future__ import annotations

from pathlib import Path

import yaml
from fastapi.testclient import TestClient

from conftest import validate_contract_definition
from jsd_aird_ai.api import create_app
from jsd_aird_ai.settings import Settings


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


def test_health_reports_pinned_baybe() -> None:
    client = TestClient(create_app(Settings()))
    response = client.get("/internal/v2/health/ready")
    assert response.status_code == 200
    body = response.json()
    assert body["contractVersion"] == "formula-model.v2"
    assert body["dependencies"]["baybe"] == "0.15.0"


def test_internal_token_is_enforced(validation_request) -> None:
    app = create_app(Settings(allow_file_urls=True, internal_token="secret"))
    client = TestClient(app)
    payload = {}
    denied = client.post("/internal/v2/snapshots/validate", json=payload)
    assert denied.status_code == 422
    assert denied.json()["code"] == "UNSUPPORTED_CONTRACT"
    assert denied.json()["contractVersion"] == "formula-model.v2"
    allowed = client.post("/internal/v2/snapshots/validate", json=payload,
                          headers={"Authorization": "Bearer secret"})
    assert allowed.status_code == 422
    assert allowed.json()["code"] == "UNSUPPORTED_CONTRACT"
    assert allowed.json()["contractVersion"] == "formula-model.v2"


def test_v1_routes_are_removed() -> None:
    client = TestClient(create_app(Settings()))
    assert client.get("/internal/v1/health/ready").status_code == 404
    assert client.post("/internal/v1/models/score", json={}).status_code == 404


def test_container_contract_is_non_root_read_only_and_database_free() -> None:
    dockerfile = (REPOSITORY_ROOT / "jsd-aird-ai" / "Dockerfile").read_text(
        encoding="utf-8"
    )
    compose = yaml.safe_load(
        (REPOSITORY_ROOT / "compose.yaml").read_text(encoding="utf-8")
    )
    service = compose["services"]["jsd-aird-ai"]

    assert "USER 10001:10001" in dockerfile
    assert service["read_only"] is True
    assert service["cap_drop"] == ["ALL"]
    assert service["tmpfs"]
    environment_text = "\n".join(
        f"{key}={value}" for key, value in service["environment"].items()
    ).upper()
    assert "POSTGRES" not in environment_text
    assert "DATASOURCE" not in environment_text
