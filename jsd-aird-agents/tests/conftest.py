import asyncio
import os
import sys
from urllib.parse import urlsplit
from uuid import uuid4

import httpx
import pytest

from jsd_aird_agents.adapters.fixtures import ACTOR, APPROVER, PROJECT, seed
from jsd_aird_agents.api import create_app
from jsd_aird_agents.contracts import CreateRun, RunContext
from jsd_aird_agents.services import Services
from jsd_aird_agents.settings import Settings
from jsd_aird_agents.worker import Worker

if sys.platform == "win32":
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())

ACTOR_TOKEN = "test-actor-token-123456789"
APPROVER_TOKEN = "test-approver-token-123456789"
OUTSIDER_TOKEN = "test-outsider-token-123456789"
AUTH = {"Authorization": f"Bearer {ACTOR_TOKEN}"}
REVIEWER = {"Authorization": f"Bearer {APPROVER_TOKEN}"}
FOREIGN = {"Authorization": f"Bearer {OUTSIDER_TOKEN}"}


def pytest_addoption(parser):
    parser.addoption(
        "--live-model", action="store_true", help="explicitly make a real provider smoke request"
    )


def pytest_collection_modifyitems(config, items):
    if not config.getoption("--live-model"):
        for item in items:
            if "live" in item.keywords:
                item.add_marker(pytest.mark.skip(reason="real provider smoke test requires --live-model"))


@pytest.fixture
def settings():
    # No production fallback: tests cannot obtain a DSN from the application .env.
    url = os.environ.get("JSD_AIRD_AGENTS_TEST_DATABASE_URL", "")
    if not url:
        pytest.fail(
            "Set JSD_AIRD_AGENTS_TEST_DATABASE_URL to an isolated jsd_aird_agents*_test database, or run -m 'not integration'"
        )
    name = urlsplit(url).path.strip("/")
    if not (name.startswith("jsd_aird_agents") and name.endswith("_test")):
        pytest.fail("Refusing to reset schemas outside a dedicated agents *_test database")
    return Settings(
        database_url=url,
        actor_token=ACTOR_TOKEN,
        approver_token=APPROVER_TOKEN,
        outsider_token=OUTSIDER_TOKEN,
        model_backend="scripted",
        model_name="fixture-model",
        worker_heartbeat_seconds=0.05,
        worker_stale_seconds=2,
        sse_poll_seconds=0.01,
        _env_file=None,
    )


@pytest.fixture
async def services(settings):
    services = Services(settings)
    await services.open()
    try:
        # These are fixed schemas in the explicitly validated disposable test database only.
        async with services.db.pool.connection() as conn:
            await conn.execute(
                "DROP SCHEMA IF EXISTS agent_runtime CASCADE; DROP SCHEMA IF EXISTS agent_checkpoint CASCADE; DROP SCHEMA IF EXISTS mock_domain CASCADE"
            )
        await services.db.migrate()
        await seed(services.db)
        yield services
    finally:
        await services.close()


@pytest.fixture
async def client(services, settings):
    app = create_app(settings, services)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        yield client


async def new_run(services, request="分析项目延期", project_id=PROJECT, conversation_id=None):
    principal = services.identity.lookup(ACTOR)
    return await services.store.create_run(
        principal,
        CreateRun(request=request, project_id=project_id, conversation_id=conversation_id),
        str(uuid4()),
    )


def context(services, run):
    return RunContext(services.identity.lookup(ACTOR), run["conversation_id"], run["id"], PROJECT)


async def awaiting_action(services):
    run = await new_run(services, "分析延期并将耐候测试验证截止日期调整到2026-11-10")
    await Worker(services).tick()
    run = await services.store.run(run["id"])
    assert run["status"] == "WAITING_APPROVAL", run.get("error")
    action = await services.domain.get(
        run["interrupt"]["value"]["action_id"], services.identity.lookup(ACTOR)
    )
    return run, action


async def approve(services, action, decision="APPROVED"):
    return await services.domain.decide(
        action["id"], services.identity.lookup(APPROVER), decision, action["request_hash"]
    )
