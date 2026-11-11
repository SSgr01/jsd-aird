from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from fastapi import HTTPException

from aird_agent.hosts.jsd.native_web import search


async def test_search_failure_is_not_misreported_as_no_matching_sources(principal):
    service = SimpleNamespace(host_call=AsyncMock(return_value={"status": "HTTP_ERROR", "results": []}))
    with pytest.raises(HTTPException, match="WEB_SEARCH_PROVIDER_FAILED"):
        await search(service, principal, {"id": "run"}, {"query": "test", "max_results": 5})
    service.host_call.return_value = {"status": "EMPTY", "results": []}
    assert await search(service, principal, {"id": "run"}, {"query": "test", "max_results": 5}) == {"sources": [], "truncated": False}
