import copy
import hashlib
import json
from unittest.mock import AsyncMock
from uuid import uuid4

import pytest
from fastapi import HTTPException
from test_retrieval_snapshots import runtime_for

from aird_agent.capabilities.registry import ANSWER_INSTRUCTIONS, default_registry
from aird_agent.core.capabilities import CapabilityPack, CapabilityRegistry
from aird_agent.evidence.citations import normalize_citations, validate_report
from aird_agent.evidence.metadata import merge_metadata
from aird_agent.evidence.navigation import cached_host, search_preview
from aird_agent.evidence.presentation import model_result
from aird_agent.hosts.jsd.bindings import JsdBindings
from aird_agent.hosts.jsd.task_state import bound_document_page


def document():
    source = {"kind": "KB_PUBLICATION", "document_id": str(uuid4()), "version_id": str(uuid4()),
              "publication_id": str(uuid4()), "title": "Actual paper", "source_node_key": str(uuid4()),
              "anchor": {"bibliography_revision": 2}, "content_sha256": hashlib.sha256(b"Actual evidence.").hexdigest()}
    candidate = {"evidence_id": "e" + "1" * 24, "text": "Actual evidence.", "source": source}
    metadata = {key: source[key] for key in ("document_id", "version_id", "publication_id", "title")}
    metadata.update(bibliography_revision=2, authors=[{"name": "Author"}], abstract="Summary. " * 2000)
    return candidate, metadata


def test_bibliography_survives_search_and_read_without_consuming_evidence_budget():
    candidate, metadata = document()
    page = {"candidates": [candidate], "document_metadata": [metadata, copy.deepcopy(metadata)],
            "trace": {"total_nodes": 1, "candidate_count": 1}}
    preview = search_preview(page, 2400)
    assert preview["hits"][0]["quote"]["text"] == candidate["text"]
    assert len(json.dumps(model_result(preview), ensure_ascii=False)) <= 2400
    assert len(preview["document_metadata"]) == 1
    assert preview["document_metadata"][0]["abstract_truncated"]
    read = bound_document_page(page, {}, 2400)
    assert read["candidates"][0]["text"] == candidate["text"]
    assert read["document_metadata"][0]["bibliography_revision"] == 2
    changed = {**metadata, "bibliography_revision": 3}
    assert len(merge_metadata([metadata], [metadata, changed])) == 2


async def test_cached_outline_reauthorizes_metadata_even_without_any_evidence(principal):
    _, metadata = document()
    run = {"id": str(uuid4()), "epoch": 1, "scope_hash": "scope", "evidence": {}}
    runtime = runtime_for(run)
    runtime.host_call = AsyncMock(return_value={"entries": [], "document_metadata": [metadata]})
    await cached_host(runtime, principal, run, "document_outline", {})
    runtime.host_call.side_effect = HTTPException(403, "revoked")
    with pytest.raises(HTTPException, match="revoked"):
        await cached_host(runtime, principal, run, "document_outline", {})
    assert runtime.host_call.call_args.args[1] == "authorize"




def test_shared_answer_rules_participate_in_the_existing_capability_fingerprint():
    registry = default_registry()
    original = registry.select(["core"])[0]
    assert original.resources["answer_guidelines"] == ANSWER_INSTRUCTIONS
    changed = CapabilityRegistry()
    changed.register(CapabilityPack(original.id, original.version, original.instructions, original.tools,
                                   original.initial_tools, resources={"answer_guidelines": ANSWER_INSTRUCTIONS + " changed"}))
    assert changed.pin(["core"])["core"]["sha256"] != registry.pin(["core"])["core"]["sha256"]



async def test_restored_bibliography_without_evidence_is_authorized_before_runtime_use(principal):
    _, metadata = document()
    bindings = object.__new__(JsdBindings)
    async def host(user, action, args, run_id):
        if action == "topic":
            return {}
        assert action == "authorize"
        assert args["documents"] == [{key: metadata[key] for key in ("document_id", "publication_id")}]
        raise HTTPException(403, "revoked")
    bindings.java = AsyncMock(side_effect=host)
    with pytest.raises(HTTPException, match="revoked"):
        await bindings.authorized_topic(principal, {"id": str(uuid4()), "topic_id": str(uuid4()),
                                                   "source_documents": [], "document_metadata": [metadata]}, execution=False)


def test_parenthesized_citations_normalize_only_exact_registered_identities():
    known, missing = "e" + "1" * 24, "e" + "2" * 24
    evidence = {known: {}}
    text = normalize_citations(f"A measured value (E:{known}).", evidence)
    assert text == f"A measured value [E:{known}]."
    assert validate_report(text, evidence) == [known]
    with pytest.raises(HTTPException, match="unknown evidence"):
        validate_report(normalize_citations(f"Claim [E:{known}] (E:{missing})", evidence), evidence)


def test_quote_pair_citations_only_normalize_a_quote_of_the_same_evidence():
    from aird_agent.evidence.quotes import quotes

    candidate, _ = document()
    eid = candidate["evidence_id"]
    quote = quotes(candidate)[0]
    evidence = {eid: candidate}
    text = f"A finding [E:{eid}, {quote['quote_id']}]"
    assert normalize_citations(text, evidence) == f"A finding [E:{eid}]"
    assert validate_report(text, evidence) == [eid]
    forged = f"A finding [E:{eid}, q{'0' * 24}]"
    assert normalize_citations(forged, evidence) == forged
    with pytest.raises(HTTPException, match="QUOTE_HANDLE_INVALID_OR_WRONG_EVIDENCE"):
        validate_report(forged, evidence)
    other = {**candidate, "evidence_id": "e" + "2" * 24}
    wrong_source = f"A finding [E:{other['evidence_id']}, {quote['quote_id']}]"
    with pytest.raises(HTTPException, match="QUOTE_HANDLE_INVALID_OR_WRONG_EVIDENCE"):
        validate_report(wrong_source, {**evidence, other['evidence_id']: other})
