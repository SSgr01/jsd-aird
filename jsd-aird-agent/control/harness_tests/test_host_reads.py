import asyncio
import json

import httpx
import pytest

from aird_agent.hosts.jsd.bindings import JsdBindings
from aird_agent.hosts.jsd.host import HostToolError, JsdHostGateway


@pytest.mark.parametrize(("status", "code", "expected", "message"), [
    (401, "AUTH_REQUIRED", "AUTH_REQUIRED", "登录状态"),
    (403, "DATA_SCOPE_DENIED", "ACCESS_DENIED", "无权读取"),
    (404, "SYS_NOT_FOUND", "NOT_FOUND", "不存在"),
    (409, "VERSION_CONFLICT", "VERSION_CONFLICT", "版本已变化"),
    (504, "OPERATION_TIMEOUT", "BUSINESS_TIMEOUT", "超时"),
    (429, "AUTH_RATE_LIMITED", "BUSINESS_RATE_LIMITED", "频率限制"),
    (503, "AI_NOT_CONFIGURED", "BUSINESS_UNAVAILABLE", "服务暂时不可用"),
])
async def test_read_failures_have_distinct_safe_diagnoses_and_do_not_retry(
    settings, principal, status, code, expected, message,
):
    calls = []

    def respond(request):
        calls.append(request)
        return httpx.Response(status, json={"code": code, "message": "private-stack db-credentials"})

    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as http:
        with pytest.raises(HostToolError) as caught:
            await JsdHostGateway(settings, http).call(principal, "business_read", {"ref": {"type": "PROJECT"}})
    error = caught.value
    assert error.status_code == status
    assert error.tool_error == {"error_code": expected, "retryable": False, "attempts": 1}
    assert message in error.detail and "private-stack" not in error.detail
    assert JsdBindings().error_info(error)["error_code"] == expected
    assert len(calls) == 1


@pytest.mark.parametrize("failure", ["busy", "bad-gateway", "connect", "read-reset", "protocol-reset"])
async def test_transient_read_failure_retries_once_with_the_same_request(settings, principal, failure):
    calls = []

    def respond(request):
        calls.append(request)
        if len(calls) == 1:
            if failure == "connect":
                raise httpx.ConnectError("private-host", request=request)
            if failure == "read-reset":
                raise httpx.ReadError("private-host", request=request)
            if failure == "protocol-reset":
                raise httpx.RemoteProtocolError("private-host", request=request)
            return httpx.Response(503 if failure == "busy" else 502,
                                  json={"code": "DATABASE_BUSY"} if failure == "busy" else {})
        return httpx.Response(200, json={"candidates": [], "total": 0})

    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as http:
        result = await JsdHostGateway(settings, http).call(principal, "business_search", {"type": "PROJECT", "query": "sample"})
    assert result == {"candidates": [], "total": 0}
    assert len(calls) == 2
    assert calls[0].content == calls[1].content
    assert calls[0].headers["X-Jsd-Agent-Ticket"] == calls[1].headers["X-Jsd-Agent-Ticket"]
    assert calls[1].extensions["timeout"]["read"] <= calls[0].extensions["timeout"]["read"]


@pytest.mark.parametrize(("action", "attempts"), [("business_read", 2), ("create_experiment_draft", 1)])
async def test_only_reads_retry_and_exhausted_failures_do_not_invite_more_retries(settings, principal, action, attempts):
    calls = []

    def respond(request):
        calls.append(request)
        return httpx.Response(503, json={"code": "SERVER_BUSY", "message": "internal private-host"})

    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as http:
        with pytest.raises(HostToolError) as caught:
            await JsdHostGateway(settings, http).call(principal, action, {})
    assert len(calls) == attempts
    assert caught.value.tool_error["attempts"] == attempts
    assert not caught.value.tool_error["retryable"]
    assert "internal" not in caught.value.detail


async def test_retry_shares_the_original_total_deadline(settings, principal):
    calls = []

    async def respond(request):
        calls.append(request)
        if len(calls) == 1:
            await asyncio.sleep(0.01)
            return httpx.Response(503, json={"code": "SERVER_BUSY"})
        await asyncio.sleep(1)
        return httpx.Response(200, json={"candidates": []})

    async with httpx.AsyncClient(transport=httpx.MockTransport(respond), timeout=0.1) as http:
        with pytest.raises(HostToolError) as caught:
            await JsdHostGateway(settings, http).call(principal, "business_read", {})
    assert caught.value.tool_error["error_code"] == "BUSINESS_TIMEOUT"
    assert len(calls) == 2
    assert calls[1].extensions["timeout"]["read"] < calls[0].extensions["timeout"]["read"]


async def test_missing_project_filter_returns_only_safe_repair_fields(settings, principal):
    calls = []

    def respond(request):
        calls.append(request)
        assert json.loads(request.content)["arguments"] == {"type": "MATERIAL", "query": "UA-1117"}
        return httpx.Response(400, json={
            "code": "SYS_BAD_REQUEST", "message": "private internals",
            "data": {"code": "INVALID_TOOL_ARGUMENTS", "path": ["filters", "project_id"], "rule": "required",
                     "required_filters": ["project_id"], "allowed_filters": ["project_id"],
                     "submitted": "UA-1117", "internal": "private internals"},
        })

    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as http:
        with pytest.raises(HostToolError) as caught:
            await JsdHostGateway(settings, http).call(principal, "business_search", {"type": "MATERIAL", "query": "UA-1117"})
    error = caught.value
    assert error.tool_error["error_code"] == "INVALID_TOOL_ARGUMENTS"
    assert error.tool_error["repair"] == {
        "path": ["filters", "project_id"], "rule": "required",
        "required_filters": ["project_id"], "allowed_filters": ["project_id"],
    }
    assert "filters.project_id" in error.detail and len(calls) == 1
    assert "private" not in str(error.tool_error) + error.detail
    assert "UA-1117" not in str(error.tool_error) + error.detail


async def test_unexpected_server_failure_is_a_service_failure_not_an_empty_search(settings, principal):
    async with httpx.AsyncClient(transport=httpx.MockTransport(
        lambda request: httpx.Response(500, text="private SQL stack trace"),
    )) as http:
        with pytest.raises(HostToolError) as caught:
            await JsdHostGateway(settings, http).call(principal, "business_search", {"type": "PROJECT"})
    assert caught.value.tool_error["error_code"] == "BUSINESS_UNAVAILABLE"
    assert caught.value.tool_error["attempts"] == 1
    assert "业务服务暂时不可用" in caught.value.detail
    assert "SQL" not in caught.value.detail


@pytest.mark.parametrize(("status", "envelope", "expected"), [
    (502, {"code": ["private malformed error"]}, "BUSINESS_UNAVAILABLE"),
    (400, {"code": "SYS_BAD_REQUEST", "data": {"code": "INVALID_TOOL_ARGUMENTS", "rule": ["private malformed rule"]}},
     "INVALID_TOOL_ARGUMENTS"),
])
async def test_malformed_error_fields_still_return_a_safe_diagnosis(settings, principal, status, envelope, expected):
    async with httpx.AsyncClient(transport=httpx.MockTransport(
        lambda request: httpx.Response(status, json=envelope),
    )) as http:
        with pytest.raises(HostToolError) as caught:
            await JsdHostGateway(settings, http).call(principal, "business_search", {"type": "PROJECT"})
    assert caught.value.tool_error["error_code"] == expected
    assert "private" not in caught.value.detail
