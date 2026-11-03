import asyncio
import threading

from aird_agent.capabilities.literature.rcs import RCS


async def test_blocking_optional_provider_initialization_does_not_block_control_loop(monkeypatch):
    started, release = threading.Event(), threading.Event()

    async def slow_provider(self, question, candidates):
        started.set()
        release.wait(2)
        return {"contexts": []}

    monkeypatch.setattr(RCS, "_score", slow_provider)
    task = asyncio.create_task(RCS(None).score("synthetic", []))
    try:
        assert await asyncio.to_thread(started.wait, 3)
        # The control loop can observe the in-flight provider before it is released.
        await asyncio.sleep(0)
        assert not task.done()
    finally:
        release.set()
        assert await task == {"contexts": []}
