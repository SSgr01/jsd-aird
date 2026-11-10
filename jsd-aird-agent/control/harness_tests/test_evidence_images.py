import copy
from unittest.mock import AsyncMock
from uuid import uuid4

import pytest
from fastapi import HTTPException

from aird_agent.capabilities.literature.handler import LiteratureTools
from aird_agent.capabilities.registry import CORE_INSTRUCTIONS, default_registry
from aird_agent.evidence.presentation import model_result


def fixture(settings):
    source = {"kind": "KB_PUBLICATION", "document_id": str(uuid4()), "publication_id": str(uuid4()),
              "version_id": str(uuid4()), "source_node_key": str(uuid4()), "content_sha256": "a" * 64,
              "anchor": {"original": {"page": 3, "assetFileId": str(uuid4())}}, "title": "study.pdf"}
    eid = "e" + "1" * 24
    run = {"id": str(uuid4()), "evidence": {eid: {"evidence_id": eid, "source": source, "text": "Figure 2"}}}
    host = AsyncMock(return_value={"source": source, "image": {"view": "figure", "page": 3},
        "business_file": {"name": "figure.png", "content_type": "image/png", "content_base64": "private-bytes"}})
    tools = LiteratureTools(settings, None, host_call=host, active_run=AsyncMock(),
                            context_request=lambda _: {"retrieval_scope": {"knowledge_documents": [
                                {key: source[key] for key in ("document_id", "publication_id")}] }},
                            character_budget=lambda: 16000)
    return tools, host, run, eid


@pytest.mark.parametrize("view", ["auto", "page", "detail"])
async def test_image_read_uses_only_registered_source_and_keeps_scope(settings, principal, view):
    tools, host, run, eid = fixture(settings)
    before = copy.deepcopy(run)
    result = await tools.dispatch(principal, run, {}, "evidence_image_read", {"evidence_id": eid, "view": view}, "call")
    args = host.call_args.args
    assert args[1] == "source_image" and args[2]["source"] == run["evidence"][eid]["source"]
    assert args[2]["view"] == view and args[2]["retrieval_scope"]["knowledge_documents"]
    assert result["evidence_id"] == eid and "read_image" in result["image_analysis_hint"]
    assert "content_base64" not in model_result(result)["business_file"]
    assert run == before  # Viewing a figure does not mark text as read or create invented quotes.


async def test_unknown_or_private_source_is_not_forwarded(settings, principal):
    tools, host, run, eid = fixture(settings)
    with pytest.raises(HTTPException, match="UNKNOWN_EVIDENCE_ID"):
        await tools.dispatch(principal, run, {}, "evidence_image_read", {"evidence_id": "e" + "0" * 24}, "call")
    run["evidence"][eid]["source"]["kind"] = "RUN_UPLOAD"
    with pytest.raises(HTTPException, match="SOURCE_IMAGE_REQUIRES"):
        await tools.dispatch(principal, run, {}, "evidence_image_read", {"evidence_id": eid}, "call")
    host.assert_not_called()


async def test_permission_is_rechecked_on_every_image_read(settings, principal):
    tools, host, run, eid = fixture(settings)
    await tools.dispatch(principal, run, {}, "evidence_image_read", {"evidence_id": eid}, "one")
    host.side_effect = HTTPException(403, "revoked")
    with pytest.raises(HTTPException, match="revoked"):
        await tools.dispatch(principal, run, {}, "evidence_image_read", {"evidence_id": eid}, "two")
    assert host.await_count == 2


def test_image_capability_is_discoverable_and_native_instructions_require_actual_pixels():
    literature = default_registry().select(["literature"])[0]
    assert "evidence_image_read" in literature.initial_tools
    assert "read_image" in CORE_INSTRUCTIONS
    spec = next(tool for tool in literature.tools if tool["name"] == "evidence_image_read")
    assert spec["parameters"]["required"] == ["evidence_id"]
