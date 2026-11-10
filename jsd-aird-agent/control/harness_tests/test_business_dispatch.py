"""Real PostgreSQL ledger + extracted adapter; Java/model responses are synthetic."""

import asyncio
import base64
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
            seeded = await store.create(
                principal,
                "run",
                {
                    "conversation_id": conversation_id,
                    "topic_id": host.scope["id"],
                    "request": "Read the authorized publication",
                    "profile": "agent",
                    "engine_version": "dsh-v1",
                    "dsh_session_id": "synthetic-" + str(uuid4()),
                    "dsh_turn": 1,
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
            from psycopg.types.json import Jsonb
            await conn.execute("INSERT INTO agent_runtime.harness_admission(run_id,organization_id,actor_id,principal,origin,state,epoch,lease_until) "
                "VALUES(%s,%s,%s,%s,'native','GRANTED',%s,now()+interval '10 minutes')",(seeded['id'],*principal.key,Jsonb(principal.model_dump(mode='json')),seeded['epoch']))
            return seeded

    async def native_command(actor, command, args):
        assert command == "review"
        current = await store.get(actor, "run", args["run_id"])
        await service.gate_dsh(actor, {"run_id": current["id"], "session_id": args["session_id"],
            "turn": current["dsh_turn"], "kind": "model", "call_id": str(uuid4()), "purpose": "report-review"})
        return {"text": "{}", "provider": "test-native", "model": "test-model"}

    service.dsh_command = native_command
    try:
        yield service, host, await create_run(), create_run
    finally:
        async with store.pool.connection() as conn:
            await conn.execute("UPDATE agent_runtime.harness_admission SET state='RELEASED' WHERE organization_id=%s AND actor_id=%s",principal.key)
        await service.http.aclose()


async def invoke(service, principal, run, name, args, call_id=None):
    return await service.tool(principal, run["id"], run["epoch"], call_id or str(uuid4()), name, args, native=name == "dsh_execute")


@pytest.mark.parametrize('stop_reason,receipt_status,expected', [
    (None,'SUCCEEDED','SUCCEEDED'), ('max-tokens','SUCCEEDED','FAILED'),
    (None,'CANCELLED','CANCELLED'), (None,'FAILED','FAILED'),
])
async def test_native_background_receipt_settles_after_model_response(setup, principal, store, monkeypatch, stop_reason, receipt_status, expected):
    from aird_agent.hosts.jsd import native_files

    service, host, run, _ = setup
    candidate = (await read_source(service, host, principal, run))['candidates'][0]
    entered, release = asyncio.Event(), asyncio.Event()

    async def execute(*args):
        entered.set()
        await release.wait()
        if receipt_status != 'SUCCEEDED':
            return {'error':'SANDBOX_'+receipt_status, 'receipt':{'status':receipt_status}}
        return {'exit_code':0, 'files':[], 'receipt':{'status':receipt_status}}

    monkeypatch.setattr(native_files, 'native_execute', execute)
    operation = asyncio.create_task(invoke(service, principal, run, 'dsh_execute', {'command':'work','files':[]}, 'background'))
    await asyncio.wait_for(entered.wait(), 3)
    try:
        async with store.transaction(principal, run['id']) as conn:
            current = await store.get(principal, 'run', run['id'], conn)
            current['last_text'] = '计算已结束，结果见命令输出。[E:' + candidate['evidence_id'] + ']'
            await store.update(principal, 'run', current, conn)
        request = {'run_id':run['id'], 'session_id':run['dsh_session_id'], 'turn':1, 'stop_reason':stop_reason}
        pending = await service.complete_dsh(principal, request)
        assert pending['status'] == 'RUNNING' and pending['pending_operations'] == 1
        saved = await store.get(principal, 'run', run['id'])
        assert saved['dsh_pending_operations'] == ['background'] and 'dsh_completed_turn' not in saved
    finally:
        release.set()
    assert (await asyncio.wait_for(operation, 3))['receipt']['status'] == receipt_status
    saved = await store.get(principal, 'run', run['id'])
    assert saved['status'] == expected and saved['dsh_completed_turn'] == 1
    if stop_reason:
        assert saved['error'] == 'OUTPUT_LIMIT_EXCEEDED'


@pytest.mark.parametrize('terminal_status', ['CANCELLED', 'FAILED'])
async def test_admitted_native_command_can_settle_after_run_closes_but_new_commands_cannot(setup, principal, store, monkeypatch, terminal_status):
    from aird_agent.hosts.jsd import native_files

    service, _, run, _ = setup
    entered, release = asyncio.Event(), asyncio.Event()

    async def execute(*args):
        entered.set()
        await release.wait()
        return {'exit_code':0, 'files':[], 'receipt':{'status':'SUCCEEDED'}}

    monkeypatch.setattr(native_files, 'native_execute', execute)
    operation = asyncio.create_task(invoke(service, principal, run, 'dsh_execute', {'command':'work','files':[]}, 'admitted'))
    await asyncio.wait_for(entered.wait(), 3)
    async with store.transaction(principal, run['id']) as conn:
        current = await store.get(principal, 'run', run['id'], conn)
        current.update(epoch=2, status=terminal_status)
        await store.update(principal, 'run', current, conn)
    release.set()
    assert (await asyncio.wait_for(operation, 3))['receipt']['status'] == 'SUCCEEDED'
    assert (await store.get(principal, 'run', run['id']))['status'] == terminal_status
    with pytest.raises(HTTPException, match='RUN_INACTIVE'):
        await invoke(service, principal, run, 'dsh_execute', {'command':'second','files':[]}, 'not-admitted')
    async with store.pool.connection() as conn:
        rows = await (await conn.execute('SELECT call_id,status FROM agent_runtime.harness_calls WHERE run_id=%s', (run['id'],))).fetchall()
    assert rows == [{'call_id':'admitted','status':'SUCCEEDED'}]


async def test_native_completion_projection_failure_preserves_command_receipt(setup, principal, store, monkeypatch):
    from unittest.mock import AsyncMock

    from aird_agent.hosts.jsd import native_files

    service, _, run, _ = setup
    async with store.transaction(principal, run['id']) as conn:
        current = await store.get(principal, 'run', run['id'], conn)
        current.update(dsh_pending_operations=['background'], dsh_model_completed_turn=1)
        await store.update(principal, 'run', current, conn)
    monkeypatch.setattr(native_files, 'native_execute', AsyncMock(return_value={'exit_code':0,'files':[], 'receipt':{'status':'SUCCEEDED'}}))
    monkeypatch.setattr(service, 'complete_dsh', AsyncMock(side_effect=HTTPException(403, 'ACCESS_REVOKED')))
    result = await invoke(service, principal, run, 'dsh_execute', {'command':'work','files':[]}, 'background')
    assert result['receipt']['status'] == 'SUCCEEDED'
    async with store.pool.connection() as conn:
        row = await (await conn.execute('SELECT status,result FROM agent_runtime.harness_calls WHERE run_id=%s', (run['id'],))).fetchone()
    assert row['status'] == 'SUCCEEDED' and row['result'] == result


async def test_multiple_background_receipts_keep_an_earlier_cancellation_until_the_last_job_finishes(setup, principal, store, monkeypatch):
    from aird_agent.hosts.jsd import native_files

    service, host, run, _ = setup
    candidate = (await read_source(service, host, principal, run))['candidates'][0]
    entered = {name:asyncio.Event() for name in ['first','last']}
    release = {name:asyncio.Event() for name in entered}

    async def execute(_service, _principal, _run, args, call_id):
        entered[call_id].set()
        await release[call_id].wait()
        if call_id == 'first':
            return {'error':'SANDBOX_CANCELLED','receipt':{'status':'CANCELLED'}}
        return {'exit_code':0,'files':[],'receipt':{'status':'SUCCEEDED'}}

    monkeypatch.setattr(native_files,'native_execute',execute)
    tasks = {name:asyncio.create_task(invoke(service,principal,run,'dsh_execute',{'command':'work','files':[]},name)) for name in entered}
    await asyncio.wait_for(asyncio.gather(*(event.wait() for event in entered.values())),3)
    try:
        async with store.transaction(principal,run['id']) as conn:
            current=await store.get(principal,'run',run['id'],conn)
            current['last_text']='任务已启动。[E:'+candidate['evidence_id']+']'
            await store.update(principal,'run',current,conn)
        await service.complete_dsh(principal,{'run_id':run['id'],'session_id':run['dsh_session_id'],'turn':1})
        release['first'].set()
        await asyncio.wait_for(tasks['first'],3)
        waiting=await store.get(principal,'run',run['id'])
        assert waiting['status']=='RUNNING' and waiting['dsh_pending_operations']==['first','last']
    finally:
        for event in release.values():
            event.set()
        await asyncio.wait_for(asyncio.gather(*tasks.values()),3)
    finished=await store.get(principal,'run',run['id'])
    assert finished['status']=='CANCELLED' and finished['error']=='SANDBOX_CANCELLED'


async def test_native_output_uses_original_epoch_and_enforces_owner_session_turn(setup, principal, store):
    from unittest.mock import AsyncMock

    from aird_agent.hosts.jsd.native_files import native_output

    service, host, run, _ = setup
    async with store.transaction(principal, run['id']) as conn:
        await conn.execute("INSERT INTO agent_runtime.harness_calls(run_id,call_id,request_hash,tool_name,status,execution_epoch) VALUES(%s,'command','bound','dsh_execute','RUNNING',1)", (run['id'],))
        current = await store.get(principal, 'run', run['id'], conn)
        current['epoch'] = 2
        await store.update(principal, 'run', current, conn)
    service.bindings.sandbox.output = AsyncMock(return_value={'status':'RUNNING','streams':{}})
    body = {'run_id':run['id'],'session_id':run['dsh_session_id'],'turn':1,'call_id':'command','stdout_offset':10,'stderr_offset':0}
    await native_output(service, principal, body)
    service.bindings.sandbox.output.assert_awaited_once_with(digest([run['id'],1,'command'])[:32],10,0)
    for changed in [{'session_id':'other'},{'turn':2},{'stdout_offset':-1},{'stderr_offset':True}]:
        with pytest.raises(HTTPException):
            await native_output(service, principal, {**body, **changed})
    outsider = principal.model_copy(update={'actor_id':uuid4()})
    with pytest.raises(HTTPException):
        await native_output(service, outsider, body)
    host.denied = True
    with pytest.raises(HTTPException):
        await native_output(service, principal, body)


async def read_source(service, host, principal, run):
    return await invoke(service, principal, run, "document_read", {**host.document, "refresh": True})


@pytest.mark.parametrize('reason,status,error', [
    ({'kind':'error','error':{'code':'TOKEN_BUDGET_EXCEEDED'}}, 'FAILED', 'TOKEN_BUDGET_EXCEEDED'),
    ({'kind':'aborted','reason':{'kind':'user'}}, 'CANCELLED', 'DSH_EXECUTION_INTERRUPTED'),
    ({'kind':'interrupted'}, 'FAILED', 'DSH_EXECUTION_INTERRUPTED'),
])
async def test_durable_native_end_reconciles_a_lost_failure_callback(setup, principal, store, reason, status, error):
    service, host, run, _ = setup
    async with store.transaction(principal, 'session-bind') as conn:
        conversation = await store.get(principal, 'conversation', run['conversation_id'], conn)
        conversation.update(dsh_session_id=run['dsh_session_id'], active_run=run['id'])
        await store.update(principal, 'conversation', conversation, conn)
    event = {'session_id':run['dsh_session_id'], 'event':{'type':'turn/end', 'seq':9,
        'data':{'turn':1, 'reason':reason}}}
    await service.project_event(principal, event)
    saved = await store.get(principal, 'run', run['id'])
    assert (saved['status'],saved['error'],saved['started_at']) == (status,error,None)
    revision = saved['revision']
    await service.project_event(principal, event)
    assert (await store.get(principal, 'run', run['id']))['revision'] == revision


def project_candidate(kind, resource_id, project_id, count):
    text = json.dumps({"id": resource_id, "experimentCount": count}, sort_keys=True)
    return {"text": text, "source": {"kind": "BUSINESS_RECORD", "resource_type": kind,
        "resource_id": resource_id, "project_id": project_id, "document_id": project_id,
        "version_id": "0", "source_node_key": kind + ":" + resource_id,
        "content_sha256": hashlib.sha256(text.encode()).hexdigest(), "anchor": {}, "title": "Synthetic record"}}


async def enable_business_data(service, host, run, principal, store):
    host.scope["pack_ids"].append("business-data")
    async with store.transaction(principal, run["id"]) as conn:
        run.update(pack_ids=host.scope["pack_ids"], pack_versions=service.packs.pin(host.scope["pack_ids"]))
        await store.update(principal, "run", run, conn)


async def test_unified_business_reads_register_full_proof_and_expose_only_compact_model_results(setup, principal, store):
    from aird_agent.evidence.presentation import model_result

    service, host, run, _ = setup
    await enable_business_data(service, host, run, principal, store)
    resource = str(uuid4())
    text = "隔离客户关联的真实研发数据；材料质量0 g，结果未测。"
    proof = {"text": text, "model_text": "数值和状态见rows", "ref": {"type": "CUSTOMER", "id": resource, "revision": "1"},
        "source": {"kind": "BUSINESS_RECORD", "resource_type": "CUSTOMER", "resource_id": resource,
            "document_id": resource, "version_id": "1", "source_node_key": "CUSTOMER:" + resource,
            "content_sha256": hashlib.sha256(text.encode()).hexdigest(), "title": "隔离客户",
            "anchor": {"business_adapter": "v1", "read_path": "/api/v1/agents/business/records/CUSTOMER/" + resource + "?ref=isolated"}}}
    original = host.call

    async def authority(user, action, args, run_id=None):
        if action in {"business_search", "business_read"} and not host.denied:
            return {"candidates": [copy.deepcopy(proof)], "rows": [{"mass": 0, "measured": None}]}
        return await original(user, action, args, run_id)

    host.call = authority
    names = {item["name"] for item in service.bindings.tools_for(run)}
    assert "business_search" in names and "query_structured_data" not in names
    discovered = await invoke(service, principal, run, "tool_search", {"query": "business_read"})
    assert "business_read" in discovered["activate_tools"]
    result = await invoke(service, principal, run, "business_search", {"type": "CUSTOMER"})
    candidate = result["candidates"][0]
    await invoke(service, principal, run, "business_read", {"ref": candidate["ref"]})
    current = await store.get(principal, "run", run["id"])
    assert current["evidence"][candidate["evidence_id"]]["text"] == text
    assert model_result(result)["candidates"][0]["text"] == "数值和状态见rows"
    assert result["rows"] == [{"mass": 0, "measured": None}]
    other = Principal(organization_id=uuid4(), actor_id=uuid4(), auth_version=1, username="another", role="USER")
    with pytest.raises(HTTPException):
        await store.get(other, "run", run["id"])
    host.denied = True
    with pytest.raises(HTTPException, match="TEST_ACCESS_REVOKED"):
        await invoke(service, principal, run, "business_read", {"ref": candidate["ref"]})


async def test_compute_lost_response_uses_the_native_call_receipt_without_replaying(setup, principal, store):
    from aird_agent.core.contracts import digest

    service, host, run, _ = setup
    await enable_business_data(service, host, run, principal, store)
    original = host.call
    submissions = []
    confirmed = False
    operation_key = digest([run["id"], "prediction-once"])

    async def authority(user, action, args, run_id=None):
        if action == "run_prediction":
            submissions.append(args)
            assert args["operation_key"] == operation_key
            raise httpx.ReadTimeout("Isolated lost compute submission response")
        if action == "operation_receipt":
            assert args["tool"] == "run_prediction" and args["operation_key"] == operation_key
            return {"status": "SUCCEEDED" if confirmed else "UNKNOWN", "operation_key": operation_key,
                **({"result": {"id": "isolated-compute", "kind": "PREDICTION", "executionStatus": "ACCEPTED"}} if confirmed else {})}
        return await original(user, action, args, run_id)

    host.call = authority
    result = await invoke(service, principal, run, "run_prediction", {"request": {"inputs": {"mass": 0}, "targets": []}}, "prediction-once")
    assert result["requires_reconciliation"] is True
    assert (await reconcile(service, principal, run["id"], "prediction-once"))["outcome"] == "STILL_UNKNOWN"
    with pytest.raises(HTTPException, match="TOOL_RESULT_UNKNOWN"):
        await invoke(service, principal, run, "run_prediction", {"request": {"inputs": {"mass": 1}, "targets": []}}, "prediction-again")
    confirmed = True
    assert (await reconcile(service, principal, run["id"], "prediction-once"))["outcome"] == "CONFIRMED_SUCCEEDED"
    operations = await list_operations(service, principal, run["id"])
    assert operations[0]["status"] == "SUCCEEDED"
    assert len(submissions) == 1


@pytest.mark.parametrize("denied", [False, True])
async def test_confirmed_draft_refreshes_linked_records_without_replaying_or_bypassing_revocation(setup, principal, store, denied):
    service, host, run, _ = setup
    project, stage, task, experiment, version = [str(uuid4()) for _ in range(5)]
    host.scope["pack_ids"].append("experiments")
    async with store.transaction(principal, run["id"]) as conn:
        run.update(pack_ids=host.scope["pack_ids"], pack_versions=service.packs.pin(host.scope["pack_ids"]))
        await store.update(principal, "run", run, conn)
    old = [project_candidate(kind, resource, project, 0) for kind, resource in (("STAGE", stage), ("TASK", task))]
    await service.remember_sources(principal, run, {"candidates": old})
    original = host.call
    writes = []

    async def authority(user, action, args, run_id=None):
        if host.denied:
            raise HTTPException(403, "TEST_ACCESS_REVOKED")
        if action == "create_experiment_draft":
            writes.append(args)
            return {"id": experiment, "version_id": version, "title": "Synthetic draft", "status": "DRAFT",
                    "path": "/experiments/" + experiment}
        if action in {"list_project_stages", "get_task_detail"}:
            kind, resource = ("STAGE", stage) if action == "list_project_stages" else ("TASK", task)
            return {"candidates": [project_candidate(kind, resource, project, 1)]}
        if action == "get_experiment_record":
            assert args["experiment_id"] == experiment and args["version_id"] == version
            item = project_candidate("EXPERIMENT", experiment, project, 0)
            item["source"]["version_id"] = version
            return {"candidates": [item]}
        if action == "authorize_sources" and writes:
            assert all(source["content_sha256"] not in {item["source"]["content_sha256"] for item in old}
                       for source in args["sources"])
        return await original(user, action, args, run_id)

    host.call = authority
    args = {"title": "Synthetic draft", "category_id": str(uuid4()), "planned_date": "2026-11-06",
            "template_version_id": str(uuid4()), "edit_model": {"plan": "Synthetic plan without results"},
            "project_id": project, "stage_id": stage, "task_id": task}
    result = await invoke(service, principal, run, "create_experiment_draft", args, "create-once")
    assert result["id"] == experiment
    host.denied = denied
    body = {"run_id": run["id"], "session_id": run["dsh_session_id"], "turn": 1,
            "kind": "model", "call_id": "after-create"}
    if denied:
        with pytest.raises(HTTPException, match="TEST_ACCESS_REVOKED"):
            await service.gate_dsh(principal, body)
    else:
        await service.gate_dsh(principal, body)
        current = await store.get(principal, "run", run["id"])
        assert len(current["superseded_evidence"]) == 2
        assert current["registered_outputs"][0]["context_refreshed"]
        assert set(current["superseded_evidence"]).isdisjoint(current["evidence"])
        assert current["business_context_updates"]["experiment_id"] == experiment
        await service.gate_dsh(principal, {**body, "call_id": "later"})
    assert len(writes) == 1
    async with store.pool.connection() as conn:
        call = await (await conn.execute("SELECT status FROM agent_runtime.harness_calls WHERE run_id=%s AND call_id='create-once'", (run["id"],))).fetchone()
    assert call["status"] == "SUCCEEDED"


async def test_native_search_records_receipts_and_rechecks_access_before_reuse(setup, principal):
    service, host, _, create_run = setup
    host.scope["pack_ids"].append("retrieval")
    run = await create_run()
    call = host.call

    async def search_host(actor, action, args, run_id=None):
        if action == "external_search":
            host.calls.append((action, args, run_id))
            return {"status": "SUCCEEDED", "results": [{"url": "https://example.org/paper",
                "title": "Paper", "content": "Actual search excerpt"}]}
        return await call(actor, action, args, run_id)

    host.call = search_host
    args = {"query": "UV resin", "max_results": 1}
    result = await service.tool(principal, run["id"], run["epoch"], "native-search", "dsh_web_search", args, native=True)
    assert result["sources"][0]["summary"] == "Actual search excerpt"
    assert await service.tool(principal, run["id"], run["epoch"], "native-search", "dsh_web_search", args, native=True) == result
    assert len([item for item in host.calls if item[0] == "external_search"]) == 1
    with pytest.raises(HTTPException, match="TOOL_CALL_CONFLICT"):
        await service.tool(principal, run["id"], run["epoch"], "native-search", "dsh_web_search", {**args, "query": "changed"}, native=True)
    host.denied = True
    with pytest.raises(HTTPException, match="TEST_ACCESS_REVOKED"):
        await service.tool(principal, run["id"], run["epoch"], "native-search", "dsh_web_search", args, native=True)


async def test_native_steps_retain_read_receipts_without_reinjecting_source_text(setup, principal, store):
    service, host, run, _ = setup
    await read_source(service, host, principal, run)
    sid = "session-" + str(uuid4())
    async with store.transaction(principal, run["id"]) as conn:
        current = await store.get(principal, "run", run["id"], conn)
        current.update(engine_version="dsh-v1", dsh_session_id=sid, dsh_turn=1)
        await store.update(principal, "run", current, conn)
        conversation = await store.get(principal, "conversation", run["conversation_id"], conn)
        conversation.update(profile="agent", dsh_session_id=sid, active_run=run["id"])
        await store.update(principal, "conversation", conversation, conn)
    request = {"session_id": sid, "turn": 1, "profile": "agent"}
    first = await service.register_step(principal, {**request, "request": "Read this paper"})
    assert "selected_source_reading" in json.loads(first["context"])["authorized_context"]
    await service.gate_dsh(principal, {"run_id": run["id"], **request, "kind": "model", "call_id": "native-step-1"})
    second = await service.register_step(principal, request)
    snapshot = json.loads(second["context"])
    assert "authorized_context" not in snapshot
    assert "citation_links" not in snapshot
    assert second["citation_links"]  # renderer still has the authoritative links
    assert "read_coverage" not in snapshot["task_state"]
    assert "sources" not in snapshot["task_state"]
    assert (await store.get(principal, "run", run["id"]))["read_coverage"]
    host.denied = True
    with pytest.raises(HTTPException, match="TEST_ACCESS_REVOKED"):
        await service.register_step(principal, request)


@pytest.mark.parametrize("usage_field", ["dsh_model_attempts", "dsh_usage"])
async def test_native_budget_notice_leaves_room_to_answer_without_loosening_hard_limit(setup, principal, store, usage_field):
    service, _, run, _ = setup
    sid = run["dsh_session_id"]
    body = {"session_id": sid, "turn": 1, "profile": "agent"}

    async def record_usage(usages):
        async with store.transaction(principal, run["id"]) as conn:
            current = await store.get(principal, "run", run["id"], conn)
            current[usage_field] = [{"usage": usage} for usage in usages] if usage_field == "dsh_model_attempts" else usages
            if usage_field == "dsh_usage":
                current.pop("dsh_model_attempts", None)
            await store.update(principal, "run", current, conn)
            conversation = await store.get(principal, "conversation", run["conversation_id"], conn)
            conversation.update(profile="agent", dsh_session_id=sid, active_run=run["id"])
            await store.update(principal, "conversation", conversation, conn)

    await record_usage([{"totalTokens": 100000}])
    assert "budget_notice" not in json.loads((await service.register_step(principal, body))["context"])

    await record_usage([{"totalTokens": 100000}] * 17 + [{"totalTokens": 120000}])
    context = json.loads((await service.register_step(principal, body))["context"])
    assert "已取得的证据" in context["budget_notice"]
    assert "资料缺口" in context["budget_notice"]
    await service.gate_dsh(principal, {**body, "run_id": run["id"], "kind": "model", "call_id": "final-answer"})

    await record_usage([{"totalTokens": 100000}] * 20)
    with pytest.raises(HTTPException, match="TOKEN_BUDGET_EXCEEDED"):
        await service.gate_dsh(principal, {**body, "run_id": run["id"], "kind": "model", "call_id": "over-limit"})
    assert service.settings.max_total_tokens == 2000000


async def test_native_file_registration_accepts_binary_payload_larger_than_prompt_text(setup, principal, store):
    service, host, run, _ = setup
    content = b"Native delivery verification\n" * 1024
    arguments = {"path": "work/delivery.txt", "base64": base64.b64encode(content).decode(),
                 "sha256": hashlib.sha256(content).hexdigest()}
    first = await service.tool(principal, run["id"], run["epoch"], "present-file-0", "artifact_save", arguments, native=True)
    assert "error" not in first
    again = await service.tool(principal, run["id"], run["epoch"], "present-file-0", "artifact_save", arguments, native=True)
    assert first == again
    saved = [args for action, args, _ in host.calls if action == "artifact_save"]
    assert len(saved) == 1
    assert base64.b64decode(saved[0]["base64"]) == content
    current = await store.get(principal, "run", run["id"])
    assert len(current["registered_outputs"]) == 1


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


async def test_concurrent_overlapping_reads_commit_only_one_body_and_preserve_replay(setup, principal, store):
    service, host, run, _ = setup
    results = await asyncio.gather(*(
        invoke(service, principal, run, "document_read", host.document, call_id)
        for call_id in ("overlap-a", "overlap-b")
    ))
    assert sum("text" in result["candidates"][0] for result in results) == 1
    assert sum(result["candidates"][0].get("already_retrieved", False) for result in results) == 1
    for call_id, result in zip(("overlap-a", "overlap-b"), results):
        assert await invoke(service, principal, run, "document_read", host.document, call_id) == result
    current = await store.get(principal, "run", run["id"])
    eid = results[0]["candidates"][0]["evidence_id"]
    assert current["evidence_delivery"][eid] == [[0, len(host.candidate["text"])]]
    assert current["evidence"][eid]["text"] == host.candidate["text"]
    assert current["read_coverage"][host.document["document_id"] + ":" + host.document["publication_id"]]["ranges"] == [[0, 1]]
    host.denied = True
    with pytest.raises(HTTPException, match="TEST_ACCESS_REVOKED"):
        await invoke(service, principal, run, "document_read", {**host.document, "refresh": True})


async def test_repeated_exact_quote_reads_stop_without_losing_sources_or_blocking_new_spans(setup, principal, store):
    service, host, run, _ = setup
    host.candidate["text"] = " ".join(f"Sample {i} was tested under its stated condition." for i in range(9))
    host.candidate["source"]["content_sha256"] = hashlib.sha256(host.candidate["text"].encode()).hexdigest()
    page = await read_source(service, host, principal, run)
    eid = page["candidates"][0]["evidence_id"]
    args = {"evidence_id": eid}
    first = await invoke(service, principal, run, "evidence_read", args, "quote-first")
    assert first["new_quote_count"] == 0 and first["next_quote_offset"] == 6
    assert all(span["already_retrieved"] and "text" not in span for span in first["quotes"])
    # An uncertain receipt replays normally, without advancing the stall counter.
    assert await invoke(service, principal, run, "evidence_read", args, "quote-first") == first
    for _ in range(2):
        reread = await invoke(service, principal, run, "evidence_read", args)
        assert reread["quotes"] == first["quotes"] and reread["new_quote_count"] == 0
        assert "evidence_context" in reread["next_action"]
    blocked = await invoke(service, principal, run, "evidence_read", args)
    assert blocked["error"].startswith("READ_NO_PROGRESS")
    current = await store.get(principal, "run", run["id"])
    assert current["evidence"][eid]["text"] == host.candidate["text"]
    next_page = await invoke(service, principal, run, "evidence_read", {**args, "quote_offset": 6})
    assert next_page["new_quote_count"] == 0 and next_page["next_quote_offset"] is None
    restored = await invoke(service, principal, run, "evidence_read", {**args, "refresh": True})
    assert restored["quotes"] == quotes(current["evidence"][eid])[:6]
    assert restored["new_quote_count"] == 6
    host.denied = True
    with pytest.raises(HTTPException, match="TEST_ACCESS_REVOKED"):
        await invoke(service, principal, run, "evidence_read", args)


async def test_owner_and_epoch_guards_run_before_business_dispatch(setup, principal):
    service, host, run, _ = setup
    outsider = Principal(**{**principal.model_dump(), "actor_id": uuid4()})
    with pytest.raises(HTTPException) as error:
        await read_source(service, host, outsider, run)
    assert error.value.status_code == 404
    with pytest.raises(HTTPException, match="RUN_INACTIVE"):
        await service.tool(principal, run["id"], 2, "stale", "document_read", host.document)
    assert not any(action == "document_read" for action, _, _ in host.calls)


async def test_table_read_supplies_authorized_header_without_rewriting_data_or_quote_handles(setup, principal, store):
    service, host, run, _ = setup
    host.candidate["text"] = "1 | 2H | 2.2 | H"
    host.candidate["source"]["content_sha256"] = hashlib.sha256(host.candidate["text"].encode()).hexdigest()
    host.candidate["table_context"] = {"table_key": "table-1", "row_index": 2, "header_node_keys": ["header-1"]}
    header = copy.deepcopy(host.candidate)
    header["text"] = "mol% A | Pencil hardness A | wt% B | Pencil hardness B"
    header["source"].update(source_node_key="header-1", content_sha256=hashlib.sha256(header["text"].encode()).hexdigest())
    header["table_context"]["row_index"] = 0
    original_call = host.call

    async def call(principal, action, arguments, run_id=None):
        if host.denied:
            raise HTTPException(403, "TEST_ACCESS_REVOKED")
        if action == "source_context":
            return {"candidates": [copy.deepcopy(header)], "trace": {"fulltext_coverage": False}}
        return await original_call(principal, action, arguments, run_id)

    host.call = call
    candidate = (await read_source(service, host, principal, run))["candidates"][0]
    expected = quotes(candidate)
    result = await invoke(service, principal, run, "evidence_read", {"evidence_id": candidate["evidence_id"]})
    assert result["quotes"] == expected
    assert not result["table_context"]["requires_context_read"]
    assert result["table_context"]["headers"][0]["quotes"][0]["text"] == header["text"]
    assert result["table_context"]["headers"][0]["source"]["source_node_key"] == "header-1"
    assert len((await store.get(principal, "run", run["id"]))["evidence"]) == 2
    host.denied = True
    with pytest.raises(HTTPException, match="TEST_ACCESS_REVOKED"):
        await invoke(service, principal, run, "evidence_read", {"evidence_id": candidate["evidence_id"]})


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
    assert result["findings"][0]["text"].startswith("全文未提供弹性模量")
    assert "本次已取得资料" in result["findings"][0]["message"]
    assert "Revise the exact text" in result["next_action"]
    assert (await store.get(principal, "run", run["id"]))["model_calls"] == 0


@pytest.mark.parametrize("decision", [None, "REJECTED", "APPROVED"])
@pytest.mark.parametrize("tool,args", [
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


async def test_experiment_reconciliation_closes_uncommitted_call_without_replaying(setup, principal, store):
    service, host, run, _ = setup
    host.scope["pack_ids"].append("experiments")
    async with store.transaction(principal, run["id"]) as conn:
        run["pack_ids"] = host.scope["pack_ids"]
        run["pack_versions"] = service.packs.pin(run["pack_ids"])
        run = await store.update(principal, "run", run, conn)
    args = {"title": "Synthetic draft", "category_id": str(uuid4()), "planned_date": "2026-11-06",
            "template_version_id": str(uuid4()), "edit_model": {"plan": "Synthetic plan; no results"}}
    original = host.call
    writes, receipts = [], []

    async def authority(user, action, arguments, run_id=None):
        if action == "create_experiment_draft":
            writes.append(arguments)
            raise httpx.ReadTimeout("Synthetic lost response")
        if action == "operation_receipt":
            receipts.append(arguments)
            assert arguments["close_uncommitted"] is True
            assert arguments["arguments"] == args
            return {"status": "FAILED", "operation_key": arguments["operation_key"],
                    "result": {"id": arguments["operation_key"], "error": "EXPERIMENT_DRAFT_NOT_COMMITTED"}}
        return await original(user, action, arguments, run_id)

    host.call = authority
    result = await invoke(service, principal, run, "create_experiment_draft", args, "draft-uncertain")
    assert result["requires_reconciliation"] is True
    outcome = await reconcile(service, principal, run["id"], "draft-uncertain")
    assert outcome["outcome"] == "CONFIRMED_FAILED" and outcome["replayed"] is False
    replay = await invoke(service, principal, run, "create_experiment_draft", args, "draft-uncertain")
    assert replay["error"] == "EXPERIMENT_DRAFT_NOT_COMMITTED"
    assert len(writes) == len(receipts) == 1


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

        async def review(self, prepared, *, repair=None, generate=None):
            await generate("review", [])
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

        async def review(self, prepared, *, repair=None, generate=None):
            await generate("review", [])
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
    reverted = await invoke(service, principal, run, "report_check", args)
    assert reverted["unchanged_report"] and reviewer.calls == 2
    before = (await store.get(principal, "run", run["id"]))["research_matrix"]["revision"]
    await invoke(service, principal, run, "matrix_record", matrix)
    assert (await store.get(principal, "run", run["id"]))["research_matrix"]["revision"] == before
    same = await invoke(service, principal, run, "report_check", args)
    assert same["unchanged_report"] and reviewer.calls == 2
    matrix["claims"][0]["claim"] = "A revised assertion under the same method"
    await invoke(service, principal, run, "matrix_record", matrix)
    await invoke(service, principal, run, "report_check", args)
    assert reviewer.calls == 3
    host.denied = True
    with pytest.raises(HTTPException):
        await invoke(service, principal, run, "report_check", args)


async def test_oversized_report_failure_is_durable_and_reuse_does_not_call_a_model(setup, principal, store):
    service, host, run, _ = setup
    candidate, _ = await save_matrix(service, host, principal, run)
    text = ("Modulus: 12 kPa. [E:" + candidate["evidence_id"] + "]\n\n") * 81
    args = {"title": "Test", "text": text}
    first = await invoke(service, principal, run, "report_check", args)
    repeated = await invoke(service, principal, run, "report_check", args)
    assert not first["passed"] and repeated["unchanged_report"]
    assert "81 units (limit 80)" in first["blocking_findings"][0]["reason"]
    current = await store.get(principal, "run", run["id"])
    assert current["model_calls"] == 0
    persisted = current["report_checks"][first["report_hash"]]
    assert persisted["passed"] is False
    assert persisted["blocking_findings"] == first["blocking_findings"]
    assert repeated["blocking_findings"] == persisted["blocking_findings"]
    refused = await invoke(service, principal, run, "report_save", args)
    assert refused["error"].startswith("REPORT_REVIEW_REQUIRED")

    class Reviewer:
        calls = 0

        async def review(self, prepared, *, generate=None):
            await generate("review", [])
            self.calls += 1
            return {"passed": False, "report_hash": prepared["report_hash"],
                    "matrix_revision": prepared["matrix_revision"], "quality_check_version": 3,
                    "units": [], "questions": []}

    reviewer = Reviewer()
    service.literature.report_auditor = reviewer
    await invoke(service, principal, run, "report_check", {**args, "text": text.splitlines()[0]})
    assert reviewer.calls == 1
    host.denied = True
    with pytest.raises(HTTPException):
        await invoke(service, principal, run, "report_check", args)


async def test_report_protocol_failure_keeps_safe_diagnostics_and_cannot_be_saved(setup, principal, store):
    from aird_agent.capabilities.literature.quality import ReviewOutputInvalid

    service, host, run, _ = setup
    candidate, matrix = await save_matrix(service, host, principal, run)

    class Reviewer:
        calls = 0

        async def review(self, prepared, *, repair=None, generate=None):
            await generate("review", [])
            self.calls += 1
            raise ReviewOutputInvalid("Incomplete or duplicate review coverage", {"units": [], "questions": [],
                                                                                 "raw": "do not persist this"})

    reviewer = Reviewer()
    service.literature.report_auditor = reviewer
    args = {"title": "Test", "text": "Modulus: 12 kPa. [E:" + candidate["evidence_id"] + "]"}
    first = await invoke(service, principal, run, "report_check", args)
    assert not first["passed"] and reviewer.calls == 2
    assert first["review_diagnostics"][0]["code"] == "INCOMPLETE_COVERAGE"
    assert first["review_diagnostics"][0]["missing_unit_ids"]
    current = await store.get(principal, "run", run["id"])
    persisted = current["report_checks"][first["report_hash"]]
    assert persisted["review_diagnostics"] == first["review_diagnostics"]
    assert "do not persist this" not in json.dumps(persisted)
    repeated = await invoke(service, principal, run, "report_check", args)
    assert not repeated.get("unchanged_report") and reviewer.calls == 4
    assert first["findings"] == []
    assert "Preserve the draft" in first["next_action"]
    # Technical failures are not content verdicts. An explicit new check can
    # retry the native checker, with one bounded format repair per check.
    changed = await invoke(service, principal, run, "report_check", {**args, "title": "Changed title"})
    assert not changed["passed"] and reviewer.calls == 6
    matrix["claims"][0]["claim"] = "A differently worded candidate"
    await invoke(service, principal, run, "matrix_record", matrix)
    after_matrix = await invoke(service, principal, run, "report_check", args)
    assert not after_matrix["passed"] and reviewer.calls == 8
    refused = await invoke(service, principal, run, "report_save", args)
    assert refused["error"].startswith("REPORT_REVIEW_REQUIRED")
    host.denied = True
    with pytest.raises(HTTPException):
        await invoke(service, principal, run, "report_check", {**args, "title": "Revoked"})


@pytest.mark.parametrize("failure", ["transport", "configuration"])
async def test_unavailable_reviewer_is_not_cached_as_a_content_failure(setup, principal, failure):
    service, host, run, _ = setup
    candidate, _ = await save_matrix(service, host, principal, run)

    class Reviewer:
        calls = 0

        async def review(self, prepared, **kwargs):
            self.calls += 1
            if failure == "transport":
                raise httpx.ReadTimeout("Do not expose provider details")
            raise HTTPException(503, "REPORT_REVIEW_MODEL_NOT_CONFIGURED")

    reviewer = Reviewer()
    service.literature.report_auditor = reviewer
    args = {"title": "Report", "text": "Modulus: 12 kPa. [E:" + candidate["evidence_id"] + "]"}
    first = await invoke(service, principal, run, "report_check", args)
    second = await invoke(service, principal, run, "report_check", {**args, "title": "Reworded"})
    assert not first["passed"] and not second["passed"] and reviewer.calls == 2
    assert first["findings"] == second["findings"] == []
    assert "provider details" not in json.dumps(first)


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


async def test_native_text_delivery_rejects_stale_change_note_and_registers_repaired_sources(setup, principal, store):
    from aird_agent.capabilities.files.completion import check_file_completion

    service, host, run, _ = setup
    result = await read_source(service, host, principal, run)
    eid = result["candidates"][0]["evidence_id"]
    filename = "PET基材厚度更正说明.md"
    obsolete = "q" + "9" * 24
    async with store.transaction(principal, run["id"]) as conn:
        current = await store.get(principal, "run", run["id"], conn)
        current["required_artifacts"] = [filename]
        await store.update(principal, "run", current, conn)

    async def present(text, call_id):
        data = text.encode()
        return await service.tool(principal, run["id"], run["epoch"], call_id, "artifact_save", {
            "path": filename, "base64": base64.b64encode(data).decode(),
            "sha256": hashlib.sha256(data).hexdigest(),
        }, native=True)

    failed = await present(f"# Correction\nObsolete citations: `{obsolete}`\nModulus: 12 kPa. [E:{eid}]", "bad-present")
    assert failed["error"].startswith("ANALYSIS_SOURCE_QUOTES_REQUIRED")
    assert filename + ":2 " + obsolete in failed["error"]
    assert not any(name == "artifact_save" for name, _, _ in host.calls)
    with pytest.raises(HTTPException, match="DELIVERABLE_FILES_MISSING"):
        check_file_completion(await store.get(principal, "run", run["id"]))

    result = await present(f"# Correction\nObsolete citations replaced.\nModulus: 12 kPa. [E:{eid}]", "fixed-present")
    assert result.get("id") and result["source_quotes"] and result["source_refs"]
    check_file_completion(await store.get(principal, "run", run["id"]))


async def test_native_delivery_checkpoint_keeps_hash_and_source_checks_before_final_receipt(setup, principal, store):
    from aird_agent.capabilities.files.completion import check_file_completion
    from aird_agent.core.receipts import receipt
    from aird_agent.core.workspace import Workspace
    from aird_agent.hosts.jsd.native_files import storage_path

    service, host, run, _ = setup
    result = await read_source(service, host, principal, run)
    eid = result["candidates"][0]["evidence_id"]
    name = "PET基材厚度更正说明.md"
    workspace = Workspace(service.settings.storage_root / "workspaces", str(principal.organization_id), run["id"])
    workspace.prepare()
    async with store.transaction(principal, run["id"]) as conn:
        current = await store.get(principal, "run", run["id"], conn)
        current["required_artifacts"] = [name]
        await store.update(principal, "run", current, conn)

    async def checkpoint(text, tag):
        original = workspace.write("scratch/" + tag + "/" + name, text)
        output = workspace.write(storage_path(name), text)
        execution = {
            "exit_code": 0, "artifacts": [output], "inputs": {"files": [original]},
            "receipt": {"status": "SUCCEEDED", "execution_id": tag, "request_hash": tag, "finished_at": 1},
        }
        async with store.transaction(principal, run["id"]) as conn:
            await receipt(store, principal, run["id"], tag, "tool-result", execution, conn)

    async def present(text, tag):
        data = text.encode()
        return await service.tool(principal, run["id"], run["epoch"], tag, "artifact_save", {
            "path": name, "base64": base64.b64encode(data).decode(),
            "sha256": hashlib.sha256(data).hexdigest(),
        }, native=True)

    await checkpoint("Original document", "old-bash")
    stale = f"Old citations removed: `q{'9' * 24}`\nModulus: 12 kPa. [E:{eid}]"
    mismatch = await present(stale, "first-present")
    assert mismatch["error"].startswith("ARTIFACT_EXECUTION_HASH_MISMATCH")
    await checkpoint(stale, "current-checkpoint")
    rejected = await present(stale, "checkpointed-present")
    assert rejected["error"].startswith("ANALYSIS_SOURCE_QUOTES_REQUIRED")
    assert not any(action == "artifact_save" for action, _, _ in host.calls)

    corrected = f"Old citations replaced with current evidence.\nModulus: 12 kPa. [E:{eid}]"
    await checkpoint(corrected, "corrected-checkpoint")
    saved = await present(corrected, "corrected-present")
    assert saved.get("id") and saved["source_quotes"]
    assert saved["execution_receipt"]["artifact_sha256"] == hashlib.sha256(corrected.encode()).hexdigest()
    assert saved["execution_receipt"]["execution_id"] == "corrected-checkpoint"
    check_file_completion(await store.get(principal, "run", run["id"]))


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
    import base64
    payload = {"path": filename, "base64": base64.b64encode(content.encode()).decode(), "sha256": hashlib.sha256(content.encode()).hexdigest()}
    result = await service.tool(principal, run["id"], run["epoch"], "native-file", "artifact_save", payload, native=True)
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
    from aird_agent.core.workspace import Workspace
    workspace = Workspace(service.settings.storage_root / "workspaces", str(principal.organization_id), run["id"])
    workspace.prepare()
    workspace.write("output/fake.pdf", "%PDF-1.7\n%%EOF\n")
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
            "VALUES(%s,'unknown-execution','unknown','UNKNOWN','dsh_execute')", (run["id"],),
        )
    with pytest.raises(HTTPException, match="TOOL_RESULT_UNKNOWN"):
        await invoke(service, principal, run, "artifact_save", {"path": "output/user.json"})
    assert not any(name == "artifact_save" for name, _, _ in host.calls)


async def test_native_completion_requires_a_saved_report_once_business_review_started(setup, principal, store):
    service, host, run, _ = setup
    candidate = (await read_source(service, host, principal, run))["candidates"][0]
    async with store.transaction(principal, run["id"]) as conn:
        current = await store.get(principal, "run", run["id"], conn)
        current.update(engine_version="dsh-v1", report_requested=True, dsh_session_id="native", dsh_turn=1,
            last_text=f"Report is ready [E:{candidate['evidence_id']}]")
        await store.update(principal, "run", current, conn)
    result = await service.complete_dsh(principal, {"run_id": run["id"], "session_id": "native", "turn": 1})
    assert result["status"] == "FAILED"
    assert (await store.get(principal, "run", run["id"]))["error"] == "CURRENT_MATRIX_CHECKED_REPORT_REQUIRED"


async def test_export_rejection_precedes_authority_write(setup, principal, store):
    from aird_agent.core.workspace import Workspace

    service, host, run, _ = setup
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


async def test_large_business_result_stays_readable_by_native_context_processing(setup, principal):
    service, host, run, _ = setup
    full = {"records": [{"field": "new-template-field", "text": "完整结果" * 20000}], "next_offset": 1}
    projected = service.bounded_result(principal, run, full, "large-record")
    assert projected == full
    assert "path" not in projected and "preview" not in projected


async def test_delivery_review_detects_missing_citation_before_settlement_and_preserves_saved_work(setup, principal, store):
    service, host, run, _ = setup
    candidate = (await read_source(service, host, principal, run))["candidates"][0]
    async with store.transaction(principal, run["id"]) as conn:
        current = await store.get(principal, "run", run["id"], conn)
        current.update(completion={"outcome": "COMPLETE"}, messages=[])
        await store.update(principal, "run", current, conn)
    current = await store.get(principal, "run", run["id"])
    _, valid = service.bindings.validate_output(current, "Report saved.")
    assert not valid
    text = "Report saved. [E:" + candidate["evidence_id"] + "]"
    _, valid = service.bindings.validate_output(current, text)
    assert valid
    async with store.transaction(principal, run["id"]) as conn:
        current.update(engine_version="dsh-v1", dsh_session_id="native", dsh_turn=1, last_text=text)
        await store.update(principal, "run", current, conn)
    completed = await service.complete_dsh(principal, {"run_id": run["id"], "session_id": "native", "turn": 1})
    assert completed["status"] == "SUCCEEDED"
    assert (await store.get(principal, "run", run["id"]))["completion"]["outcome"] == "COMPLETE"


async def test_invalid_final_citation_still_fails_after_delivery_review(setup, principal, store):
    service, host, run, _ = setup
    await read_source(service, host, principal, run)
    async with store.transaction(principal, run["id"]) as conn:
        current = await store.get(principal, "run", run["id"], conn)
        current.update(completion={"outcome": "COMPLETE"}, messages=[])
        await store.update(principal, "run", current, conn)
    async with store.transaction(principal, run["id"]) as conn:
        current = await store.get(principal, "run", run["id"], conn)
        current.update(engine_version="dsh-v1", dsh_session_id="native", dsh_turn=1, last_text="Report saved.")
        await store.update(principal, "run", current, conn)
    await service.complete_dsh(principal, {"run_id": run["id"], "session_id": "native", "turn": 1})
    final = await store.get(principal, "run", run["id"])
    assert final["status"] == "FAILED" and final["error"] == "CITATION_INVALID"


async def test_citation_repair_counts_distinct_reads_and_rejects_discovery_at_the_executor(setup, principal, store):
    service, host, run, _ = setup
    candidate = (await read_source(service, host, principal, run))["candidates"][0]
    async with store.transaction(principal, run["id"]) as conn:
        current = await store.get(principal, "run", run["id"], conn)
        current["last_text"] = "A measured value [E:unknown]."
        await store.update(principal, "run", current, conn)
    await service.begin_output_repair(principal, {
        "run_id": run["id"], "session_id": run["dsh_session_id"], "turn": 1})
    before = len(host.calls)
    with pytest.raises(HTTPException, match="CITATION_REPAIR_TARGETED_READ_ONLY"):
        await invoke(service, principal, run, "knowledge_search", {"query": "new paper"})
    assert not any(call[0] == "knowledge_search" for call in host.calls[before:])
    args = {"evidence_id": candidate["evidence_id"], "refresh": True}
    first = await invoke(service, principal, run, "evidence_read", args, "repair-read-1")
    assert not first.get("error")
    assert await invoke(service, principal, run, "evidence_read", args, "repair-read-1") == first
    await invoke(service, principal, run, "evidence_read", args, "repair-read-2")
    with pytest.raises(HTTPException, match="CITATION_REPAIR_READ_BUDGET_EXCEEDED"):
        await invoke(service, principal, run, "evidence_read", args, "repair-read-3")
    current = await store.get(principal, "run", run["id"])
    assert current["output_repair"]["tool_calls"] == 2


async def test_failed_script_cannot_satisfy_requested_file_delivery(setup, principal, store):
    from unittest.mock import AsyncMock

    service, host, run, _ = setup
    await read_source(service, host, principal, run)
    service.bindings.sandbox.execute = AsyncMock(
        return_value={"exit_code": 1, "error": "SANDBOX_SCRIPT_FAILED"}
    )
    result = await invoke(
        service,
        principal,
        run,
        "dsh_execute",
        {"command": "false", "files": []},
    )
    assert result["error"] == "SANDBOX_SCRIPT_FAILED"
    async with store.transaction(principal, run["id"]) as conn:
        current = await store.get(principal, "run", run["id"], conn)
        current.update(engine_version="dsh-v1", dsh_session_id="native", dsh_turn=1, last_text="Computed successfully",
                       required_artifacts=["result.xlsx"])
        await store.update(principal, "run", current, conn)
    result = await service.complete_dsh(principal, {"run_id": run["id"], "session_id": "native", "turn": 1})
    assert result["status"] == "FAILED"
    assert "DELIVERABLE_FILES_MISSING" in (await store.get(principal, "run", run["id"]))["error"]


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
    assert result.get("id") and "preview" not in result
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


async def test_native_continuation_keeps_reauthorized_evidence_without_inheriting_filters(setup, principal, store):
    from aird_agent.server.contracts import RunRequest

    service, host, run, _ = setup
    candidate = (await read_source(service, host, principal, run))["candidates"][0]
    async with store.transaction(principal, run['id']) as conn:
        previous = await store.get(principal,'run',run['id'],conn)
        previous.update(status='SUCCEEDED',dsh_session_id='native-continuation')
        await store.update(principal,'run',previous,conn)
        conversation = await store.get(principal,'conversation',run['conversation_id'],conn)
        conversation.update(profile='agent',engine_version='dsh-v1',last_run=run['id'],
            dsh_session_id='native-continuation',messages=[{'role':'user','run_id':run['id'],'text':'读这篇论文'}])
        await store.update(principal,'conversation',conversation,conn)
    # The new message has no active selection. Its native history still cites
    # this paper, and exporting those citations must retain their real identity.
    host.scope.update(documents=[],context_refs=[],retrieval_scope={})
    request = RunRequest(request='把说明做成 Word 和 PDF',profile='agent',conversation_id=conversation['id'])
    continued = await service.create_run(principal,request,'continued-evidence',dispatch=False)
    assert continued['evidence'][candidate['evidence_id']]['source']==candidate['source']
    assert continued['turn_evidence_ids']==[]
    assert continued['context_refs']==[]
    assert not continued.get('private_source_ids')
    assert not continued['retrieval_scope'].get('knowledge_documents')
    assert any(action=='authorize' and host.document in args['documents'] for action,args,_ in host.calls)
    host.denied=True
    with pytest.raises(HTTPException):
        await service.create_run(principal,request,'revoked-continuation',dispatch=False)
