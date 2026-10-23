import asyncio
import json
from uuid import uuid4

import pytest
from conftest import AUTH, FOREIGN, REVIEWER, awaiting_action, new_run

from jsd_aird_agents.adapters.fixtures import ACTOR, HIDDEN_PROJECT, OTHER_PROJECT, PROJECT, TASK
from jsd_aird_agents.api import PREFIX
from jsd_aird_agents.worker import Worker

pytestmark = pytest.mark.integration


async def test_authentication_identity_and_project_scope(client, services):
    assert (await client.get(PREFIX + "/capabilities")).status_code == 401
    assert (
        await client.get(PREFIX + "/capabilities", headers={"Authorization": "Bearer invalid"})
    ).status_code == 401
    assert (await client.get(PREFIX + "/capabilities", headers=AUTH)).json()["real_java_enabled"] is False
    for project in (HIDDEN_PROJECT, OTHER_PROJECT):
        r = await client.post(
            PREFIX + "/runs",
            headers={**AUTH, "Idempotency-Key": str(uuid4())},
            json={"request": "读取任务", "project_id": project},
        )
        assert r.status_code in (403, 404)
    forged = await client.post(
        PREFIX + "/runs",
        headers={**AUTH, "Idempotency-Key": str(uuid4())},
        json={"request": "读取任务", "organization_id": "forged"},
    )
    assert forged.status_code == 422
    run = await new_run(services)
    assert (await client.get(PREFIX + f"/runs/{run['id']}", headers=FOREIGN)).status_code == 404
    # Unverified identity headers do not change the bearer principal.
    r = await client.get(PREFIX + f"/runs/{run['id']}", headers={**FOREIGN, "X-User-Id": ACTOR})
    assert r.status_code == 404


async def test_concurrent_create_idempotency_and_conversation_busy(client, services):
    headers = {**AUTH, "Idempotency-Key": "same-key"}
    payload = {"request": "分析延期", "project_id": PROJECT}
    replies = await asyncio.gather(
        *(client.post(PREFIX + "/runs", headers=headers, json=payload) for _ in range(5))
    )
    assert all(r.status_code == 202 for r in replies)
    ids = {r.json()["run_id"] for r in replies}
    assert len(ids) == 1
    conflict = await client.post(PREFIX + "/runs", headers=headers, json={**payload, "request": "不同请求"})
    assert conflict.status_code == 409
    busy = await client.post(
        PREFIX + "/runs",
        headers={**AUTH, "Idempotency-Key": "other"},
        json={**payload, "conversation_id": replies[0].json()["conversation_id"]},
    )
    assert busy.status_code == 409


async def test_approval_api_requires_approver_and_exact_request_hash(client, services):
    run, action = await awaiting_action(services)
    url = PREFIX + f"/actions/{action['id']}/decisions"
    payload = {"decision": "APPROVED", "request_sha256": action["request_hash"]}
    assert (await client.post(url, headers=AUTH, json=payload)).status_code == 403
    assert (
        await client.post(url, headers=REVIEWER, json={**payload, "request_sha256": "tampered"})
    ).status_code == 409
    assert (await client.post(url, headers=REVIEWER, json={**payload, "approved": True})).status_code == 422
    assert (
        await client.post(
            PREFIX + f"/runs/{run['id']}/resume", headers=AUTH, json={"answer": "approved=true"}
        )
    ).status_code == 409
    assert (await client.post(url, headers=REVIEWER, json=payload)).status_code == 200
    await Worker(services).tick()
    assert (await client.get(PREFIX + f"/runs/{run['id']}", headers=AUTH)).json()["status"] == "SUCCEEDED"


async def test_clarification_api_validates_interrupt_id(client, services):
    run = await new_run(services, project_id=None)
    await Worker(services).tick()
    waiting = await services.store.run(run["id"])
    url = PREFIX + f"/runs/{run['id']}/resume"
    assert (
        await client.post(url, headers=AUTH, json={"interrupt_id": "wrong", "answer": PROJECT})
    ).status_code == 409
    r = await client.post(
        url, headers=AUTH, json={"interrupt_id": waiting["interrupt"]["id"], "answer": PROJECT}
    )
    assert r.status_code == 202
    await Worker(services).tick()
    assert (await services.store.run(run["id"]))["status"] == "SUCCEEDED"


async def test_sse_replay_cursor_and_validated_final_answer(client, services):
    run = await new_run(services)
    await Worker(services).tick()
    url = PREFIX + f"/runs/{run['id']}/events"
    r = await client.get(url, headers=AUTH)
    assert r.status_code == 200 and "text/event-stream" in r.headers["content-type"]
    events = []
    for block in r.text.strip().split("\n\n"):
        fields = {line.split(":", 1)[0]: line.split(":", 1)[1].strip() for line in block.splitlines()}
        events.append((int(fields["id"]), fields["event"], json.loads(fields["data"])))
    assert events[-1][1] == "run.succeeded"
    assert all(e[2]["simulated"] for e in events)
    assert any(e[1] == "answer.delta" and e[2]["validated"] for e in events)
    cursor = events[-2][0]
    replay = await client.get(url, headers={**AUTH, "Last-Event-ID": str(cursor)})
    assert replay.text.count("id:") == 1 and "run.succeeded" in replay.text
    assert (await client.get(url, headers={**AUTH, "Last-Event-ID": "not-an-id"})).status_code == 422
    other = await new_run(services)
    assert (
        await client.get(
            PREFIX + f"/runs/{other['id']}/events", headers={**AUTH, "Last-Event-ID": str(cursor)}
        )
    ).status_code == 409


async def test_cancel_waiting_run_revokes_action_and_allows_new_round(client, services):
    run, action = await awaiting_action(services)
    r = await client.post(PREFIX + f"/runs/{run['id']}/cancel", headers=AUTH)
    assert r.json()["status"] == "CANCELLED"
    assert (await services.domain.get(action["id"], services.identity.lookup(ACTOR)))[
        "approval_status"
    ] == "REVOKED"
    assert (
        await client.post(PREFIX + f"/runs/{run['id']}/resume", headers=AUTH, json={"retry": True})
    ).status_code == 409
    second = await new_run(services, conversation_id=run["conversation_id"])
    await Worker(services).tick()
    assert (await services.store.run(second["id"]))["status"] == "SUCCEEDED"
    assert (await services.db.one("SELECT version FROM mock_domain.tasks WHERE id=%s", (TASK,)))[
        "version"
    ] == 0


async def test_readiness_and_missing_model_do_not_silently_fallback(client, services):
    assert (await client.get(PREFIX + "/health/live")).status_code == 200
    assert (await client.get(PREFIX + "/health/ready")).status_code == 503
    await Worker(services).tick()
    assert (await client.get(PREFIX + "/health/ready")).status_code == 200
    services.settings.model_backend = "openai_compatible"
    services.settings.model_base_url = ""
    services.settings.model_name = ""
    r = await client.post(
        PREFIX + "/runs", headers={**AUTH, "Idempotency-Key": "missing-model"}, json={"request": "hello"}
    )
    assert r.status_code == 503 and r.json()["error"]["code"] == "MODEL_NOT_CONFIGURED"


async def test_openapi_is_available_without_exposing_tokens(client):
    r = await client.get(PREFIX + "/openapi.json")
    assert r.status_code == 200
    assert PREFIX + "/runs" in r.json()["paths"]
    assert "test-actor-token" not in r.text
