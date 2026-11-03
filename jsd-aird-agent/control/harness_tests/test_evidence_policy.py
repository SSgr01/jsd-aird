import copy
import hashlib

import pytest
from fastapi import HTTPException

from aird_agent.capabilities.literature.quality import (
    absence_findings,
    certainty_findings,
    quotes,
    resolve_quote,
    validate_review,
)
from aird_agent.hosts.jsd.task_state import check_completion, read_progress, record_read


def test_quote_handles_pin_exact_text_source_version_and_offsets():
    text = "  Modulus: 12 kPa.\nCured at 25 °C.  "
    candidate = {
        "evidence_id": "e" + "a" * 24,
        "text": text,
        "source": {
            "publication_id": "publication-1",
            "content_sha256": hashlib.sha256(text.encode()).hexdigest(),
        },
    }
    spans = quotes(candidate)
    assert spans and all(span["text"] == text[span["start"] : span["end"]] for span in spans)
    assert quotes(candidate) == spans
    changed = copy.deepcopy(candidate)
    changed["source"]["publication_id"] = "publication-2"
    assert quotes(changed)[0]["quote_id"] != spans[0]["quote_id"]
    with pytest.raises(HTTPException, match="QUOTE_HANDLE_INVALID_OR_WRONG_EVIDENCE"):
        resolve_quote({changed["evidence_id"]: changed}, spans[0])
    changed["text"] = "Modulus: 12 MPa."
    with pytest.raises(HTTPException, match="QUOTE_SOURCE_HASH_MISMATCH"):
        quotes(changed)


def test_fulltext_coverage_retains_holes_until_every_required_node_is_delivered():
    source = {"document_id": "doc", "publication_id": "pub"}
    run = {"read_coverage": {}, "evidence": {}, "research_requirements": {"fulltext_documents": [source]}}
    for start, count in [(4, 2), (0, 3)]:
        record_read(
            run, {**source, "offset": start}, {"trace": {"total_nodes": 6}, "candidates": [{}] * count}
        )
    progress = read_progress(run["read_coverage"])["doc:pub"]
    assert progress["read_nodes"] == 5
    assert progress["missing_ranges"] == [[3, 4]] and progress["next_unread_offset"] == 3
    with pytest.raises(HTTPException, match="FULLTEXT_COVERAGE_INCOMPLETE"):
        check_completion(run, {"outcome": "COMPLETE", "checks": []})
    record_read(run, {**source, "offset": 3}, {"trace": {"total_nodes": 6}, "candidates": [{}]})
    assert read_progress(run["read_coverage"])["doc:pub"]["missing_ranges"] == []


@pytest.fixture
def review():
    prepared = {
        "units": [{"unit_id": "u1", "text": "Modulus: 12 kPa", "quotes": [{"quote_id": "q1"}]}],
        "questions": [{"question_id": "modulus"}],
        "report_hash": "hash",
        "matrix_revision": 1,
    }
    answer = {
        "units": [{"unit_id": "u1", "verdict": "SUPPORTED", "quote_ids": ["q1"], "reason": "Exact source"}],
        "questions": [{"question_id": "modulus", "verdict": "COVERED", "reason": "Answered"}],
    }
    return prepared, answer


def test_review_pass_keeps_its_ai_verification_label(review):
    result = validate_review(*review)
    assert result["passed"] is True
    assert result["quality_check_version"] == 3
    assert result["verification"] == "AI_REVIEW_NOT_HUMAN_VERIFICATION"


def test_legacy_passing_report_cannot_complete_under_current_review_policy():
    run = {
        "plan": {"steps": [], "deliverables": ["report"]},
        "pack_versions": {"literature": {"version": "4"}},
        "research_matrix": {"revision": 1},
        "report_checks": {"old-hash": {"passed": True, "quality_check_version": 2}},
        "registered_outputs": [
            {
                "kind": "report",
                "quality_review": {"passed": True, "matrix_revision": 1, "report_hash": "old-hash"},
            }
        ],
    }
    with pytest.raises(HTTPException, match="CURRENT_MATRIX_CHECKED_REPORT_REQUIRED"):
        check_completion(run, {"outcome": "COMPLETE", "checks": []})


def test_reviewer_support_without_a_source_handle_is_an_actionable_failed_check(review):
    prepared, answer = review
    answer["units"][0]["quote_ids"] = []
    result = validate_review(prepared, answer)
    assert not result["passed"]
    assert result["units"][0]["verdict"] == "INSUFFICIENT"


@pytest.mark.parametrize("change", ["omit", "duplicate", "unrelated_quote", "non_factual"])
def test_review_cannot_skip_facts_or_borrow_other_units_quotes(review, change):
    prepared, answer = review
    if change == "omit":
        answer["units"] = []
    elif change == "duplicate":
        answer["units"] *= 2
    elif change == "unrelated_quote":
        answer["units"][0]["quote_ids"] = ["other-unit-quote"]
    else:
        answer["units"][0]["verdict"] = "NON_FACTUAL"
        assert validate_review(prepared, answer)["passed"] is False
        return
    with pytest.raises(ValueError):
        validate_review(prepared, answer)


def test_missing_user_question_cannot_pass_review(review):
    prepared, answer = review
    answer["questions"][0]["verdict"] = "MISSING"
    assert validate_review(prepared, answer)["passed"] is False


@pytest.mark.parametrize(
    "text", ["文献中未提及拉伸速率。", "文中未提供自修复效率百分比。", "全文未包含相关条件。"]
)
def test_document_absence_wording_cannot_bypass_source_span_prescreen(text):
    assert absence_findings([{"unit_id": "u1", "text": text}])


def test_explicit_causal_certainty_cannot_replace_a_tentative_source_statement(review):
    prepared, answer = review
    prepared["units"][0].update(
        text="文献明确将模量衰减归因于氢键重建减少。",
        quotes=[
            {"quote_id": "q1", "text": "The decrease may be caused by less reformation of hydrogen bonding."}
        ],
    )
    assert validate_review(prepared, answer)["passed"] is False
    prepared["units"][0]["text"] = "作者认为该变化可能由氢键重建减少造成。"
    assert not certainty_findings(prepared["units"])


@pytest.mark.parametrize(
    "text,quoted",
    [("LB21 与 PDMS/Fe 文献对比报告", False), ("具体分析如下：", True), ("**1. LB21 测试方法**", False)],
)
def test_material_identifier_titles_and_literal_connectives_are_not_measurements(review, text, quoted):
    prepared, answer = review
    prepared["units"][0].update(text=text, quotes=[{"quote_id": "q1"}] if quoted else [])
    answer["units"][0].update(verdict="NON_FACTUAL", quote_ids=[])
    assert validate_review(prepared, answer)["passed"] is True


@pytest.mark.parametrize("text", ["LB21 断裂应变为 1300%", "具体分析如下：模量为 12 kPa"])
def test_numeric_facts_cannot_hide_in_a_title_or_connective(review, text):
    prepared, answer = review
    prepared["units"][0].update(text=text, quotes=[])
    answer["units"][0].update(verdict="NON_FACTUAL", quote_ids=[])
    result = validate_review(prepared, answer)
    assert result["passed"] is False
    assert result["units"][0]["verdict"] == "INSUFFICIENT"
