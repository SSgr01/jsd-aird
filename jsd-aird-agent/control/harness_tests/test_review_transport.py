import json

import pytest
from fastapi import HTTPException

from aird_agent.capabilities.literature.quality import ReportAuditor, ReviewOutputInvalid, unavailable_review


async def test_review_requires_a_native_generation_service(settings):
    with pytest.raises(HTTPException, match="REPORT_REVIEW_MODEL_NOT_CONFIGURED"):
        await ReportAuditor(settings).review({"units": [], "questions": []})


@pytest.mark.parametrize("content", ["not json", '{"units": [', None, ""])
async def test_invalid_output_has_one_explicit_repair_and_never_becomes_a_content_verdict(settings, content):
    calls = []

    async def generate(system, messages):
        calls.append((system, messages))
        return {"content": content, "model": "selected-model", "provider": "native-provider"}

    prepared = {"units": [], "questions": [], "required_unit_ids": [], "required_question_ids": [],
                "report_hash": "hash", "matrix_revision": 0}
    auditor = ReportAuditor(settings)
    with pytest.raises(ReviewOutputInvalid) as invalid:
        await auditor.review(prepared, generate=generate)
    assert len(calls) == 1
    with pytest.raises(ReviewOutputInvalid):
        await auditor.review(prepared, repair=invalid.value, generate=generate)
    assert len(calls) == 2
    assert json.loads(calls[-1][1][1]["content"]) == {}
    failure = unavailable_review(prepared, error=invalid.value)
    assert failure["units"] == [] and failure["review_diagnostics"]


async def test_native_provider_failure_propagates_instead_of_marking_facts_unsupported(settings):
    async def unavailable(system, messages):
        raise TimeoutError("Native provider timed out")

    with pytest.raises(TimeoutError):
        await ReportAuditor(settings).review({"units": [], "questions": []}, generate=unavailable)
