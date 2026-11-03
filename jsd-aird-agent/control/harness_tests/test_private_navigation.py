from contextlib import asynccontextmanager
from types import SimpleNamespace
from unittest.mock import AsyncMock
from uuid import uuid4

import pytest
from fastapi import HTTPException

from aird_agent.capabilities.literature.private_sources import private_pack


@pytest.mark.parametrize("mode", ["search", "outline"])
async def test_no_progress_is_durable_and_never_claims_fulltext_read(mode, principal):
    sid = str(uuid4())
    run = {
        "id": str(uuid4()),
        "epoch": 1,
        "conversation_id": str(uuid4()),
        "private_source_ids": [sid],
        "scope_hash": "test",
        "evidence": {},
    }

    @asynccontextmanager
    async def transaction(*args):
        yield None

    runtime = SimpleNamespace(
        store=SimpleNamespace(transaction=transaction, update=AsyncMock(), get=AsyncMock(return_value=run)),
        active=AsyncMock(return_value=run),
        remember_sources=AsyncMock(),
        tool_character_budget=20000,
    )
    host = AsyncMock(return_value={"candidates": [], "entries": []})
    runtime.host_call = host
    context = SimpleNamespace(runtime=runtime, run=run, principal=principal, host=host)
    handler = private_pack().handlers["private_document_" + mode]
    args = {"document_id": sid, "offset": 0}
    if mode == "search":
        args["query"] = "missing"
    await handler(context, args)
    with pytest.raises(HTTPException, match="READ_NO_PROGRESS"):
        await handler(context, args)
    assert host.await_count == 1
    assert "read_coverage" not in run
    await handler(context, {**args, "offset": 12})
    assert host.await_count == 2
    with pytest.raises(HTTPException, match="PRIVATE_SOURCE_NOT_IN_CONVERSATION"):
        await handler(context, {**args, "document_id": str(uuid4())})
    if mode == "search":
        for query in ("other", "another"):
            await handler(context, {**args, "offset": 12, "query": query})
        with pytest.raises(HTTPException, match="READ_NO_PROGRESS"):
            await handler(context, {**args, "offset": 12, "query": "still different"})
