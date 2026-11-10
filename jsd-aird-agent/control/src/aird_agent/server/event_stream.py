"""Commit notifications wake readers; durable, authorized events remain authoritative."""

import asyncio
import logging
import time
from contextlib import contextmanager

from psycopg import AsyncConnection, Error

logger = logging.getLogger(__name__)


class EventNotifications:
    def __init__(self):
        self.readers = {}
        self.ready = asyncio.Event()

    @contextmanager
    def subscribe(self, run_id):
        changed = asyncio.Event()
        self.readers.setdefault(run_id, set()).add(changed)
        try:
            yield changed
        finally:
            self.readers[run_id].discard(changed)
            if not self.readers[run_id]:
                del self.readers[run_id]

    def publish(self, run_id):
        for changed in self.readers.get(run_id, ()):
            changed.set()

    async def listen(self, dsn):
        # A dedicated connection per server process, shared by all SSE readers.
        # PostgreSQL delivers NOTIFY only after the event transaction commits.
        while True:
            try:
                async with await AsyncConnection.connect(dsn, autocommit=True) as conn:
                    await conn.execute("LISTEN aird_harness_events")
                    self.ready.set()
                    async for notification in conn.notifies():
                        self.publish(notification.payload)
            except (Error, OSError):
                self.ready.clear()
                # Never log a DSN or credentials. Readers keep their polling fallback.
                logger.warning("Event notification connection unavailable; reconnecting")
                await asyncio.sleep(1)
            finally:
                self.ready.clear()


async def stream_events(store, service, notifications, user, run_id, request, cursor):
    import json

    run = None
    authorized_at = heartbeat_at = float("-inf")
    with notifications.subscribe(run_id) as changed:
        while not await request.is_disconnected():
            # Clear before reading so a commit racing with the read cannot be lost.
            changed.clear()
            now = time.monotonic()
            if now - authorized_at >= 0.5:
                run = await store.get(user, "run", run_id)
                await service.authorized_topic(user, run, execution=False)
                authorized_at = now
            batch = await store.events(user, run_id, cursor)
            for event in batch:
                cursor = event["seq"]
                if event.get("payload", {}).get("error"):
                    from aird_agent.server.api import public_failure
                    event = {**event, "payload": {**event["payload"], "error": public_failure(event["payload"]["error"])}}
                yield f"id: {cursor}\nevent: {event['type']}\ndata: {json.dumps(event, ensure_ascii=False)}\n\n"
            if batch:
                # Drain replay/backlog without inserting a pause between pages.
                continue
            if run["status"] in {"SUCCEEDED", "FAILED", "CANCELLED"}:
                return
            if now - heartbeat_at >= 15:
                yield ": keepalive\n\n"
                heartbeat_at = now
            try:
                await asyncio.wait_for(changed.wait(), timeout=0.5)
            except TimeoutError:
                pass
