"""Harness-only fixtures: never read application .env files or business databases."""

import asyncio
import os
import sys
from urllib.parse import urlsplit
from uuid import uuid4

import pytest

from aird_agent.core.store import Store
from aird_agent.server.contracts import HarnessSettings, Principal

if sys.platform == "win32":
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())


@pytest.fixture
def principal():
    return Principal(organization_id=uuid4(), actor_id=uuid4(), auth_version=1, username="test", role="USER")


@pytest.fixture
def settings(tmp_path):
    return HarnessSettings(
        database_url="postgresql://unused@127.0.0.1/jsd_aird_agents_test",
        shared_secret="control-" + "c" * 32,
        runtime_secret="runtime-" + "r" * 32,
        storage_root=tmp_path,
        _env_file=None,
    ).model_copy(update={
        # This shared test database retains earlier synthetic runs as evidence;
        # admission capacity is tested with an explicit limit in its own tests.
        "max_running_runs": 100000,
    })


@pytest.fixture
async def store():
    dsn = os.environ.get("HARNESS_TEST_DATABASE_URL")
    if not dsn:
        pytest.skip("Set HARNESS_TEST_DATABASE_URL to an isolated agents *_test database")
    name = urlsplit(dsn).path.strip("/")
    if not (name.startswith("jsd_aird_agents") and name.endswith("_test")):
        pytest.fail("Refusing Harness tests outside a dedicated jsd_aird_agents*_test database")
    store = Store(dsn)
    await store.pool.open(wait=True, timeout=10)
    try:
        # Additive Harness schema only; each test uses a different owner and IDs.
        await store.migrate()
        yield store
    finally:
        await store.pool.close()
