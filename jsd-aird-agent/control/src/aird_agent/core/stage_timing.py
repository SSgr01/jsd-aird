"""Request-local timing only: no payloads, credentials or extra database writes."""

import json
import logging
from contextlib import contextmanager
from contextvars import ContextVar
from time import perf_counter, time
from uuid import uuid4

CURRENT = ContextVar("harness_stage_timing", default=None)
logger = logging.getLogger("uvicorn.error")


@contextmanager
def measure(name):
    record = CURRENT.get()
    if record is None:
        yield
        return
    started = perf_counter()
    try:
        yield
    finally:
        record["durations_ms"][name] = record["durations_ms"].get(name, 0) + (perf_counter() - started) * 1000
        record["counts"][name] = record["counts"].get(name, 0) + 1


def fields(**values):
    record = CURRENT.get()
    if record is not None:
        record.update({key: value for key, value in values.items() if value is not None})


class StageTimingMiddleware:
    def __init__(self, app, *, enabled=False):
        self.app, self.enabled = app, enabled

    async def __call__(self, scope, receive, send):
        path = scope.get("path", "")
        if not self.enabled or scope["type"] != "http" or not path.startswith("/internal/v1/harness/dsh/"):
            return await self.app(scope, receive, send)
        started = perf_counter()
        record = {"trace_id": uuid4().hex, "action": path.rsplit("/", 1)[-1],
                  "started_at_ms": round(time() * 1000), "durations_ms": {}, "counts": {}, "response_bytes": 0}
        token = CURRENT.set(record)

        async def traced_send(message):
            if message["type"] == "http.response.start":
                record["status"] = message["status"]
                record["headers_ms"] = (perf_counter() - started) * 1000
                timing = ", ".join(f"{name};dur={value:.3f}" for name, value in record["durations_ms"].items())
                message = {**message, "headers": [*message.get("headers", []),
                    (b"server-timing", timing.encode("ascii")), (b"x-jsd-trace-id", record["trace_id"].encode("ascii"))]}
            elif message["type"] == "http.response.body":
                record["response_bytes"] += len(message.get("body", b""))
            await send(message)

        try:
            await self.app(scope, receive, traced_send)
        finally:
            record["elapsed_ms"] = round((perf_counter() - started) * 1000, 3)
            record["durations_ms"] = {name: round(value, 3) for name, value in record["durations_ms"].items()}
            CURRENT.reset(token)
            logger.info("HARNESS_STAGE %s", json.dumps(record, separators=(",", ":")))
