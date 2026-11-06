from copy import deepcopy
from types import SimpleNamespace
from unittest.mock import AsyncMock
from uuid import uuid4

import httpx
from fastapi import HTTPException

from aird_agent.server.api import create_app
from aird_agent.server.security import issue


async def test_conversation_keeps_authorized_files_registered_before_a_topic_without_merging_versions(settings, principal):
    cid, first, second = (str(uuid4()) for _ in range(3))
    conversation = {
        "id": cid,
        "messages": [
            {"id": "m1", "role": "user", "run_id": first, "text": "Generate files"},
            {"id": "m2", "role": "assistant", "run_id": first, "text": "Saved"},
            {"id": "m3", "role": "user", "run_id": second, "text": "Explain the result"},
        ],
        "evidence": {"private": "source text"},
    }
    original = {"id": "a", "kind": "artifact", "title": "result.csv", "path": "output/result.csv"}
    revised = {**original, "id": "b"}
    snapshots = {
        first: {"topic_id": None, "registered_outputs": [{**original, "base64": "private-bytes"}]},
        second: {"registered_outputs": [original, revised]},
    }
    store = SimpleNamespace(get=AsyncMock(side_effect=lambda *args: deepcopy(conversation)))
    service = SimpleNamespace(authorize_history=AsyncMock(return_value=snapshots))
    app = create_app(settings, store, service)
    ticket = issue(settings.shared_secret.get_secret_value(), principal, "control")
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://harness.test") as client:
        response = await client.get(
            f"/internal/v1/harness/conversations/{cid}", headers={"X-JSD-Agent-Ticket": ticket}
        )
        assert response.status_code == 200
        assert response.json()["registered_outputs"] == [original, revised]
        assert "evidence" not in response.json()
        assert "private-bytes" not in response.text
        service.authorize_history.assert_awaited_once()
        # Cached snapshots must not be projected when current authorization fails.
        service.authorize_history.side_effect = HTTPException(403, "ACCESS_REVOKED")
        denied = await client.get(
            f"/internal/v1/harness/conversations/{cid}", headers={"X-JSD-Agent-Ticket": ticket}
        )
        assert denied.status_code == 403 and "result.csv" not in denied.text
