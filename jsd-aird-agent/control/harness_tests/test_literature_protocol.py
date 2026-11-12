"""Guidance versions must not disable source-reading or report safeguards."""

import hashlib
from contextlib import asynccontextmanager
from types import SimpleNamespace
from unittest.mock import AsyncMock
from uuid import uuid4

import pytest
from fastapi import HTTPException

from aird_agent.capabilities.literature.handler import LiteratureTools
from aird_agent.capabilities.literature.quality import report_digest, uses_source_review
from aird_agent.hosts.jsd.task_state import check_delivery


@pytest.fixture
def literature(tmp_path, principal):
    text = "The sample was cured at 25 degrees Celsius."
    candidate = {"evidence_id": "e" + "a" * 24, "text": text, "source": {
        "document_id": "document", "publication_id": "publication",
        "content_sha256": hashlib.sha256(text.encode()).hexdigest(),
    }}
    run = {"id": str(uuid4()), "epoch": 1, "evidence": {candidate["evidence_id"]: candidate},
           "pack_versions": {"literature": {"version": "5"}}}

    @asynccontextmanager
    async def transaction(*_args):
        yield object()

    host = AsyncMock(return_value={"id": "report"})
    tool = LiteratureTools(SimpleNamespace(storage_root=tmp_path),
        SimpleNamespace(transaction=transaction, update=AsyncMock()), host_call=host,
        active_run=AsyncMock(return_value=run), context_request=lambda _: {}, character_budget=lambda: 20000)
    tool.ensure_table_headers = AsyncMock(return_value=run)
    tool.ensure_research_topic = AsyncMock(return_value={"id": "topic"})
    return tool, run, candidate, host


@pytest.mark.parametrize("version", ["4", "5", "6"])
async def test_new_guidance_keeps_quote_handles_and_table_header_enrichment(literature, principal, version):
    tool, run, candidate, _ = literature
    run["pack_versions"]["literature"]["version"] = version
    result = await tool.dispatch(principal, run, {}, "evidence_read", {"evidence_id": candidate["evidence_id"]}, "read")
    assert result["quotes"] and result["quotes"][0]["quote_id"].startswith("q")
    assert result["quotes"][0]["text"] == candidate["text"]
    tool.ensure_table_headers.assert_awaited_once()


@pytest.mark.parametrize("version", ["4", "5", "6"])
async def test_new_guidance_rejects_unreviewed_reports_before_host_write(literature, principal, version):
    tool, run, candidate, host = literature
    run["pack_versions"]["literature"]["version"] = version
    args = {"title": "Report", "text": f"Measured property [E:{candidate['evidence_id']}]"}
    with pytest.raises(HTTPException, match="REPORT_REVIEW_REQUIRED"):
        await tool.dispatch(principal, run, {}, "report_save", args, "save")
    host.assert_not_awaited()
    tool.ensure_research_topic.assert_not_awaited()


@pytest.mark.parametrize("version", ["4", "5", "6"])
async def test_new_guidance_preserves_verified_matrix_in_saved_report(literature, principal, version):
    tool, run, candidate, host = literature
    run["pack_versions"]["literature"]["version"] = version
    args = {"title": "Report", "text": f"Measured property [E:{candidate['evidence_id']}]"}
    run["research_matrix"] = {"revision": 1}
    key = report_digest(args["title"], args["text"], run["research_matrix"])
    review = {"passed": True, "quality_check_version": 3, "matrix_revision": 1, "report_hash": key}
    run["report_checks"] = {key: review}
    await tool.dispatch(principal, run, {}, "report_save", args, "save")
    saved = host.call_args.args[2]
    assert saved["quality_protocol"] == 4
    assert saved["quality_review"] == review
    assert saved["evidence_matrix"] == run["research_matrix"]


@pytest.mark.parametrize("version", ["4", "5", "6"])
def test_new_guidance_cannot_complete_with_a_report_from_an_obsolete_review(version):
    run = {"report_requested": True, "pack_versions": {"literature": {"version": version}},
           "research_matrix": {"revision": 1}, "report_checks": {"old": {"passed": True, "quality_check_version": 2}},
           "registered_outputs": [{"kind": "report", "quality_review": {
               "passed": True, "matrix_revision": 1, "report_hash": "old"}}]}
    with pytest.raises(HTTPException, match="CURRENT_MATRIX_CHECKED_REPORT_REQUIRED"):
        check_delivery(run)


@pytest.mark.parametrize("version", [None, "1", "2", "3"])
def test_pre_review_legacy_protocol_is_unchanged(version):
    run = {"pack_versions": {"literature": {"version": version}}} if version else {}
    assert not uses_source_review(run)
