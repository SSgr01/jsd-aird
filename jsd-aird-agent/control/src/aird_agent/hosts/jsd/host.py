"""JSD host adapter. Another host injects HostGateway, not a Java implementation."""

import asyncio
import logging
import re
import time

import httpx
from fastapi import HTTPException

from aird_agent.core.stage_timing import measure
from aird_agent.server.security import issue

logger = logging.getLogger(__name__)


class HostToolError(HTTPException):
    """A public diagnosis and safe recovery fields for the existing tool ledger."""

    def __init__(self, status, code, message, *, attempts=1, repair=None):
        super().__init__(status, message)
        self.tool_error = {"error_code": code, "retryable": False, "attempts": attempts}
        if repair:
            self.tool_error["repair"] = repair


def repair_fields(detail):
    """Forward validation rules and field names, never submitted business values."""
    if not isinstance(detail, dict) or detail.get("code") != "INVALID_TOOL_ARGUMENTS":
        return None
    identifier = re.compile(r"[A-Za-z_][A-Za-z0-9_]{0,63}")
    output = {}
    for key in ("path", "required_filters", "allowed_filters"):
        value = detail.get(key)
        if isinstance(value, list) and len(value) <= 60 and all(
            isinstance(item, str) and identifier.fullmatch(item)
            or type(item) is int and key == "path" and 0 <= item <= 100000 for item in value
        ):
            output[key] = value
    rule = detail.get("rule")
    if isinstance(rule, str) and rule in {"required", "unsupported_filter", "type", "maxLength", "searchable_terms"}:
        output["rule"] = rule
    if type(detail.get("max_length")) is int and 0 < detail["max_length"] <= 1000:
        output["max_length"] = detail["max_length"]
    return output or None


class JsdHostGateway:
    RETRIEVAL_ACTIONS = {"knowledge_search", "retrieval_context", "private_source_search", "query_structured_data"}
    READ_ACTIONS = RETRIEVAL_ACTIONS | {
        "business_search", "business_read", "business_links", "business_query",
        "document_outline", "document_read", "table_read", "source_read", "source_context", "source_image",
        "private_source_outline", "private_source_read", "private_source_context",
    }

    def __init__(self, settings, http):
        self.settings, self.http = settings, http

    async def call(self, principal, action, arguments, run_id=None):
        ticket = issue(self.settings.shared_secret.get_secret_value(), principal, "java-tools", run_id=run_id)
        read_only = action in self.READ_ACTIONS
        seconds = 35 if action in self.RETRIEVAL_ACTIONS else self.http.timeout.read
        deadline = time.monotonic() + seconds if seconds is not None else None
        attempts = 0
        try:
            async with asyncio.timeout(seconds):
                for attempt in range(2 if read_only else 1):
                    attempts = attempt + 1
                    remaining = max(0.001, deadline - time.monotonic()) if deadline is not None else None
                    try:
                        with measure("host_http_ms"):
                            response = await self.http.post(
                                self.settings.java_url + "/internal/v1/agent-tools",
                                headers={"X-Jsd-Agent-Ticket": ticket},
                                json={"action": action, "arguments": arguments or {}},
                                timeout=httpx.Timeout(remaining, connect=min(3, remaining) if remaining is not None else 3),
                            )
                    except (httpx.NetworkError, httpx.ConnectTimeout, httpx.RemoteProtocolError):
                        if read_only and attempt == 0:
                            continue
                        if not read_only:
                            raise
                        raise HostToolError(503, "BUSINESS_UNAVAILABLE", "业务服务连接失败。", attempts=attempts) from None
                    if response.status_code < 400:
                        return response.json()
                    try:
                        envelope = response.json()
                    except ValueError:
                        envelope = {}
                    if not isinstance(envelope, dict):
                        envelope = {}
                    code = envelope.get("code", "UNAVAILABLE")
                    if not isinstance(code, str):
                        envelope["code"] = code = "UNAVAILABLE"
                    # Do not log arguments, response bodies, credentials or source contents.
                    logger.warning("Business host refused action=%s status=%s attempt=%s code=%s", action, response.status_code, attempts,
                                   code if isinstance(code, str) and code.replace("_", "").isalnum() else "UNAVAILABLE")
                    if read_only and attempt == 0 and response.status_code in {502, 503} and code in {
                        "UNAVAILABLE", "SERVER_BUSY", "DATABASE_BUSY",
                    }:
                        continue
                    self.refuse(response.status_code, envelope, action, attempts)
        except (httpx.TimeoutException, TimeoutError):
            if not read_only:
                raise
            raise self.timeout_error(action, attempts) from None

    @staticmethod
    def timeout_error(action, attempts):
        if action in JsdHostGateway.RETRIEVAL_ACTIONS:
            return HostToolError(504, "RETRIEVAL_TIMEOUT", "RETRIEVAL_TIMEOUT: 本次检索超时，覆盖不完整。", attempts=attempts)
        return HostToolError(504, "BUSINESS_TIMEOUT", "业务读取超时。", attempts=attempts)

    @staticmethod
    def refuse(status, envelope, action, attempts):
        code = envelope.get("code")
        if status == 401:
            raise HostToolError(status, "AUTH_REQUIRED", "登录状态已失效，请重新登录。", attempts=attempts)
        if status == 403:
            raise HostToolError(status, "ACCESS_DENIED", "当前账号无权读取这些记录或执行该业务操作。", attempts=attempts)
        if status == 404:
            raise HostToolError(status, "NOT_FOUND", "所需记录不存在，请重新查找。", attempts=attempts)
        if status == 504 or code in {"OPERATION_TIMEOUT", "SYS_OPERATION_TIMEOUT"}:
            raise JsdHostGateway.timeout_error(action, attempts)
        # These markers have fixed recovery instructions; arbitrary exception text stays private.
        message = envelope.get("message", "")
        for marker, hint in {
            "RETRIEVAL_SNAPSHOT_CHANGED": "RETRIEVAL_SNAPSHOT_CHANGED: restart search at offset 0",
            "RETRIEVAL_REWRITE_BUDGET_REQUIRED": "RETRIEVAL_REWRITE_BUDGET_REQUIRED",
        }.items():
            if isinstance(message, str) and marker in message:
                raise HostToolError(409, marker, hint, attempts=attempts)
        if code in {"VERSION_CONFLICT", "OPTIMISTIC_LOCK_CONFLICT", "POLICY_VERSION_CONFLICT"}:
            raise HostToolError(409, "VERSION_CONFLICT", "记录版本已变化，请重新查找并读取固定版本。", attempts=attempts)
        repair = repair_fields(envelope.get("data"))
        if repair:
            path = ".".join(map(str, repair.get("path", [])))
            if repair.get("rule") == "required":
                hint = "业务参数缺少必要字段：" + path + "；请补齐后再查询。"
            elif repair.get("rule") == "unsupported_filter":
                hint = "业务查询不支持筛选字段：" + path + "；请采用允许的筛选字段。"
            else:
                hint = "业务参数需要调整：" + path + "，请按返回的字段规则修正。"
            raise HostToolError(status, "INVALID_TOOL_ARGUMENTS", hint, repair=repair, attempts=attempts)
        if status == 422 and code == "SYS_VALIDATION_ERROR" and isinstance(message, str):
            raise HostToolError(status, "INVALID_TOOL_ARGUMENTS", "BUSINESS_VALIDATION: " + message[:1200], attempts=attempts)
        if status in {400, 422}:
            raise HostToolError(status, "INVALID_TOOL_ARGUMENTS", "业务参数不符合查询要求，请检查可用类型及必要筛选字段。", attempts=attempts)
        if status == 409:
            raise HostToolError(status, "BUSINESS_CONFLICT", "业务记录发生冲突，请核对当前记录状态。", attempts=attempts)
        if status == 429:
            raise HostToolError(status, "BUSINESS_RATE_LIMITED", "业务读取受到频率限制，请稍后再查。", attempts=attempts)
        raise HostToolError(status, "BUSINESS_UNAVAILABLE", "业务服务暂时不可用。", attempts=attempts)
