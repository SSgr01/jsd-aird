import asyncio

import pytest
from conftest import context, new_run

from jsd_aird_agents.adapters.fixtures import ACTOR

pytestmark = pytest.mark.integration


async def test_sse_sequence_cannot_overtake_an_uncommitted_event(services):
    run = await new_run(services)
    cursor = (await services.store.events(run["id"]))[-1]["seq"]
    async with services.db.pool.connection() as conn, conn.transaction():
        await services.store.event(run["id"], "test.first", {}, conn=conn)
        second = asyncio.create_task(services.store.event(run["id"], "test.second", {}))
        done, _ = await asyncio.wait({second}, timeout=0.05)
        assert not done
        assert await services.store.events(run["id"], cursor) == []
    await asyncio.wait_for(second, 5)
    events = await services.store.events(run["id"], cursor)
    assert [e["kind"] for e in events] == ["test.first", "test.second"]
    assert events[0]["seq"] < events[1]["seq"]


async def test_cancel_wins_before_final_publication_transaction(services):
    run = await new_run(services)
    claimed = await services.store.claim("test-finisher")
    from dataclasses import replace

    ctx = replace(context(services, run), worker_id="test-finisher", lease_epoch=claimed["lease_epoch"])
    await services.store.cancel(run["id"], services.identity.lookup(ACTOR))
    await services.store.finish(ctx, "SUCCEEDED", result={"answer": "validated but now cancelled"})
    final = await services.store.run(run["id"])
    assert final["status"] == "CANCELLED" and final["result"] is None
    assert "answer.delta" not in [e["kind"] for e in await services.store.events(run["id"])]
