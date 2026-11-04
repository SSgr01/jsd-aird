"""Queue order and handoff regressions; existing isolated schema only, no migrations."""

import os
import time
from copy import deepcopy
from urllib.parse import urlsplit
from uuid import uuid4

import pytest
from fastapi import HTTPException

from aird_agent.core import CapabilityPack, CapabilityRegistry
from aird_agent.core.bindings import RuntimeBindings
from aird_agent.core.config import RuntimeConfig
from aird_agent.core.contracts import ExecutionRequest
from aird_agent.core.coordinator import Coordinator
from aird_agent.core.messages import record_replies, requeue_unconsumed
from aird_agent.core.store import Store
from aird_agent.server.contracts import MessageRequest


def test_unconsumed_steering_precedes_followups_without_replaying_checkpointed_messages():
    messages = [
        {"id": str(index), "seq": index, "role": "user", "text": str(index), "status": "DELIVERED"}
        for index in range(1, 4)
    ]
    run = {"messages": messages, "checkpoint_message_seq": 1}
    conversation = {"messages": deepcopy(messages), "queued": [{"id": "later"}, dict(messages[2])]}
    requeue_unconsumed(run, conversation)
    requeue_unconsumed(run, conversation)
    assert [message["id"] for message in conversation["queued"]] == ["2", "3", "later"]
    assert conversation["messages"][0]["status"] == "DELIVERED"
    assert all(message["status"] == "QUEUED" for message in conversation["messages"][1:])
    assert all(message["delivery"] == "follow_up" for message in conversation["queued"][:2])


def test_history_keeps_replies_between_the_supplements_that_caused_them():
    original = {"id": "request", "role": "user", "text": "question", "run_id": "run"}
    steering = [
        {"id": f"steer-{seq}", "role": "user", "text": f"supplement-{seq}", "seq": seq, "run_id": "run"}
        for seq in [1, 2]
    ]
    run = {
        "id": "run",
        "messages": steering,
        "assistant_messages": [
            {"id": f"reply-{seq}", "text": f"answer-{seq}", "after_message_seq": seq} for seq in [0, 1, 2]
        ],
        "result": "answer-2",
    }
    conversation = {"messages": [original, *steering, {"id": "later", "text": "queued"}], "queued": []}
    record_replies(run, conversation)
    record_replies(run, conversation)
    assert [message["text"] for message in conversation["messages"]] == [
        "question",
        "answer-0",
        "supplement-1",
        "answer-1",
        "supplement-2",
        "answer-2",
        "queued",
    ]


@pytest.fixture
async def queue_store():
    dsn = os.environ.get("HARNESS_QUEUE_TEST_DATABASE_URL")
    if not dsn:
        pytest.skip("Set HARNESS_QUEUE_TEST_DATABASE_URL to an existing isolated agents *_test database")
    name = urlsplit(dsn).path.strip("/")
    if not (name.startswith("jsd_aird_agents") and name.endswith("_test")):
        pytest.fail("Queue tests require a dedicated jsd_aird_agents*_test database")
    store = Store(dsn)
    await store.pool.open(wait=True, timeout=10)
    try:
        async with store.pool.connection() as conn:
            for table in [
                "harness_records",
                "harness_events",
                "harness_calls",
                "harness_checkpoints",
                "harness_idempotency",
            ]:
                row = await (
                    await conn.execute("SELECT to_regclass(%s) AS name", ("agent_runtime." + table,))
                ).fetchone()
                assert row["name"], "Prepare the isolated test schema manually before running queue tests"
        yield store
    finally:
        await store.pool.close()


@pytest.fixture
async def queue_runtime(queue_store, tmp_path):
    class Host:
        async def call(self, user, action, arguments, run_id=None):
            assert action == "authorize_context"
            return {"scope": {"tenant": str(user.organization_id)}, "pack_ids": ["core"]}

    registry = CapabilityRegistry()
    registry.register(CapabilityPack("core", "1", "Synthetic queue regression.", ()))
    runtime = Coordinator(
        RuntimeConfig(runtime_secret="r" * 32, storage_root=tmp_path),
        queue_store,
        registry=registry,
        host=Host(),
        bindings=RuntimeBindings(),
    )
    try:
        yield runtime
    finally:
        await runtime.http.aclose()


async def running(runtime, principal, rid, **values):
    async with runtime.store.transaction(principal, rid) as conn:
        run = await runtime.store.get(principal, "run", rid, conn)
        run.update(status="RUNNING", epoch=1, started_at=time.time(), lease_until=time.time() + 180, **values)
        return await runtime.store.update(principal, "run", run, conn)


async def send(runtime, principal, cid, text, delivery="follow_up"):
    conversation = await runtime.store.get(principal, "conversation", cid)
    request = MessageRequest(text=text, delivery=delivery, expected_revision=conversation["revision"])
    key = str(uuid4())
    result = await runtime.accept_message(principal, cid, request, key)
    assert await runtime.accept_message(principal, cid, request, key) == result
    return result["message_id"]


async def reply(runtime, principal, rid, text, seq):
    await runtime.callback(
        principal,
        rid,
        "event",
        {
            "epoch": 1,
            "type": "message.completed",
            "payload": {"id": str(uuid4()), "text": text, "after_message_seq": seq},
        },
    )


@pytest.mark.integration
async def test_normal_completion_dispatches_each_followup_once_with_fresh_run_state(queue_runtime, principal):
    runtime = queue_runtime
    store = runtime.store
    first = await runtime.create_run(principal, ExecutionRequest(request="original"), str(uuid4()))
    rid, cid = first["id"], first["conversation_id"]
    await running(runtime, principal, rid)
    steer = await send(runtime, principal, cid, "supplement", "steer")
    await send(runtime, principal, cid, "followup-1")
    await send(runtime, principal, cid, "followup-2")
    await reply(runtime, principal, rid, "original-answer", 0)
    await runtime.callback(
        principal, rid, "event", {"epoch": 1, "type": "message.applied", "payload": {"message_id": steer}}
    )
    await reply(runtime, principal, rid, "supplement-answer", 1)
    await runtime.callback(
        principal, rid, "checkpoint", {"epoch": 1, "content": '{"type":"session"}\n', "message_seq": 1}
    )
    previous = await running(
        runtime,
        principal,
        rid,
        evidence={"authorized": {"text": "source"}},
        registered_outputs=[{"id": "old-output"}],
        required_artifacts=["previous-result.csv"],
        completion={"outcome": "COMPLETE"},
        plan={"steps": ["old"]},
        model_calls=7,
        tool_calls=3,
    )
    await runtime.callback(principal, rid, "settled", {"epoch": 1, "text": "supplement-answer"})
    conversation = await store.get(principal, "conversation", cid)
    second = await store.get(principal, "run", conversation["active_run"])
    assert second["request"] == "followup-1"
    assert second["messages"] == second["assistant_messages"] == second["registered_outputs"] == []
    assert second["required_artifacts"] == []
    assert second["checkpoint_message_seq"] == second["model_calls"] == second["tool_calls"] == 0
    assert second["plan"] is second["completion"] is None
    assert second["last_text"] == ""
    assert second["checkpoint"] == previous["checkpoint"]
    assert second["evidence"] == previous["evidence"]
    assert [
        message["text"]
        for message in conversation["messages"]
        if message["id"] not in {item["id"] for item in conversation["queued"]}
    ] == ["original", "original-answer", "supplement", "supplement-answer", "followup-1"]
    with pytest.raises(HTTPException, match="RUN_INACTIVE_OR_LEASE_LOST"):
        await runtime.callback(principal, rid, "settled", {"epoch": 1, "text": "duplicate"})
    await running(runtime, principal, second["id"])
    await runtime.callback(principal, second["id"], "settled", {"epoch": 1, "text": "answer-1"})
    conversation = await store.get(principal, "conversation", cid)
    third = await store.get(principal, "run", conversation["active_run"])
    assert third["request"] == "followup-2"
    assert conversation["queued"] == []
    await running(runtime, principal, third["id"])
    await runtime.callback(principal, third["id"], "settled", {"epoch": 1, "text": "answer-2"})
    conversation = await store.get(principal, "conversation", cid)
    assert conversation["active_run"] is None
    assert len(await store.list(principal, "run")) == 3
    assert [message["text"] for message in conversation["messages"]][-4:] == [
        "followup-1",
        "answer-1",
        "followup-2",
        "answer-2",
    ]


@pytest.mark.integration
@pytest.mark.parametrize("action", ["settled", "failed"])
async def test_late_steering_stays_ahead_of_later_followups_and_failure_pauses(
    queue_runtime, principal, action
):
    runtime = queue_runtime
    first = await runtime.create_run(principal, ExecutionRequest(request="original"), str(uuid4()))
    rid, cid = first["id"], first["conversation_id"]
    await running(runtime, principal, rid)
    late = [await send(runtime, principal, cid, text, "steer") for text in ["late-1", "late-2"]]
    later = await send(runtime, principal, cid, "later")
    await runtime.callback(
        principal, rid, action, {"epoch": 1, "text": "original-answer", "code": "RUNTIME_EXECUTION_FAILED"}
    )
    conversation = await runtime.store.get(principal, "conversation", cid)
    if action == "settled":
        next_run = await runtime.store.get(principal, "run", conversation["active_run"])
        assert next_run["request"] == "late-1"
        assert [message["id"] for message in conversation["queued"]] == [late[1], later]
    else:
        assert conversation["active_run"] is None
        assert conversation["queue_paused"] is True
        assert [message["id"] for message in conversation["queued"]] == [*late, later]
        assert len(await runtime.store.list(principal, "run")) == 1
