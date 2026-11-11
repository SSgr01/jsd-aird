from contextlib import asynccontextmanager
from types import SimpleNamespace
from unittest.mock import AsyncMock

from aird_agent.hosts.jsd import operations


async def test_stop_reconciles_only_after_turn_projection_marks_unknown(principal, monkeypatch):
    statuses = iter(["RUNNING", "UNKNOWN", "UNKNOWN"])
    cursor = SimpleNamespace(fetchone=AsyncMock(side_effect=lambda:
        {"status": next(statuses), "tool_name": "dsh_execute"}))
    connection = SimpleNamespace(execute=AsyncMock(return_value=cursor))

    @asynccontextmanager
    async def connect():
        yield connection

    service = SimpleNamespace(store=SimpleNamespace(pool=SimpleNamespace(connection=connect)))
    reconcile = AsyncMock(side_effect=[{"outcome": "STILL_UNKNOWN"}, {"outcome": "CONFIRMED_FAILED"}])
    monkeypatch.setattr(operations, "reconcile", reconcile)
    monkeypatch.setattr(operations.asyncio, "sleep", AsyncMock())
    await operations.reconcile_cancelled_execution(service, principal, "run", "call")
    assert connection.execute.await_count == 3
    assert reconcile.await_count == 2


async def test_completed_operation_is_never_replayed_or_reconciled(principal, monkeypatch):
    cursor = SimpleNamespace(fetchone=AsyncMock(return_value={"status": "SUCCEEDED", "tool_name": "dsh_execute"}))
    connection = SimpleNamespace(execute=AsyncMock(return_value=cursor))

    @asynccontextmanager
    async def connect():
        yield connection

    service = SimpleNamespace(store=SimpleNamespace(pool=SimpleNamespace(connection=connect)))
    reconcile = AsyncMock()
    monkeypatch.setattr(operations, "reconcile", reconcile)
    await operations.reconcile_cancelled_execution(service, principal, "run", "call")
    reconcile.assert_not_awaited()
