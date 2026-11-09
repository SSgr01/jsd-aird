import copy
import hashlib

import pytest
from fastapi import HTTPException

from aird_agent.capabilities.literature.quality import (
    absence_findings,
    certainty_findings,
    matrix_summary,
    prepare_review,
    quotes,
    report_units,
    resolve_quote,
    validate_review,
)
from aird_agent.hosts.jsd.task_state import check_completion, read_progress, record_read, source_catalog


def test_recovery_catalog_reuses_handles_without_merging_publication_versions():
    evidence = {}
    for i, (publication, text) in enumerate(
        [("v1", "page footer"), ("v1", "Original results. " * 50), ("v2", "Revised results.")]
    ):
        evidence[str(i)] = {
            "evidence_id": str(i),
            "text": text,
            "node_offset": i,
            "source": {
                "kind": "KB_PUBLICATION",
                "document_id": "doc",
                "publication_id": publication,
                "version_id": publication,
                "title": "Research",
            },
        }
    catalog = source_catalog({"evidence": evidence})
    assert len(catalog) == 2
    assert catalog[0]["publication_id"] == "v1" and catalog[1]["publication_id"] == "v2"
    assert catalog[0]["reading_handles"][0] == {"evidence_id": "1", "node_offset": 1}
    assert catalog[0]["evidence_count"] == 2
    assert "Original results" not in str(catalog)


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


def test_recovery_matrix_retains_exact_quote_and_evidence_pairs_without_copying_source_text():
    span = {"evidence_id": "e-original", "quote_id": "q-original", "text": "Original source.",
            "source": {"publication_id": "v1"}}
    row = {"question_id": "healing", "question": "How fast?", "status": "CANDIDATE", "gap_note": "",
           "claims": [{"claim": "Healing time", "quotes": [span]}]}
    summary = matrix_summary({"research_matrix": {"revision": 1, "questions": {"healing": row}}})
    claim = summary["questions"][0]["claims"][0]
    assert claim["quote_refs"] == [{"evidence_id": "e-original", "quote_id": "q-original"}]
    assert claim["quote_ids"] == ["q-original"]
    assert "Original source" not in str(summary)
    assert span["source"]["publication_id"] == "v1"


def test_report_rejection_identifies_unadopted_citations_and_still_requires_exact_quotes():
    text = "Modulus: 12 kPa."
    evidence = {
        "e" + digit * 24: {
            "evidence_id": "e" + digit * 24,
            "text": text,
            "source": {"publication_id": digit, "content_sha256": hashlib.sha256(text.encode()).hexdigest()},
        }
        for digit in ("a", "b")
    }
    first, second = evidence.values()
    row = {
        "question_id": "modulus",
        "question": "What is the modulus?",
        "gap_note": "",
        "claims": [{"claim": text, "quotes": [resolve_quote(evidence, quotes(first)[0])]}],
    }
    run = {"evidence": evidence, "research_matrix": {"revision": 1, "questions": {"modulus": row}}}
    with pytest.raises(HTTPException, match="REPORT_CITATION_FORMAT"):
        prepare_review(run, "Results", f"{text} [E:{first['evidence_id']}:q-invented]")
    with pytest.raises(HTTPException, match="no \\[E:evidence_id\\] citations"):
        prepare_review(run, "Results", text)
    report = text + " [E:" + second["evidence_id"] + "]"
    with pytest.raises(HTTPException, match=second["evidence_id"]):
        prepare_review(run, "Results", report)
    row["claims"][0]["quotes"].append(resolve_quote(evidence, quotes(second)[0]))
    prepared = prepare_review(run, "Results", report)
    assert any(
        span["evidence_id"] == second["evidence_id"] for unit in prepared["units"] for span in unit["quotes"]
    )


@pytest.mark.parametrize("layout", ["table", "bullets", "numbered"])
def test_report_rows_cannot_borrow_another_samples_citation(layout):
    evidence = {
        "e" + letter * 24: {
            "evidence_id": "e" + letter * 24,
            "text": text,
            "source": {"publication_id": "v1", "content_sha256": hashlib.sha256(text.encode()).hexdigest()},
        }
        for letter, text in [("a", "Sample A modulus: 12 kPa."), ("b", "Sample B modulus: 8 kPa.")]
    }
    spans = [resolve_quote(evidence, quotes(candidate)[0]) for candidate in evidence.values()]
    a, b = evidence
    lines = {
        "table": f"| Sample | Modulus | Source |\n|---|---|---|\n| A | 8 kPa | [E:{a}] |\n| B | 8 kPa | [E:{b}] |",
        "bullets": f"- Sample A: 8 kPa [E:{a}]\n- Sample B: 8 kPa [E:{b}]",
        "numbered": f"1. Sample A: 8 kPa [E:{a}]\n2. Sample B: 8 kPa [E:{b}]",
    }
    run = {
        "request": "Compare A and B under the same conditions.",
        "evidence": evidence,
        "research_matrix": {"revision": 1, "questions": {"modulus": {
            "question_id": "modulus", "question": "Compare modulus", "gap_note": "",
            "claims": [{"claim": "Modulus values", "quotes": spans}],
        }}},
    }
    prepared = prepare_review(run, "Modulus comparison", lines[layout])
    assert prepared["request"] == run["request"]
    first = next(unit for unit in prepared["units"] if "8 kPa" in unit["text"])
    assert {quote["evidence_id"] for quote in first["quotes"]} == {a}
    # Even if a reviewer borrows B's genuinely matching number, it cannot pass A.
    answer = {
        "units": [{"unit_id": unit["unit_id"], "verdict": "NON_FACTUAL", "quote_ids": [], "reason": "heading"}
                  for unit in prepared["units"]],
        "questions": [{"question_id": "modulus", "verdict": "COVERED", "reason": "compared"}],
    }
    next(unit for unit in answer["units"] if unit["unit_id"] == first["unit_id"]).update(
        verdict="SUPPORTED", quote_ids=[spans[1]["quote_id"]]
    )
    with pytest.raises(ValueError, match="unrelated quote handles"):
        validate_review(prepared, answer)


def test_repeated_citations_do_not_exhaust_the_distinct_source_review_budget():
    text = "The method requires a mounting step with unmeasured duration. " * 15
    candidate = {"evidence_id": "e" + "a" * 24, "text": text, "source": {
        "publication_id": "v1", "content_sha256": hashlib.sha256(text.encode()).hexdigest(),
        "anchor": {"coordinates": list(range(600))},
    }}
    evidence = {candidate["evidence_id"]: candidate}
    span = resolve_quote(evidence, quotes(candidate)[0])
    run = {"evidence": evidence, "research_matrix": {"revision": 1, "questions": {"method": {
        "question_id": "method", "question": "What is the method?", "gap_note": "",
        "claims": [{"claim": "Mounting is required", "quotes": [span]}],
    }}}}
    report = "\n\n".join("Mounting duration is not quantified here. [E:" + candidate["evidence_id"] + "]" for _ in range(60))
    prepared = prepare_review(run, "Method", report)
    assert len(prepared["units"]) == 61
    # Review keeps the selected quote and its complete same-block context;
    # repeated citations still count that context only once toward the budget.
    assert all(span in unit["quotes"] for unit in prepared["units"][:-1])
    assert all(unit["quotes"] == prepared["units"][0]["quotes"] for unit in prepared["units"][:-1])


def test_different_studies_in_one_paragraph_keep_their_own_citations():
    units = report_units("本文试样贴合3分钟后装夹。[E:study]另一种材料恢复需24小时[E:other]。")
    assert len(units) == 2
    assert units[0]["context_evidence_ids"] == ["study"]
    assert "[E:study]" in units[0]["text"]
    assert units[1]["context_evidence_ids"] == ["other"]
    # One citation can still apply to the consecutive sentences of one source.
    shared = report_units("先贴合3分钟。装夹需要数分钟[E:study]。")
    assert all(unit["context_evidence_ids"] == ["study"] for unit in shared)


def test_connective_labels_do_not_become_scientific_assertions_through_citation_inheritance():
    units = report_units("具体而言：\n强度已完全恢复：\n试样贴合3分钟[E:study]。")
    assert units[0]["context_evidence_ids"] == []
    assert units[1]["context_evidence_ids"] == ["study"]


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


@pytest.mark.parametrize(
    "text,passed",
    [("4.26（保留两位有效数字）", False), ("4.26（保留三位有效数字）", True),
     ("0.8100（保留4位有效数字）", True), ("0.8100（保留两位有效数字）", False),
     ("4.263157894737（保留十二位有效数字）", False)],
)
def test_decimal_precision_claims_are_checked_even_when_the_reviewer_says_supported(review, text, passed):
    prepared, answer = review
    prepared["units"][0]["text"] = text
    assert validate_review(prepared, answer)["passed"] is passed


@pytest.mark.parametrize(
    "text,passed",
    [("### 2.1 自愈时间", True), ("### 2.1.3 自愈时间", True), ("### 2.1 自愈时间为30秒", False), ("30 kg", False)],
)
def test_section_numbers_are_not_measurements_but_actual_numeric_assertions_still_need_sources(review, text, passed):
    prepared, answer = review
    prepared["units"][0].update(text=text, quotes=[])
    answer["units"][0].update(verdict="NON_FACTUAL", quote_ids=[])
    assert validate_review(prepared, answer)["passed"] is passed


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
