import asyncio
import json

import pytest
from fastapi import HTTPException

from aird_agent.capabilities.literature.rcs import RCS


def evidence():
    return {"evidence_id": "known", "source": {"title": "合成证据"}, "text": "样品A：25 °C，结果0；样品B：未测"}


async def test_native_model_callback_keeps_control_responsive_and_preserves_authoritative_source():
    started, release = asyncio.Event(), asyncio.Event()

    async def provider(system, messages):
        assert json.loads(messages[0]["content"])["evidence"][0]["text"] == evidence()["text"]
        started.set()
        await release.wait()
        return {"content": json.dumps({"contexts": [{"evidence_id": "known", "score": 8,
            "summary": "A在25 °C结果0，B未测；两者不能合并。"}]}), "model": "selected-native-model", "provider": "native"}

    task = asyncio.create_task(RCS(None).score("synthetic", [evidence()], generate=provider))
    try:
        await asyncio.wait_for(started.wait(), 3)
        assert not task.done()
    finally:
        release.set()
    result = await task
    assert result["model"] == "selected-native-model"
    assert result["contexts"][0]["source"] == evidence()["source"]
    assert result["contexts"][0]["text"] == evidence()["text"]
    assert result["contexts"][0]["verification_status"] == "AI_EXTRACTED"


@pytest.mark.parametrize("contexts", [
    [{"evidence_id": "invented", "score": 8, "summary": "untrusted"}],
    [{"evidence_id": "known", "score": True, "summary": "untrusted"}],
    [{"evidence_id": "known", "score": 11, "summary": "untrusted"}],
    [],
])
async def test_invalid_model_output_returns_service_failure_without_content_verdict(contexts):
    async def provider(system, messages):
        return {"content": json.dumps({"contexts": contexts})}
    with pytest.raises(HTTPException, match="no evidence scores were recorded") as caught:
        await RCS(None).score("synthetic", [evidence()], generate=provider)
    assert caught.value.status_code == 502


async def test_native_model_cancellation_is_not_converted_into_a_quality_verdict():
    async def provider(system, messages):
        raise asyncio.CancelledError()
    with pytest.raises(asyncio.CancelledError):
        await RCS(None).score("synthetic", [evidence()], generate=provider)
