from types import SimpleNamespace
from aird_agent.evidence.links import citation_links, canonical_links
from aird_agent.hosts.jsd.bindings import JsdBindings


def test_section_label_uses_recorded_title_and_keeps_fixed_page_and_version():
    evidence = {"source": {"kind": "KB_PUBLICATION", "document_id": "doc",
        "version_id": "v2", "title": "PMC10007467_Long_Paper.pdf",
        "anchor": {"section_title": "配方与固化工艺", "original": {"page": 3}}}}
    link = citation_links({"e1": evidence}, "https://example.test")["e1"]
    assert link == {"label": "配方与固化工艺 · 第3页",
        "url": "https://example.test/api/v1/knowledge/documents/doc/versions/v2/content#page=3"}


def test_native_markdown_links_pin_original_version_page_and_evidence_identity():
    eid = "e" + "a" * 24
    evidence = {eid: {"text": "source", "source": {"kind": "KB_PUBLICATION", "document_id": "doc",
                    "version_id": "version", "anchor": {"original": {"page": 3}}}}}
    links = citation_links(evidence, "https://example.test")
    url = links[eid]["url"] + "&evidence=" + eid
    text = "A fact [原文·第3页](" + url + ")"
    assert canonical_links(text, links) == "A fact [E:" + eid + "]"
    assert canonical_links(text.replace("example.test", "untrusted.test"), links) == text.replace("example.test", "untrusted.test")
    binding = JsdBindings()
    binding.settings = SimpleNamespace(public_url="https://example.test")
    run = {"profile": "agent", "evidence": evidence, "context_refs": [{"kind": "KB_PUBLICATION", "resource_id": "doc"}]}
    assert binding.validate_output(run, text)[1]
    assert not binding.validate_output(run, "An unsupported answer with no citation")[1]


def test_unrelated_search_hits_do_not_force_a_citation_on_a_general_answer():
    binding = JsdBindings()
    binding.settings = SimpleNamespace(public_url="https://example.test")
    run = {"profile": "agent", "evidence": {"e" + "a" * 24: {"text": "Unrelated search hit"}}}
    assert binding.validate_output(run, "Temperature and strain both shift the Bragg wavelength.")[1]


def test_experiment_links_open_the_referenced_version_in_the_existing_editor():
    eid = "e" + "b" * 24
    links = citation_links({eid: {"source": {"kind": "BUSINESS_RECORD", "resource_type": "EXPERIMENT",
                           "resource_id": "experiment", "version_id": "v1", "anchor": {"versionNo": 1}}}}, "https://example.test")
    assert links[eid] == {"url": "https://example.test/experiments/experiment?version_id=v1", "label": "实验记录·V1"}
    assert canonical_links("[实验记录·V1](" + links[eid]["url"] + "#evidence=" + eid + ")", links) == "[E:" + eid + "]"


def test_uploaded_and_acquired_sources_use_the_owner_checked_original_route():
    for kind in ("RUN_UPLOAD", "EXTERNAL_FULLTEXT"):
        eid = "e" + "c" * 24
        links = citation_links({eid: {"source": {"kind": kind, "document_id": "private-doc",
                               "version_id": "immutable-file", "anchor": {"page": 5}}}}, "https://example.test")
        assert links[eid] == {"url": "https://example.test/api/v1/agents/private-sources/private-doc/content?version_id=immutable-file#page=5",
                              "label": "原文·第5页"}
        text = "[原文·第5页](" + links[eid]["url"] + "&evidence=" + eid + ")"
        assert canonical_links(text, links) == "[E:" + eid + "]"


def test_exact_quote_alias_preserves_immutable_source_and_rejects_unknown_or_changed_spans():
    import hashlib
    import pytest
    from fastapi import HTTPException
    from aird_agent.evidence.citations import normalize_citations, validate_report
    from aird_agent.evidence.quotes import quotes

    eid = "e" + "d" * 24
    candidate = {"evidence_id": eid, "text": "Sample A: 12.38 HZ after 2000 cycles.",
        "source": {"kind": "KB_PUBLICATION", "document_id": "doc", "version_id": "fixed-v2",
            "content_sha256": hashlib.sha256(b"Sample A: 12.38 HZ after 2000 cycles.").hexdigest(), "anchor": {"page": 7}}}
    evidence = {eid: candidate}
    qid = quotes(candidate)[0]["quote_id"]
    links = citation_links(evidence, "https://example.test", quote_aliases=True)
    assert links[qid] == {**links[eid], "evidence_id": eid}
    text = normalize_citations("A fact [E:" + qid + "]", evidence)
    assert text == "A fact [E:" + eid + "]"
    assert validate_report(text, evidence) == [eid]
    unknown = "A fact [E:q" + "0" * 24 + "]"
    assert normalize_citations(unknown, evidence) == unknown
    with pytest.raises(HTTPException, match="unknown evidence"):
        validate_report(unknown, evidence)
    candidate["text"] = "A changed measurement"
    with pytest.raises(HTTPException, match="QUOTE_SOURCE_HASH_MISMATCH"):
        citation_links(evidence, "https://example.test", quote_aliases=True)
