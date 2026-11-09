from contextlib import asynccontextmanager
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from aird_agent.capabilities.literature.private_sources import Upload, detach, list_sources, upload


@pytest.mark.parametrize("source_ids", [None, []])
async def test_empty_sources_still_authorize_and_return_an_empty_list(source_ids):
    conversation = {"private_source_ids": source_ids}
    service = SimpleNamespace(
        store=SimpleNamespace(get=AsyncMock(return_value=conversation)),
        authorize_conversation=AsyncMock(),
        java=AsyncMock(),
    )
    assert await list_sources(service, "owner", "conversation") == []
    service.authorize_conversation.assert_awaited_once_with("owner", conversation)
    service.java.assert_not_awaited()


async def test_historical_null_list_can_attach_list_and_detach_a_file():
    conversation = {
        "profile": "agent", "pack_ids": ["core", "private-literature"],
        "pack_versions": {"core": "pinned"}, "private_source_ids": None,
    }

    @asynccontextmanager
    async def transaction(*args):
        yield object()

    service = SimpleNamespace(
        store=SimpleNamespace(get=AsyncMock(return_value=conversation), transaction=transaction, update=AsyncMock()),
        authorize_conversation=AsyncMock(), java=AsyncMock(return_value={"id": "file"}),
    )
    request = Upload(name="study.txt", content_type="text/plain", base64="dGVzdA==")
    await upload(service, "owner", "conversation", request, "upload-key")
    assert conversation["private_source_ids"] == ["file"]
    service.java.return_value = {"id": "file", "status": "READY"}
    assert await list_sources(service, "owner", "conversation") == [{"id": "file", "status": "READY"}]
    await detach(service, "owner", "conversation", "file")
    assert await list_sources(service, "owner", "conversation") == []
    conversation["private_source_ids"] = None
    await detach(service, "owner", "conversation", "already-absent")
    assert conversation["private_source_ids"] == []
