import json

import httpx
import pytest
from pydantic import SecretStr

from aird_agent.capabilities.literature.quality import ReportAuditor


@pytest.mark.parametrize("thinking", [None, False])
async def test_review_provider_switch_is_explicit_and_validated_output_remains_required(
    settings, monkeypatch, thinking
):
    prepared = {
        "units": [{"unit_id": "u1", "text": "Modulus: 12 kPa", "quotes": [{"quote_id": "q1"}]}],
        "questions": [{"question_id": "modulus"}],
        "report_hash": "hash",
        "matrix_revision": 1,
    }
    answer = {
        "units": [{"unit_id": "u1", "verdict": "SUPPORTED", "quote_ids": ["q1"], "reason": "Literal source"}],
        "questions": [{"question_id": "modulus", "verdict": "COVERED", "reason": "Answered"}],
    }

    def respond(request):
        payload = json.loads(request.content)
        assert payload["response_format"] == {"type": "json_object"}
        if thinking is None:
            assert "enable_thinking" not in payload
        else:
            assert payload["enable_thinking"] is False
        return httpx.Response(
            200, json={"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(answer)}}]}
        )

    client = httpx.AsyncClient
    monkeypatch.setattr(
        httpx, "AsyncClient", lambda **kwargs: client(transport=httpx.MockTransport(respond), **kwargs)
    )
    config = settings.model_copy(
        update={
            "paperqa_model": "openai/synthetic",
            "paperqa_base_url": "https://review.test/v1",
            "paperqa_api_key": SecretStr("synthetic-key"),
            "paperqa_enable_thinking": thinking,
        }
    )
    result = await ReportAuditor(config).review(prepared)
    assert result["passed"] and result["verification"] == "AI_REVIEW_NOT_HUMAN_VERIFICATION"
