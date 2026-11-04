import json

import pytest
from fastapi import HTTPException

from aird_agent.capabilities.export_quality import validate_export
from aird_agent.hosts.jsd.task_state import check_completion

REF = {"evidence_id": "e" + "1" * 24, "quote_id": "q" + "2" * 24}
HEADER = "value,unit,formula,evidence_id,quote_id\r\n"
ROW = f"0.81,kg,1*0.81,{REF['evidence_id']},{REF['quote_id']}\r\n"


@pytest.mark.parametrize(
    "content,reason",
    [
        (HEADER + HEADER + ROW, "EXPORT_HEADER_REPEATED"),
        ("value,value\n0.81,kg\n", "EXPORT_HEADER_INVALID"),
        ("value,unit\n0.81,kg\n", "EXPORT_CITATIONS_MISSING"),
        (HEADER + ROW.rstrip() + ",extra\n", "EXPORT_ROW_INVALID"),
        (HEADER, "EXPORT_ROWS_MISSING"),
        (HEADER + ROW.replace(REF["quote_id"], "q" + "3" * 24), "EXPORT_QUOTE_INVALID"),
        (HEADER + ROW.replace(REF["evidence_id"], "e" + "4" * 24), "EXPORT_QUOTE_INVALID"),
    ],
)
def test_export_rejects_malformed_tables_and_foreign_or_missing_citations(content, reason):
    with pytest.raises(HTTPException, match=reason):
        validate_export("output/result.csv", content.encode(), [REF])


def test_export_accepts_bom_quoted_multiline_and_matching_json_without_changing_bytes():
    content = ("\ufeff" + HEADER + ROW.replace("1*0.81", '"1*0.81,\nrounded input"')).encode()
    original = bytes(content)
    validate_export("output/result.csv", content, [REF])
    validate_export(
        "output/result.json", json.dumps({"water_mass": 0.81, "source_quotes": [REF]}).encode(), [REF]
    )
    assert content == original


def test_json_requires_embedded_citations_and_rejects_mismatched_pairs():
    with pytest.raises(HTTPException, match="EXPORT_CITATIONS_MISSING"):
        validate_export("output/result.json", b'{"water_mass":0.81}', [REF])
    with pytest.raises(HTTPException, match="EXPORT_QUOTE_INVALID"):
        validate_export(
            "output/result.json", json.dumps({**REF, "quote_id": "other-version"}).encode(), [REF]
        )
    validate_export("output/result.json", b'{"water_mass":0.81}', [])


def test_user_given_basis_can_have_no_citation_but_source_rows_must_use_real_pairs():
    validate_export("output/result.csv", (HEADER + "1,kg,user given,,\n" + ROW).encode(), [REF])
    with pytest.raises(HTTPException, match="EXPORT_CITATIONS_MISSING"):
        validate_export("output/result.csv", (HEADER + "1,kg,user given,,\n").encode(), [REF])
    with pytest.raises(HTTPException, match="EXPORT_QUOTE_INVALID.*row 2"):
        validate_export("output/result.csv", (HEADER + f"1,kg,source,{REF['evidence_id']},\n").encode(), [REF])


def test_complete_requires_each_named_file_and_partial_keeps_missing_delivery_visible():
    run = {
        "evidence": {},
        "plan": {"task_type": "GENERAL", "steps": [], "deliverables": ["analysis_file"]},
        "required_artifacts": ["result.json", "result.csv"],
        "registered_outputs": [{"kind": "artifact", "title": "result.json"}],
    }
    body = {"outcome": "COMPLETE", "checks": [{"requirement": "export", "status": "covered"}], "gaps": []}
    with pytest.raises(HTTPException, match="DELIVERABLE_FILES_MISSING.*result.csv"):
        check_completion(run, body)
    run["registered_outputs"].append({"kind": "artifact", "title": "unrelated.csv"})
    with pytest.raises(HTTPException, match="DELIVERABLE_FILES_MISSING"):
        check_completion(run, body)
    assert (
        check_completion(run, {**body, "outcome": "PARTIAL", "gaps": ["CSV not delivered"]})["outcome"]
        == "PARTIAL"
    )
    run["registered_outputs"].append({"kind": "artifact", "path": "output/result.csv"})
    assert check_completion(run, body)["outcome"] == "COMPLETE"
