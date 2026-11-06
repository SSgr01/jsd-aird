"""Cross-domain file behavior, independent of models or a database."""

import hashlib
import json

import pytest
from fastapi import HTTPException

from aird_agent.capabilities.files.completion import check_file_completion
from aird_agent.capabilities.files.quality import validate_format
from aird_agent.capabilities.registry import default_registry
from aird_agent.core.capabilities import CapabilityPack, CapabilityRegistry
from aird_agent.evidence.exports import export_sources
from aird_agent.evidence.quotes import quotes


def candidate():
    text = "Water content is approximately 81 percent."
    return {
        "evidence_id": "e" + "1" * 24, "text": text,
        "source": {"kind": "KB_PUBLICATION", "content_sha256": hashlib.sha256(text.encode()).hexdigest()},
    }


def test_only_core_and_files_support_generic_downloads_without_research():
    registry = default_registry()
    tools = {tool["name"] for tool in registry.tools(["core", "files"])}
    assert "artifact_save" in tools
    assert not {"workspace_write", "workspace_read", "workspace_list", "sandbox_prepare", "sandbox_execute"} & tools
    assert not {"report_check", "report_save", "matrix_record", "knowledge_search"} & tools
    validate_format("output/user-data.csv", b"name,value\nwater,0.81\n")
    validate_format("output/user-data.json", b'{"value": 0.81}')
    check_file_completion(
        {"required_artifacts": ["user-data.csv"], "registered_outputs": [{"kind": "artifact", "path": "output/user-data.csv"}]},
    )


def test_business_catalog_leaves_planning_and_completion_to_native_dsh():
    tools = {item["name"] for item in default_registry().tools(["core", "literature", "files"])}
    assert not {"plan_update", "finish_task", "workspace_read", "workspace_write", "sandbox_execute"} & tools
    assert {"report_check", "report_save", "artifact_save"} <= tools


def test_professional_guidance_uses_native_skills_without_a_parallel_skill_tool():
    from pathlib import Path
    registry = default_registry()
    ids = ["core", "literature", "files", "experiments"]
    assert "skill_read" not in {item["name"] for item in registry.tools(ids)}
    instructions = registry.instructions(ids)
    assert "普通回答和文件不强制" in instructions
    assert "question_targets不得偷换" not in instructions
    skill = Path(__file__).parents[2] / "enterprise-plugin/skills/literature-review/SKILL.md"
    assert "report_check" in skill.read_text(encoding="utf-8")


def test_unrelated_run_evidence_is_neither_authorized_nor_attached_to_output():
    # A source not used by this file must not even need to be readable.
    run = {"evidence": {"unrelated": {"text": "changed", "source": {"content_sha256": "old"}}}}
    assert export_sources(run, [], {}, []) == ([], [])


def test_selected_evidence_and_explicit_quotes_keep_source_identity():
    item = candidate()
    run = {"evidence": {item["evidence_id"]: item}}
    quote = quotes(item)[0]
    resolved, refs = export_sources(run, [], {}, [json.dumps(item).encode()])
    assert resolved[0]["quote_id"] == quote["quote_id"]
    assert refs == [item["source"]]
    wrong = {"evidence_id": "e" + "2" * 24, "quote_id": quote["quote_id"]}
    with pytest.raises(HTTPException, match="QUOTE_HANDLE_INVALID_OR_WRONG_EVIDENCE"):
        export_sources(run, [wrong], {}, [])
    with pytest.raises(HTTPException, match="ANALYSIS_SOURCE_QUOTES_REQUIRED"):
        export_sources(run, [], {"quote_ids": ["q" + "9" * 24]}, [])


@pytest.mark.parametrize("path,content", [
    ("output/a.csv", b"a,a\n1,2\n"),
    ("output/a.csv", b"a,b\na,b\n1,2\n"),
    ("output/a.tsv", b"a\tb\n1\n"),
    ("output/a.json", b"{not json}"),
    ("output/a.json", b'{"value": NaN}'),
    ("output/a.json", b'{"value": Infinity}'),
    ("output/a.json", b'{"value": -Infinity}'),
])
def test_generic_exports_still_reject_malformed_formats(path, content):
    with pytest.raises(HTTPException):
        validate_format(path, content)


@pytest.mark.parametrize("suffix", ["docx", "xlsx", "pdf"])
def test_file_extension_does_not_make_plain_text_a_document(suffix):
    with pytest.raises(HTTPException, match="EXPORT_FORMAT_INVALID"):
        validate_format("output/fake." + suffix, b"# Not a binary document")


def test_on_demand_guides_are_copied_and_bound_to_capability_fingerprint():
    guide = {"formats": "original instructions"}
    registry = CapabilityRegistry()
    registry.register(CapabilityPack("files", "1", "files", (), resources=guide))
    pinned = registry.pin(["files"])
    guide["formats"] = "external mutation"
    assert registry.pin(["files"]) == pinned
    changed = CapabilityRegistry()
    changed.register(CapabilityPack("files", "1", "files", (), resources=guide))
    with pytest.raises(ValueError, match="PACK_VERSION_MISMATCH"):
        changed.verify(["files"], pinned)
