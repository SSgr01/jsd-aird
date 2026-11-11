import httpx
import pytest
from fastapi import HTTPException

from aird_agent.hosts.jsd.host import JsdHostGateway


@pytest.mark.parametrize("failure", ["http-timeout", "operation-timeout"])
async def test_retrieval_has_one_bounded_call_and_explains_incomplete_coverage(settings, principal, failure):
    calls = []

    def respond(request):
        calls.append(request)
        assert 0 < request.extensions["timeout"]["read"] <= 35
        if failure == "http-timeout":
            raise httpx.ReadTimeout("deadline", request=request)
        return httpx.Response(504, json={"code": "OPERATION_TIMEOUT"})

    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as http:
        gateway = JsdHostGateway(settings, http)
        with pytest.raises(HTTPException) as error:
            await gateway.call(principal, "knowledge_search", {"query": "resin"})
    assert error.value.status_code == 504
    assert "RETRIEVAL_TIMEOUT" in error.value.detail and "覆盖不完整" in error.value.detail
    assert len(calls) == 1
