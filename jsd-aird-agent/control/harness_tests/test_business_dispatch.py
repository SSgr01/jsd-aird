"""Real PostgreSQL ledger + extracted adapter; Java/model responses are synthetic."""

import copy
import hashlib
import json
import time
from uuid import uuid4

import httpx
import pytest
from fastapi import HTTPException

from aird_agent.capabilities.literature.quality import quotes, report_digest
from aird_agent.hosts.jsd.composition import Service
from aird_agent.hosts.jsd.operations import list_operations, reconcile
from aird_agent.hosts.jsd.task_state import scope_digest
from aird_agent.server.contracts import Principal, digest

pytestmark = pytest.mark.integration


class Host:
    def __init__(self):
        self.document = {"document_id": str(uuid4()), "publication_id": str(uuid4())}
        self.scope = {
            "id": str(uuid4()),
            "scope_mode": "SELECTED",
            "documents": [self.document],
            "pack_ids": ["core", "literature", "files"],
        }
        text = "The modulus is 12 kPa. The sample was cured at 25 °C."
        self.candidate = {
            "text": text,
            "source": {
                "kind": "KB_PUBLICATION",
                **self.document,
                "version_id": str(uuid4()),
                "source_node_key": "node-1",
                "content_sha256": hashlib.sha256(text.encode()).hexdigest(),
                "anchor": {"page": 1},
                "title": "Synthetic material",
            },
        }
        self.calls = []
        self.denied = False
        self.unknown_write = False
        self.retrieval_candidates = [copy.deepcopy(self.candidate)]

    async def call(self, principal, action, arguments, run_id=None):
        self.calls.append((action, copy.deepcopy(arguments), run_id))
        if self.denied:
            raise HTTPException(403, "TEST_ACCESS_REVOKED")
        if action in {"topic", "runtime_context"}:
            return copy.deepcopy(self.scope)
        if action in {"authorize", "authorize_sources"}:
            return {"authorized": True}
        if action == "document_read":
            return {"candidates": [copy.deepcopy(self.candidate)], "trace": {"total_nodes": 1}}
        if action == "retrieval_context":
            return {
                "candidates": [{**copy.deepcopy(item), "label": "K1"} for item in self.retrieval_candidates],
                "context": "Authorized evidence [evidenceRef=K1]",
                "trace": {"test": True},
            }
        if action == "note_save" and self.unknown_write:
            raise httpx.ReadTimeout("Synthetic lost response")
        return {"id": str(uuid4()), **arguments}


@pytest.fixture
async def setup(settings, store, principal):
    host = Host()
    service = Service(settings, store, host=host)

    async def create_run(conversation_id=None):
        async with store.transaction(principal, "seed") as conn:
            if conversation_id is None:
                conversation = await store.create(principal, "conversation", {"messages": []}, conn)
                conversation_id = conversation["id"]
            return await store.create(
                principal,
                "run",
                {
                    "conversation_id": conversation_id,
                    "topic_id": host.scope["id"],
                    "request": "Read the authorized publication",
                    "profile": "agent",
                    "pack_ids": host.scope["pack_ids"],
                    "pack_versions": service.packs.pin(host.scope["pack_ids"]),
                    "scope_hash": scope_digest(host.scope),
                    "source_documents": [host.document],
                    "source_refs": [],
                    "evidence": {},
                    "approvals": {},
                    "registered_outputs": [],
                    "status": "RUNNING",
                    "epoch": 1,
                    "lease_until": time.time() + 180,
                    "started_at": time.time(),
                    "active_seconds": 0,
                    "tool_calls": 0,
                    "model_calls": 0,
                    "steps": 0,
                    "messages": [],
                },
                conn,
            )

    try:
        yield service, host, await create_run(), create_run
    finally:
        await service.http.aclose()


async def invoke(service, principal, run, name, args, call_id=None):
    return await service.tool(principal, run["id"], run["epoch"], call_id or str(uuid4()), name, args)


async def read_source(service, host, principal, run):
    return await invoke(service, principal, run, "document_read", host.document)


async def save_matrix(service, host, principal, run):
    result = await read_source(service, host, principal, run)
    candidate = result["candidates"][0]
    quote = quotes(candidate)[0]
    args = {
        "question_id": "modulus",
        "question": "What is the modulus?",
        "documents": [host.document],
        "claims": [
            {
                "claim": "12 kPa",
                "quotes": [{"evidence_id": candidate["evidence_id"], "quote_id": quote["quote_id"]}],
            }
        ],
    }
    await invoke(service, principal, run, "matrix_record", args)
    return candidate, args


async def test_read_is_authorized_persisted_and_replayed_without_repeating_host_work(setup, principal, store):
    service, host, run, _ = setup
    first = await invoke(service, principal, run, "document_read", host.document, "read-1")
    again = await invoke(service, principal, run, "document_read", host.document, "read-1")
    assert first == again
    assert len([call for call in host.calls if call[0] == "document_read"]) == 1
    current = await store.get(principal, "run", run["id"])
    eid = first["candidates"][0]["evidence_id"]
    assert current["evidence"][eid]["text"] == host.candidate["text"]
    assert current["read_coverage"][host.document["document_id"] + ":" + host.document["publication_id"]][
        "ranges"
    ] == [[0, 1]]
    assert current["tool_calls"] == 1
    assert any(
        action == "authorize" and args["documents"] == [host.document] for action, args, _ in host.calls
    )


async def test_cached_results_are_not_returned_after_revocation(setup, principal):
    service, host, run, _ = setup
    await invoke(service, principal, run, "document_read", host.document, "read-1")
    host.denied = True
    with pytest.raises(HTTPException, match="TEST_ACCESS_REVOKED"):
        await invoke(service, principal, run, "document_read", host.document, "read-1")


async def test_owner_and_epoch_guards_run_before_business_dispatch(setup, principal):
    service, host, run, _ = setup
    outsider = Principal(**{**principal.model_dump(), "actor_id": uuid4()})
    with pytest.raises(HTTPException) as error:
        await read_source(service, host, outsider, run)
    assert error.value.status_code == 404
    with pytest.raises(HTTPException, match="RUN_INACTIVE_OR_LEASE_LOST"):
        await service.tool(principal, run["id"], 2, "stale", "document_read", host.document)
    assert not any(action == "document_read" for action, _, _ in host.calls)


async def test_hash_mismatch_cannot_become_authoritative_evidence(setup, principal, store):
    service, host, run, _ = setup
    host.candidate["text"] = "Changed source content"
    result = await read_source(service, host, principal, run)
    assert result["error"] == "SOURCE_HASH_MISMATCH"
    assert (await store.get(principal, "run", run["id"]))["evidence"] == {}


async def test_matrix_edit_invalidates_old_review_and_frozen_report_hash(setup, principal, store):
    service, host, run, _ = setup
    _, args = await save_matrix(service, host, principal, run)
    async with store.transaction(principal, run["id"]) as conn:
        current = await store.get(principal, "run", run["id"], conn)
        old_hash = report_digest("Report", "Old text", current["research_matrix"])
        current["report_checks"] = {old_hash: {"passed": True}}
        current["completion"] = {"outcome": "COMPLETE"}
        await store.update(principal, "run", current, conn)
    args["claims"][0]["claim"] = "12 kPa under the stated conditions"
    await invoke(service, principal, run, "matrix_record", args)
    current = await store.get(principal, "run", run["id"])
    assert current["research_matrix"]["revision"] == 2
    assert "report_checks" not in current and "completion" not in current
    assert report_digest("Report", "Old text", current["research_matrix"]) != old_hash
    result = await invoke(service, principal, run, "report_save", {"title": "Report", "text": "Old text"})
    assert result["error"].startswith("REPORT_REVIEW_REQUIRED")
    assert not any(action == "report_save" for action, _, _ in host.calls)


async def test_document_absence_prescreen_does_not_reserve_a_model_call(setup, principal, store):
    service, host, run, _ = setup
    candidate, _ = await save_matrix(service, host, principal, run)
    result = await invoke(
        service,
        principal,
        run,
        "report_check",
        {
            "title": "核查",
            "text": "全文未提供弹性模量。[E:" + candidate["evidence_id"] + "]",
        },
    )
    assert result["passed"] is False
    assert result["blocking_findings"]
    assert (await store.get(principal, "run", run["id"]))["model_calls"] == 0


@pytest.mark.parametrize("decision", [None, "REJECTED", "APPROVED"])
@pytest.mark.parametrize("tool,args", [
    ("external_search", {"query": "synthetic polymer"}),
    ("bibliography_lookup", {"query": "10.1234/synthetic"}),
    ("literature_search", {"query": "synthetic polymer"}),
    ("literature_expand", {"work_id": "W123"}),
    ("literature_acquire", {"work_id": "W123"}),
])
async def test_external_queries_require_exact_unexpired_approval(setup, principal, store, decision, tool, args):
    service, host, run, _ = setup
    if tool == "literature_acquire":
        host.scope["pack_ids"].append("private-literature")
        async with store.transaction(principal, run["id"]) as conn:
            run["pack_ids"] = host.scope["pack_ids"]
            run["pack_versions"] = service.packs.pin(host.scope["pack_ids"])
            await store.update(principal, "run", run, conn)
    key = digest({"tool": tool, "arguments": args})
    if decision:
        async with store.transaction(principal, run["id"]) as conn:
            current = await store.get(principal, "run", run["id"], conn)
            # Even an old rejection stays final; an old approval cannot authorize a new request.
            current["approvals"][key] = {"decision": decision, "expires_at": time.time() - 1}
            await store.update(principal, "run", current, conn)
    result = await invoke(service, principal, run, tool, args)
    assert not any(action == tool for action, _, _ in host.calls)
    if decision == "REJECTED":
        assert result["error"] == "EXTERNAL_QUERY_REJECTED"
    else:
        assert result["approval_required"] is True
        current = await store.get(principal, "run", run["id"])
        assert current["status"] == "WAITING_APPROVAL"
        assert current["pending_approval"]["arguments"] == args


async def test_unknown_acquisition_is_reconciled_by_inspection_without_a_second_download(setup, principal, store):
    service, host, run, _ = setup
    args = {"work_id": "W123"}
    host.scope["pack_ids"].append("private-literature")
    async with store.transaction(principal, run["id"]) as conn:
        run["pack_ids"] = host.scope["pack_ids"]
        run["pack_versions"] = service.packs.pin(run["pack_ids"])
        run["approvals"][digest({"tool": "literature_acquire", "arguments": args})] = {
            "decision": "APPROVED", "expires_at": time.time() + 900,
        }
        await store.update(principal, "run", run, conn)
    original = host.call
    downloads = []

    async def call(principal, action, arguments, run_id=None):
        if action == "literature_acquire":
            downloads.append(arguments)
            raise httpx.ReadTimeout("Synthetic lost submission response")
        if action == "literature_acquire_status":
            return {"id": "source", "conversation_id": run["conversation_id"], "work_id": "W123",
                    "kind": "EXTERNAL_FULLTEXT", "status": "PARSING"}
        return await original(principal, action, arguments, run_id)

    host.call = call
    result = await invoke(service, principal, run, "literature_acquire", args, "acquire-1")
    assert result["requires_reconciliation"] is True
    with pytest.raises(HTTPException, match="TOOL_RESULT_UNKNOWN"):
        await invoke(service, principal, run, "literature_acquire", args, "acquire-2")
    reconciled = await reconcile(service, principal, run["id"], "acquire-1")
    assert reconciled["outcome"] == "CONFIRMED_SUCCEEDED" and not reconciled["replayed"]
    status = await invoke(service, principal, run, "literature_acquire_status", args)
    assert status["status"] == "PARSING" and "activate_tools" not in status
    assert len(downloads) == 1


async def test_unknown_write_blocks_changed_arguments_and_linked_runs(setup, principal, store):
    service, host, run, create_run = setup
    host.unknown_write = True
    result = await invoke(service, principal, run, "note_save", {"text": "first note"})
    assert result["requires_reconciliation"] is True
    linked = await create_run(run["conversation_id"])
    for target in (run, linked):
        with pytest.raises(HTTPException, match="TOOL_RESULT_UNKNOWN"):
            await invoke(service, principal, target, "note_save", {"text": "changed note"})
    assert len([call for call in host.calls if call[0] == "note_save"]) == 1
    events = await store.events(principal, run["id"])
    assert [event["seq"] for event in events] == sorted(event["seq"] for event in events)


async def prepare_fixed_qa(service, host, principal, store, run):
    host.scope["pack_ids"] = ["core", "fast-qa"]
    async with store.transaction(principal, run["id"]) as conn:
        current = await store.get(principal, "run", run["id"], conn)
        current.update(
            profile="fast_qa",
            pack_ids=host.scope["pack_ids"],
            pack_versions=service.packs.pin(host.scope["pack_ids"]),
        )
        await store.update(principal, "run", current, conn)
        conversation = await store.get(principal, "conversation", run["conversation_id"], conn)
        conversation.update(
            topic_id=current["topic_id"],
            profile="fast_qa",
            pack_ids=current["pack_ids"],
            pack_versions=current["pack_versions"],
        )
        await store.update(principal, "conversation", conversation, conn)
    return await store.get(principal, "run", run["id"])


async def test_fixed_qa_recovery_reuses_authorized_retrieval_without_extra_reservation(
    setup, principal, store
):
    service, host, run, _ = setup
    run = await prepare_fixed_qa(service, host, principal, store, run)
    prepared = await service.prepare_fast_qa(principal, run)
    eid = next(iter(prepared["evidence"]))
    assert prepared["answer_context"] == "Authorized evidence [E:" + eid + "]"
    assert prepared["retrieval_trace"] == {"test": True}
    assert prepared["model_calls"] == 1
    recovered = await service.prepare_fast_qa(principal, prepared)
    assert recovered["answer_context"] == prepared["answer_context"]
    assert recovered["model_calls"] == 1
    assert len([call for call in host.calls if call[0] == "retrieval_context"]) == 1
    with pytest.raises(HTTPException, match="FAST_QA_TOOL_NOT_ALLOWED"):
        await invoke(service, principal, run, "skill_read", {})


async def test_ambiguous_fixed_qa_citations_do_not_persist_partial_evidence(setup, principal, store):
    service, host, run, _ = setup
    other = copy.deepcopy(host.candidate)
    other["source"]["source_node_key"] = "node-2"
    host.retrieval_candidates.append(other)
    run = await prepare_fixed_qa(service, host, principal, store, run)
    with pytest.raises(HTTPException, match="FAST_QA_RETRIEVAL_FAILED"):
        await service.prepare_fast_qa(principal, run)
    current = await store.get(principal, "run", run["id"])
    assert current["evidence"] == {} and "answer_context" not in current


async def test_reconciliation_requires_authority_receipt_and_never_replays_the_write(setup, principal, store):
    service, host, run, _ = setup
    host.unknown_write = True
    await invoke(service, principal, run, "note_save", {"text": "uncertain note"}, "uncertain-1")
    assert (await reconcile(service, principal, run["id"], "uncertain-1"))["outcome"] == "STILL_UNKNOWN"
    original = host.call

    async def authority(user, action, args, run_id=None):
        if action == "operation_receipt":
            return {
                "status": "SUCCEEDED",
                "operation_key": args["operation_key"],
                "result": {"id": str(uuid4())},
            }
        return await original(user, action, args, run_id)

    host.call = authority
    result = await reconcile(service, principal, run["id"], "uncertain-1")
    assert result["outcome"] == "CONFIRMED_SUCCEEDED" and result["replayed"] is False
    operation = next(
        item
        for item in await list_operations(service, principal, run["id"])
        if item["call_id"] == "uncertain-1"
    )
    assert operation["status"] == "SUCCEEDED" and operation["receipt_id"]
    assert len([call for call in host.calls if call[0] == "note_save"]) == 1
    async with store.pool.connection() as conn:
        audit = await (
            await conn.execute(
                "SELECT result FROM agent_runtime.harness_reconciliation_audit WHERE run_id=%s ORDER BY id",
                (run["id"],),
            )
        ).fetchall()
    assert [item["result"] for item in audit] == ["STILL_UNKNOWN", "CONFIRMED_SUCCEEDED"]


async def test_control_transaction_receipt_recovers_a_committed_note_without_java_or_replay(
    setup, principal, store
):
    service, host, run, _ = setup
    async with store.transaction(principal, run["id"]) as conn:
        current = await store.get(principal, "run", run["id"], conn)
        current["topic_id"] = None
        run = await store.update(principal, "run", current, conn)
    result = await invoke(
        service, principal, run, "note_save", {"text": "committed local note"}, "local-note"
    )
    async with store.transaction(principal, run["id"]) as conn:
        await conn.execute(
            "UPDATE agent_runtime.harness_calls SET status='UNKNOWN' WHERE run_id=%s AND call_id='local-note'",
            (run["id"],),
        )
    reconciled = await reconcile(service, principal, run["id"], "local-note")
    assert reconciled["outcome"] == "CONFIRMED_SUCCEEDED" and not reconciled["replayed"]
    conversation = await store.get(principal, "conversation", run["conversation_id"])
    assert conversation["notes"] == [result]
    assert not any(call[0] == "operation_receipt" for call in host.calls)


async def test_attaching_private_source_adds_only_the_new_read_pack_without_repinning_existing_tools(
    setup, principal, store
):
    from aird_agent.capabilities.literature.private_sources import Upload, upload

    service, host, run, _ = setup
    host.scope["pack_ids"].append("private-literature")
    old_ids = ["core", "files", "literature"]
    old_pins = service.packs.pin(old_ids)
    async with store.transaction(principal, run["conversation_id"]) as conn:
        conversation = await store.get(principal, "conversation", run["conversation_id"], conn)
        conversation.update(profile="agent", pack_ids=old_ids, pack_versions=old_pins)
        await store.update(principal, "conversation", conversation, conn)
    source = await upload(
        service,
        principal,
        run["conversation_id"],
        Upload(name="synthetic.md", content_type="text/markdown", base64="dGVzdA=="),
        "new-source",
    )
    conversation = await store.get(principal, "conversation", run["conversation_id"])
    assert conversation["private_source_ids"] == [source["id"]]
    assert conversation["pack_ids"] == [*old_ids, "private-literature"]
    assert {key: conversation["pack_versions"][key] for key in old_ids} == old_pins


@pytest.mark.parametrize("budget,expected_calls", [(1, 1), (2, 2)])
async def test_review_format_repair_is_bounded_and_reserves_a_second_model_call(
    setup, principal, store, budget, expected_calls
):
    from aird_agent.capabilities.literature.quality import ReviewOutputInvalid

    service, host, run, _ = setup
    candidate, _ = await save_matrix(service, host, principal, run)
    service.settings.max_model_calls = budget

    class Reviewer:
        calls = 0

        async def review(self, prepared, *, repair=None, reserve_model_call=None):
            await reserve_model_call()
            self.calls += 1
            if self.calls == 1:
                raise ReviewOutputInvalid("Incomplete review coverage", {"units": [], "questions": []})
            assert repair is not None
            return {
                "passed": False,
                "report_hash": prepared["report_hash"],
                "matrix_revision": prepared["matrix_revision"],
                "verification": "TEST_NO_PASSING_CONTENT_VERDICT",
                "quality_check_version": 3,
                "units": [],
                "questions": [],
            }

    reviewer = Reviewer()
    service.literature.report_auditor = reviewer
    result = await invoke(
        service,
        principal,
        run,
        "report_check",
        {"title": "Test", "text": "Modulus: 12 kPa. [E:" + candidate["evidence_id"] + "]"},
    )
    assert reviewer.calls == expected_calls
    current = await store.get(principal, "run", run["id"])
    assert current["model_calls"] == expected_calls
    if budget == 1:
        assert result["error"] == "BUDGET_EXCEEDED"
    else:
        assert result["passed"] is False


async def test_navigation_cache_reuses_original_body_and_rechecks_access(setup, principal, store):
    service, host, run, _ = setup
    first = await invoke(service, principal, run, "document_read", host.document)
    second = await invoke(service, principal, run, "document_read", host.document)
    assert second["trace"]["cache_hit"] is True
    assert first["candidates"][0]["evidence_id"] == second["candidates"][0]["evidence_id"]
    assert first["candidates"][0]["workspace_path"] == second["candidates"][0]["workspace_path"]
    assert sum(action == "document_read" for action, *_ in host.calls) == 1
    assert any(action == "validate_cached_evidence" for action, *_ in host.calls)
    current = await store.get(principal, "run", run["id"])

    def contains_body(value):
        if isinstance(value, dict):
            return "text" in value or any(contains_body(item) for item in value.values())
        return isinstance(value, list) and any(contains_body(item) for item in value)

    assert not contains_body(current["navigation_cache"])
    host.denied = True
    with pytest.raises(HTTPException):
        await invoke(service, principal, run, "document_read", host.document)


async def test_unchanged_report_reuses_failed_check_until_text_or_matrix_changes_and_rechecks_access(
    setup, principal, store
):
    service, host, run, _ = setup
    candidate, matrix = await save_matrix(service, host, principal, run)

    class Reviewer:
        calls = 0

        async def review(self, prepared, *, repair=None, reserve_model_call=None):
            await reserve_model_call()
            self.calls += 1
            return {"passed": False, "report_hash": prepared["report_hash"],
                    "matrix_revision": prepared["matrix_revision"], "quality_check_version": 3,
                    "verification": "TEST_UNSUPPORTED", "units": [], "questions": []}

    reviewer = Reviewer()
    service.literature.report_auditor = reviewer
    args = {"title": "Test", "text": "Modulus: 12 kPa. [E:" + candidate["evidence_id"] + "]"}
    first = await invoke(service, principal, run, "report_check", args)
    repeated = await invoke(service, principal, run, "report_check", args)
    assert not first["passed"] and not repeated["passed"]
    assert repeated["unchanged_report"] and reviewer.calls == 1
    assert (await store.get(principal, "run", run["id"]))["model_calls"] == 1
    await invoke(service, principal, run, "report_check", {**args, "title": "Revised"})
    assert reviewer.calls == 2
    await invoke(service, principal, run, "matrix_record", matrix)
    await invoke(service, principal, run, "report_check", args)
    assert reviewer.calls == 3
    host.denied = True
    with pytest.raises(HTTPException):
        await invoke(service, principal, run, "report_check", args)


async def test_artifact_lineage_matches_immutable_execution_output_hash(setup, principal, store):
    from aird_agent.core.receipts import receipt
    from aird_agent.core.workspace import Workspace

    service, host, run, _ = setup
    workspace = Workspace(
        service.settings.storage_root / "workspaces", str(principal.organization_id), run["id"]
    )
    workspace.prepare()
    output = workspace.write("output/result.json", '{"derived": 15}')
    execution = {
        "exit_code": 0,
        "artifacts": [output],
        "receipt": {"status": "SUCCEEDED", "execution_id": "x", "request_hash": "hash", "finished_at": 1},
        "inputs": {"file_count": 1},
    }
    async with store.transaction(principal, run["id"]) as conn:
        await receipt(store, principal, run["id"], "sandbox-call", "tool-result", execution, conn)
    result = await invoke(service, principal, run, "artifact_save", {"path": "output/result.json"})
    assert result["execution_receipt"]["artifact_sha256"] == output["sha256"]
    workspace.write("output/result.json", '{"modified": true}')
    changed = await invoke(service, principal, run, "artifact_save", {"path": "output/result.json"})
    assert changed["error"].startswith("ARTIFACT_EXECUTION_HASH_MISMATCH")


@pytest.mark.parametrize("filename,content", [
    ("note.txt", "User supplied summary"),
    ("result.csv", "name,value\nwater,0.81\n"),
    ("result.json", '{"water": 0.81}'),
    ("report.md", "# Project summary\nNot a literature report."),
])
async def test_core_files_register_without_topic_or_literature(setup, principal, store, filename, content):
    service, host, run, _ = setup
    async with store.transaction(principal, run["id"]) as conn:
        current = await store.get(principal, "run", run["id"], conn)
        current.pop("topic_id", None)
        current["pack_ids"] = ["core", "files"]
        current["pack_versions"] = service.packs.pin(current["pack_ids"])
        await store.update(principal, "run", current, conn)
    path = "output/" + filename
    await invoke(service, principal, run, "workspace_write", {"path": path, "content": content})
    result = await invoke(service, principal, run, "artifact_save", {"path": path})
    assert result["name"] == filename and not result.get("topic_id")
    assert not result["source_quotes"] and not result["source_refs"]
    assert "execution_receipt" not in result
    assert not any(name in {"report_save", "research_topic_ensure"} for name, _, _ in host.calls)


async def test_unrelated_read_does_not_force_citations_on_computation(setup, principal, store):
    from aird_agent.core.receipts import receipt
    from aird_agent.core.workspace import Workspace

    service, host, run, _ = setup
    await read_source(service, host, principal, run)
    workspace = Workspace(service.settings.storage_root / "workspaces", str(principal.organization_id), run["id"])
    workspace.prepare()
    output = workspace.write("output/user.csv", "value\n42\n")
    execution = {
        "exit_code": 0, "artifacts": [output], "inputs": {"quote_ids": [], "files": []},
        "receipt": {"status": "SUCCEEDED", "execution_id": "x", "request_hash": "hash", "finished_at": 1},
    }
    async with store.transaction(principal, run["id"]) as conn:
        await receipt(store, principal, run["id"], "user-computation", "tool-result", execution, conn)
    result = await invoke(service, principal, run, "artifact_save", {"path": "output/user.csv"})
    assert result["id"] and result["execution_receipt"]
    assert not result["source_refs"] and not result["source_quotes"]


async def test_pdf_header_cannot_bypass_real_sandbox_validation(setup, principal):
    service, host, run, _ = setup
    await invoke(service, principal, run, "workspace_write", {
        "path": "output/fake.pdf", "content": "%PDF-1.7\n%%EOF\n",
    })
    result = await invoke(service, principal, run, "artifact_save", {"path": "output/fake.pdf"})
    assert result["error"].startswith("ARTIFACT_FORMAT_REQUIRES_SANDBOX")
    assert not any(name == "artifact_save" for name, _, _ in host.calls)


async def test_changed_inputs_and_unknown_executions_block_registration(setup, principal, store):
    from aird_agent.core.receipts import receipt
    from aird_agent.core.workspace import Workspace

    service, host, run, _ = setup
    workspace = Workspace(service.settings.storage_root / "workspaces", str(principal.organization_id), run["id"])
    workspace.prepare()
    selected = workspace.write("scratch/user.json", '{"value": 1}')
    output = workspace.write("output/user.json", '{"value": 2}')
    execution = {
        "exit_code": 0, "artifacts": [output], "inputs": {"files": [selected]},
        "receipt": {"status": "SUCCEEDED", "execution_id": "x", "request_hash": "hash", "finished_at": 1},
    }
    async with store.transaction(principal, run["id"]) as conn:
        await receipt(store, principal, run["id"], "selected-input", "tool-result", execution, conn)
    workspace.write("scratch/user.json", '{"value": 3}')
    result = await invoke(service, principal, run, "artifact_save", {"path": "output/user.json"})
    assert result["error"].startswith("ARTIFACT_INPUT_CHANGED")
    async with store.transaction(principal, run["id"]) as conn:
        await conn.execute(
            "INSERT INTO agent_runtime.harness_calls(run_id,call_id,request_hash,status,tool_name) "
            "VALUES(%s,'unknown-execution','unknown','UNKNOWN','sandbox_execute')", (run["id"],),
        )
    with pytest.raises(HTTPException, match="TOOL_RESULT_UNKNOWN"):
        await invoke(service, principal, run, "artifact_save", {"path": "output/user.json"})
    assert not any(name == "artifact_save" for name, _, _ in host.calls)


async def test_plan_updates_keep_undelivered_files_and_export_rejection_precedes_authority_write(setup, principal, store):
    from aird_agent.core.workspace import Workspace

    service, host, run, _ = setup
    plan = {
        "goal": "Export results", "summary": "Deliver both formats", "task_type": "GENERAL",
        "presentation": "research_plan_v1",
        "deliverables": ["analysis_file"], "file_names": ["result.csv", "result.json"],
        "steps": [{"id": "export", "title": "Export", "status": "in_progress"}],
    }
    first = await invoke(service, principal, run, "plan_update", plan)
    assert first["saved"] and "presentation" not in first
    await invoke(service, principal, run, "plan_update", {**plan, "file_names": ["result.json"]})
    current = await store.get(principal, "run", run["id"])
    assert current["required_artifacts"] == ["result.csv", "result.json"]
    assert "presentation" not in current["plan"]
    candidate = (await read_source(service, host, principal, run))["candidates"][0]
    ref = {"evidence_id": candidate["evidence_id"], "quote_id": quotes(candidate)[0]["quote_id"]}
    workspace = Workspace(service.settings.storage_root / "workspaces", str(principal.organization_id), run["id"])
    workspace.prepare()
    workspace.write("output/result.csv", "value,unit\n15,kPa\n")
    rejected = await invoke(service, principal, run, "artifact_save", {"path": "output/result.csv", "source_quotes": [ref]})
    assert rejected["error"].startswith("EXPORT_CITATIONS_MISSING")
    assert not any(name == "artifact_save" for name, _, _ in host.calls)
    workspace.write("output/result.csv", f"value,unit,evidence_id,quote_id\n15,kPa,{ref['evidence_id']},{ref['quote_id']}\n")
    accepted = await invoke(service, principal, run, "artifact_save", {"path": "output/result.csv", "source_quotes": [ref]})
    assert accepted["id"]
    current = await store.get(principal, "run", run["id"])
    assert current["registered_outputs"][-1]["path"] == "output/result.csv"


async def test_report_projection_keeps_failure_details_without_mutating_full_audit(setup, principal, store):
    service, host, run, _ = setup
    full = {
        "report_hash": "report", "quality_check_version": 3, "passed": False,
        "units": [
            {"unit_id": "u1", "verdict": "SUPPORTED", "quote_ids": ["q1"], "reason": "Supported"},
            {"unit_id": "u2", "verdict": "INSUFFICIENT", "quote_ids": [], "reason": "Purpose not stated"},
        ],
        "findings": [{"unit_id": "u2", "text": "Waiting for thermal equilibration", "reason": "Purpose not stated"}],
        "questions": [{"question_id": "method", "verdict": "MISSING", "reason": "Not answered"}],
    }
    projected = service.bounded_result(principal, run, full, "review")
    assert projected["checked_unit_count"] == 2
    assert [unit["unit_id"] for unit in projected["units"]] == ["u2"]
    assert projected["findings"] == full["findings"] and projected["questions"] == full["questions"]
    assert len(full["units"]) == 2


async def test_delivery_review_detects_missing_citation_before_settlement_and_preserves_saved_work(setup, principal, store):
    service, host, run, _ = setup
    candidate = (await read_source(service, host, principal, run))["candidates"][0]
    async with store.transaction(principal, run["id"]) as conn:
        current = await store.get(principal, "run", run["id"], conn)
        current.update(completion={"outcome": "COMPLETE"}, messages=[], checkpoint_message_seq=0)
        await store.update(principal, "run", current, conn)
    denied = await service.callback(principal, run["id"], "review", {"epoch": 1, "text": "Report saved."})
    assert not denied["ready"] and denied["repair_output"]
    accepted = await service.callback(principal, run["id"], "review", {
        "epoch": 1, "text": "Report saved. [E:" + candidate["evidence_id"] + "]"
    })
    assert accepted["ready"] and not accepted["repair_output"]
    assert (await store.get(principal, "run", run["id"]))["completion"]["outcome"] == "COMPLETE"


async def test_invalid_final_citation_still_fails_after_delivery_review(setup, principal, store):
    service, host, run, _ = setup
    await read_source(service, host, principal, run)
    async with store.transaction(principal, run["id"]) as conn:
        current = await store.get(principal, "run", run["id"], conn)
        current.update(completion={"outcome": "COMPLETE"}, messages=[], checkpoint_message_seq=0, checkpoint=None)
        await store.update(principal, "run", current, conn)
    review = await service.callback(principal, run["id"], "review", {"epoch": 1, "text": "Report saved."})
    assert review["repair_output"]
    await service.callback(principal, run["id"], "settled", {"epoch": 1, "text": "Report saved."})
    final = await store.get(principal, "run", run["id"])
    assert final["status"] == "FAILED" and final["error"] == "CITATION_INVALID"


async def test_failed_script_cannot_be_claimed_complete(setup, principal, store):
    from unittest.mock import AsyncMock

    service, host, run, _ = setup
    service.bindings.sandbox.execute = AsyncMock(
        return_value={"exit_code": 1, "error": "SANDBOX_SCRIPT_FAILED"}
    )
    result = await invoke(
        service,
        principal,
        run,
        "sandbox_execute",
        {"script": "raise RuntimeError()", "language": "python", "input_paths": []},
    )
    assert result["error"] == "SANDBOX_SCRIPT_FAILED"
    async with store.transaction(principal, run["id"]) as conn:
        current = await store.get(principal, "run", run["id"], conn)
        current["plan"] = {"task_type": "GENERAL", "steps": [], "deliverables": ["answer"]}
        await store.update(principal, "run", current, conn)
    completion = await invoke(
        service,
        principal,
        run,
        "finish_task",
        {
            "outcome": "COMPLETE",
            "summary": "Done",
            "checks": [{"requirement": "compute", "status": "covered"}],
            "gaps": [],
        },
    )
    assert completion["error"].startswith("SANDBOX_EXECUTION_NOT_SUCCEEDED")


async def test_durable_side_effect_receipt_keeps_authority_before_model_projection(setup, principal, store):
    import base64

    from aird_agent.core.workspace import Workspace

    service, host, run, _ = setup
    workspace = Workspace(
        service.settings.storage_root / "workspaces", str(principal.organization_id), run["id"]
    )
    workspace.prepare()
    workspace.write("output/large.txt", "x" * 20000)
    result = await invoke(
        service, principal, run, "artifact_save", {"path": "output/large.txt"}, "large-artifact"
    )
    assert result["truncated"] is True and result.get("id")
    async with store.pool.connection() as conn:
        saved = await (
            await conn.execute(
                "SELECT payload FROM agent_runtime.harness_receipts WHERE run_id=%s AND call_id='large-artifact'",
                (run["id"],),
            )
        ).fetchone()
    assert saved["payload"]["id"] == result["id"]
    assert base64.b64decode(saved["payload"]["base64"]) == b"x" * 20000


async def test_artifact_freezes_only_registered_quote_handles_from_execution_inputs(setup, principal, store):
    from aird_agent.core.receipts import receipt
    from aird_agent.core.workspace import Workspace

    service, host, run, _ = setup
    candidate = (await read_source(service, host, principal, run))["candidates"][0]
    quote = quotes(candidate)[0]
    workspace = Workspace(
        service.settings.storage_root / "workspaces", str(principal.organization_id), run["id"]
    )
    workspace.prepare()
    output = workspace.write(
        "output/provenance.json", json.dumps({"derived": 15, "source_quotes": [
            {"evidence_id": candidate["evidence_id"], "quote_id": quote["quote_id"]}
        ]})
    )
    execution = {
        "exit_code": 0,
        "artifacts": [output],
        "receipt": {"status": "SUCCEEDED", "execution_id": "x", "request_hash": "hash", "finished_at": 1},
        "inputs": {"quote_ids": [quote["quote_id"]], "files": []},
    }
    async with store.transaction(principal, run["id"]) as conn:
        await receipt(store, principal, run["id"], "source-execution", "tool-result", execution, conn)
    result = await invoke(service, principal, run, "artifact_save", {"path": "output/provenance.json"})
    assert result["source_quotes"][0]["quote_id"] == quote["quote_id"]
    assert result["source_quotes"][0]["evidence_id"] == candidate["evidence_id"]
    rejected = await invoke(
        service,
        principal,
        run,
        "artifact_save",
        {
            "path": "output/provenance.json",
            "source_quotes": [{"quote_id": quote["quote_id"], "evidence_id": "e" + "a" * 24}],
        },
    )
    assert rejected["error"] == "QUOTE_HANDLE_INVALID_OR_WRONG_EVIDENCE"
