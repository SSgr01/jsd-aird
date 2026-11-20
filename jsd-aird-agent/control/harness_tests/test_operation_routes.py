from types import SimpleNamespace
from unittest.mock import AsyncMock
from uuid import uuid4

import httpx

from aird_agent.server.api import create_app
from aird_agent.server.security import issue


async def test_body_reconciliation_accepts_subcall_slashes_and_keeps_owner_authentication(settings, principal, monkeypatch):
    from aird_agent.server import api
    operation = AsyncMock(return_value={"outcome": "STILL_UNKNOWN", "replayed": False})
    monkeypatch.setattr(api, "reconcile", operation)
    app = create_app(settings, SimpleNamespace(), SimpleNamespace())
    rid = str(uuid4())
    headers = {"X-Jsd-Agent-Ticket": issue(settings.shared_secret.get_secret_value(), principal, "control")}
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://harness.test") as client:
        url = f"/internal/v1/harness/runs/{rid}/operations/reconcile"
        response = await client.post(url, headers=headers, json={"call_id": "present-1/file-0"})
        assert response.status_code == 200 and response.json()["replayed"] is False
        assert operation.await_args.args[1].key == principal.key
        assert operation.await_args.args[2:] == (rid, "present-1/file-0")
        assert (await client.post(url, headers=headers, json={"call_id": ""})).status_code == 422
        assert (await client.post(url, json={"call_id": "present-1/file-0"})).status_code == 401
