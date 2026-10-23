import json
import os
from pathlib import Path

import httpx
import pytest

from jsd_aird_agents.contracts import AnswerDocument, IntentPlan
from jsd_aird_agents.errors import AgentError
from jsd_aird_agents.models.adapters import OpenAICompatibleModel, retry_after_seconds
from jsd_aird_agents.settings import Settings


def model_settings(**overrides):
    return Settings(
        database_url="postgresql://test@127.0.0.1/jsd_aird_agents_test",
        actor_token="actor-token-test-123456",
        approver_token="approver-token-test-123456",
        model_base_url="https://provider.invalid/v1",
        model_api_key="fixture-secret-key",
        model_name="fixture-model",
        _env_file=None,
        **overrides,
    )


def completion(arguments):
    return {
        "id": "chatcmpl-fixture",
        "object": "chat.completion",
        "created": 1,
        "model": "fixture-model",
        "choices": [
            {
                "index": 0,
                "finish_reason": "tool_calls",
                "message": {
                    "role": "assistant",
                    "content": None,
                    "tool_calls": [
                        {
                            "id": "call-fixture",
                            "type": "function",
                            "function": {"name": "IntentPlan", "arguments": json.dumps(arguments)},
                        }
                    ],
                },
            }
        ],
        "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15},
    }


async def test_real_sdk_http_request_response_schema_and_usage_contract():
    seen = []

    async def respond(request):
        body = json.loads(request.content)
        seen.append(body)
        assert str(request.url) == "https://provider.invalid/v1/chat/completions"
        assert request.headers["Authorization"] == "Bearer fixture-secret-key"
        assert body["model"] == "fixture-model"
        assert "enable_thinking" not in body
        assert body["tools"][0]["function"]["name"] == "IntentPlan"
        return httpx.Response(
            200,
            json=completion(
                {"needs_clarification": True, "clarification_question": "请明确项目", "steps": []}
            ),
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        adapter = OpenAICompatibleModel(model_settings(), client)
        result, usage = await adapter.generate("plan", {"request": "项目"}, IntentPlan)
    assert result.needs_clarification and usage["total_tokens"] == 15 and usage["reported"]
    assert len(seen) == 1


async def test_provider_thinking_extension_is_only_sent_when_explicitly_configured():
    async def respond(request):
        assert json.loads(request.content)["enable_thinking"] is False
        return httpx.Response(200, json=completion({"steps": []}))

    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        result, _ = await OpenAICompatibleModel(model_settings(model_enable_thinking=False), client).generate(
            "plan", {}, IntentPlan
        )
    assert result.steps == []


@pytest.mark.parametrize("method,format_type", [("json_mode", "json_object"), ("json_schema", "json_schema")])
async def test_configured_json_protocol_request_and_response(method, format_type):
    async def respond(request):
        payload = json.loads(request.content)
        assert payload["response_format"]["type"] == format_type
        if method == "json_mode":
            assert "Return JSON matching this schema" in payload["messages"][0]["content"]
        body = completion({})
        body["choices"][0].update(
            finish_reason="stop", message={"role": "assistant", "content": json.dumps({"steps": []})}
        )
        return httpx.Response(200, json=body)

    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        result, _ = await OpenAICompatibleModel(
            model_settings(model_structured_method=method), client
        ).generate("plan", {}, IntentPlan)
    assert result.steps == []


@pytest.mark.parametrize(
    "status,code,retryable",
    [
        (429, "rate_limit_exceeded", True),
        (429, "insufficient_quota", False),
        (401, "invalid_api_key", False),
        (503, "unavailable", True),
    ],
)
async def test_provider_error_classification_has_no_raw_body_or_secret(status, code, retryable):
    async def respond(request):
        return httpx.Response(status, json={"error": {"code": code, "message": "private provider response"}})

    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        adapter = OpenAICompatibleModel(model_settings(), client)
        with pytest.raises(AgentError) as exc:
            await adapter.generate("plan", {}, IntentPlan)
    assert exc.value.retryable == retryable
    assert "private provider response" not in str(exc.value)
    assert "fixture-secret-key" not in str(exc.value)


async def test_provider_timeout_and_invalid_structured_output():
    async def timeout(request):
        raise httpx.ReadTimeout("private timeout detail", request=request)

    async with httpx.AsyncClient(transport=httpx.MockTransport(timeout)) as client:
        with pytest.raises(AgentError) as exc:
            await OpenAICompatibleModel(model_settings(), client).generate("plan", {}, IntentPlan)
        assert exc.value.code == "MODEL_TIMEOUT" and exc.value.retryable

    async def malformed(request):
        return httpx.Response(200, json=completion({"steps": "invalid-shape"}))

    async with httpx.AsyncClient(transport=httpx.MockTransport(malformed)) as client:
        with pytest.raises(AgentError) as exc:
            await OpenAICompatibleModel(model_settings(), client).generate("plan", {}, IntentPlan)
        assert exc.value.code == "MODEL_OUTPUT_INVALID"


@pytest.mark.parametrize("method", ["function_calling", "json_schema", "json_mode"])
async def test_truncated_response_is_explicit_and_never_published_as_valid_output(method):
    async def truncated(request):
        body = completion({"steps": []})
        body["choices"][0]["finish_reason"] = "length"
        return httpx.Response(200, json=body)

    async with httpx.AsyncClient(transport=httpx.MockTransport(truncated)) as client:
        with pytest.raises(AgentError) as exc:
            await OpenAICompatibleModel(model_settings(model_structured_method=method), client).generate(
                "plan", {}, IntentPlan
            )
        assert exc.value.code == "MODEL_OUTPUT_TRUNCATED" and not exc.value.retryable


@pytest.mark.live
async def test_live_model_smoke():
    # Explicit opt-in. Only a tiny synthetic evidence payload is sent; no business database access.
    config = {}
    root_env = Path(__file__).resolve().parents[2] / ".env"
    if root_env.exists():
        for line in root_env.read_text(encoding="utf-8-sig").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                key, value = line.split("=", 1)
                config[key.strip()] = value.strip().strip('"').strip("'")
    from dotenv import dotenv_values

    module_config = dotenv_values(Path(__file__).resolve().parents[1] / ".env")
    settings = model_settings(model_max_output_tokens=4096)
    for field, key in (("model_base_url", "JSD_AIRD_MODEL_BASE_URL"), ("model_name", "JSD_AIRD_MODEL_NAME")):
        module_key = "JSD_AIRD_AGENTS_" + key.removeprefix("JSD_AIRD_")
        setattr(
            settings, field, os.environ.get(module_key, module_config.get(module_key) or config.get(key, ""))
        )
    from pydantic import SecretStr

    settings.model_api_key = SecretStr(
        os.environ.get(
            "JSD_AIRD_AGENTS_MODEL_API_KEY",
            module_config.get("JSD_AIRD_AGENTS_MODEL_API_KEY") or config.get("JSD_AIRD_MODEL_API_KEY", ""),
        )
    )
    if not settings.model_configured:
        pytest.fail("Live model smoke explicitly enabled but no model configuration exists")
    adapter = OpenAICompatibleModel(settings)
    try:
        result, usage = await adapter.generate(
            "answer",
            {
                "request": "仅复述给定事实，不调用工具。",
                "evidence": [{"id": "ev_demo", "content": "模拟任务截止日期为2026-11-10。"}],
                "receipts": [],
            },
            AnswerDocument,
        )
    finally:
        await adapter.aclose()
    assert result.claims and all(c.evidence_ids == ["ev_demo"] for c in result.claims)


def test_retry_after_missing_invalid_and_http_date():
    assert retry_after_seconds(None) is None
    assert retry_after_seconds("2") == 2
    assert retry_after_seconds("NaN") is None
    assert retry_after_seconds("bad") is None
    assert retry_after_seconds("Wed, 21 Oct 2015 07:28:00 GMT") == 0
