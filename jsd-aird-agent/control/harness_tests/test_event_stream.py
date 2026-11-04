import asyncio
from types import SimpleNamespace

import pytest
from fastapi import HTTPException

from aird_agent.server.event_stream import EventNotifications, stream_events


class Source:
    def __init__(self, status="RUNNING"):
        self.status = status
        self.rows = []
        self.checked = 0
        self.denied = False

    async def get(self, *args):
        return {"status": self.status}

    async def events(self, user, rid, cursor):
        return [row for row in self.rows if row["seq"] > cursor][:200]

    async def authorized_topic(self, *args, **kwargs):
        self.checked += 1
        if self.denied:
            raise HTTPException(403, "ACCESS_REVOKED")

    def append(self, seq, kind="message.delta"):
        self.rows.append({"seq": seq, "type": kind, "payload": {"delta": str(seq)}})


async def connected():
    return False


def reader(source, notifications, cursor=0):
    return stream_events(
        source, source, notifications, None, "run", SimpleNamespace(is_disconnected=connected), cursor
    )


async def test_commit_wakeup_delivers_without_the_polling_pause_and_cleans_up():
    source, notifications = Source(), EventNotifications()
    stream = reader(source, notifications)
    assert await anext(stream) == ": keepalive\n\n"
    waiting = asyncio.create_task(anext(stream))
    await asyncio.sleep(0)
    source.append(1)
    notifications.publish("another-run")
    await asyncio.sleep(0)
    assert not waiting.done()
    notifications.publish("run")
    assert "id: 1\n" in await asyncio.wait_for(waiting, 0.2)
    await stream.aclose()
    assert not notifications.readers


async def test_replay_drains_all_pages_before_terminal_close():
    source, notifications = Source("SUCCEEDED"), EventNotifications()
    for seq in range(1, 405):
        source.append(seq)
    stream = reader(source, notifications, cursor=2)
    records = [await asyncio.wait_for(anext(stream), 0.2) for _ in range(402)]
    assert records[0].startswith("id: 3\n")
    assert records[-1].startswith("id: 404\n")
    with pytest.raises(StopAsyncIteration):
        await anext(stream)


async def test_failed_authorization_never_emits_cached_text():
    source, notifications = Source(), EventNotifications()
    source.append(1)
    source.denied = True
    stream = reader(source, notifications)
    with pytest.raises(HTTPException, match="ACCESS_REVOKED"):
        await anext(stream)
    assert not notifications.readers


async def test_multiple_readers_are_not_competing_consumers():
    notifications = EventNotifications()
    with notifications.subscribe("run") as first, notifications.subscribe("run") as second:
        notifications.publish("run")
        assert first.is_set() and second.is_set()
    assert not notifications.readers


async def test_permission_is_rechecked_while_new_text_keeps_arriving(monkeypatch):
    clock = [0.0]
    monkeypatch.setattr("aird_agent.server.event_stream.time", SimpleNamespace(monotonic=lambda: clock[0]))
    source, notifications = Source(), EventNotifications()
    source.append(1)
    stream = reader(source, notifications)
    assert "id: 1" in await anext(stream)
    source.denied = True
    source.append(2)
    clock[0] = 0.5
    with pytest.raises(HTTPException, match="ACCESS_REVOKED"):
        await anext(stream)


async def test_missing_notification_still_falls_back_to_the_durable_log():
    source, notifications = Source(), EventNotifications()
    stream = reader(source, notifications)
    assert await anext(stream) == ": keepalive\n\n"
    source.append(1)
    assert "id: 1" in await asyncio.wait_for(anext(stream), 1)
    await stream.aclose()
