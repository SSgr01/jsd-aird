"""Bound HTTP admission and retain a compute lease until the actual sync work exits."""
from __future__ import annotations

import json
from contextlib import contextmanager
from threading import BoundedSemaphore, Lock
from typing import Any


class Lease:
    def __init__(self, semaphore: BoundedSemaphore):
        self.semaphore = semaphore
        self.claimed = False
        self.closed = False
        self.lock = Lock()

    def close(self):
        with self.lock:
            if not self.closed:
                self.closed = True
                self.semaphore.release()


@contextmanager
def computation(request):
    lease = request.scope.get("aird.capacity")
    if lease is not None:
        lease.claimed = True
    try:
        yield
    finally:
        if lease is not None:
            lease.close()


class CapacityMiddleware:
    def __init__(self, app, concurrency: int = 1, maximum_json_bytes: int = 32 * 1024 * 1024):
        self.app = app
        self.slots = BoundedSemaphore(max(1, concurrency))
        self.maximum_json_bytes = maximum_json_bytes

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope["method"] != "POST":
            return await self.app(scope, receive, send)
        if not self.slots.acquire(blocking=False):
            return await self._error(scope, send, 503, "SERVER_BUSY", "compute capacity is busy")
        lease = Lease(self.slots)
        scope["aird.capacity"] = lease
        received = 0
        started = False
        exceeded = False
        limit_response_sent = False

        async def bounded_receive():
            nonlocal received, exceeded
            message = await receive()
            received += len(message.get("body", b""))
            if received > self.maximum_json_bytes:
                exceeded = True
                raise PayloadTooLarge()
            return message

        async def tracked_send(message):
            nonlocal started, limit_response_sent
            # FastAPI may convert receive errors into its own 400 response.
            # Preserve the actual 413 contract, including for chunked bodies.
            if exceeded:
                if not limit_response_sent and not started:
                    limit_response_sent = True
                    started = True
                    await self._error(scope, send, 413, "PAYLOAD_TOO_LARGE", "request exceeds size limit")
                return
            if message["type"] == "http.response.start":
                started = True
            await send(message)

        try:
            headers = dict(scope.get("headers", []))
            raw_length = headers.get(b"content-length", b"0")
            try:
                oversized = int(raw_length) > self.maximum_json_bytes
            except ValueError:
                oversized = False
            if oversized:
                return await self._error(scope, send, 413, "PAYLOAD_TOO_LARGE", "request exceeds size limit")
            await self.app(scope, bounded_receive, tracked_send)
        except PayloadTooLarge:
            if not started:
                await self._error(scope, send, 413, "PAYLOAD_TOO_LARGE", "request exceeds size limit")
        finally:
            # A cancelled HTTP task must not release a running native computation.
            if not lease.claimed:
                lease.close()

    @staticmethod
    async def _error(scope, send, status: int, code: str, message: str):
        headers = dict(scope.get("headers", []))
        body = json.dumps({"code": code, "message": message,
                           "requestId": headers.get(b"x-request-id", b"unknown").decode(errors="replace"),
                           "retryable": status == 503, "contractVersion": "formula-model.v2"}).encode()
        response_headers = [(b"content-type", b"application/json")]
        if status == 503:
            response_headers.append((b"retry-after", b"2"))
        await send({"type": "http.response.start", "status": status, "headers": response_headers})
        await send({"type": "http.response.body", "body": body})


class PayloadTooLarge(Exception):
    pass
