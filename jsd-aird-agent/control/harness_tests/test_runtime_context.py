from contextlib import asynccontextmanager
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from aird_agent.core.business_service import BusinessService
from aird_agent.evidence.navigation import search_preview
from aird_agent.hosts.jsd.bindings import JsdBindings


def test_reading_does_not_reinject_growing_catalogs_into_every_native_step():
    bindings = JsdBindings()
    run = {"source_documents": [{"document_id": "paper", "publication_id": "fixed"}],
           "evidence": {}, "read_coverage": {"paper:fixed": {"total_nodes": 180, "ranges": [[0, 20]]}}}
    first = bindings.gate_state(run)
    run["read_coverage"]["paper:fixed"]["ranges"] = [[0, 80]]
    assert bindings.gate_state(run) == first == {}
    run["registered_outputs"] = [{"kind": "experiment_draft", "id": "created-once"}]
    assert bindings.gate_state(run) == {"registered_outputs": run["registered_outputs"]}
    run["research_requirements"] = {"fulltext_documents": run["source_documents"]}
    assert bindings.gate_state(run)["read_coverage"]["paper:fixed"]["read_nodes"] == 80


def test_search_preview_preserves_partial_coverage_even_when_no_hits_were_delivered():
    result = search_preview({"candidates": [], "retrieval_status": "PARTIAL",
        "retrieval_notice": "向量检索未完成，覆盖不完整。", "trace": {"candidate_count": 0}}, 5000)
    assert result["hits"] == []
    assert result["retrieval_status"] == "PARTIAL"
    assert result["retrieval_notice"] == "向量检索未完成，覆盖不完整。"


@pytest.mark.parametrize('packs', [True, False])
async def test_register_reuses_authorized_runtime_context_without_caching_across_requests(packs):
    run = {'id':'run', 'status':'RUNNING', 'dsh_turn':1, 'epoch':1, 'started_at':1}
    if packs:
        run['pack_versions'] = {'business':'v1'}
    topic = {'id':'scope', 'title':'Current authorized sources'}

    @asynccontextmanager
    async def transaction(*_args):
        yield object()

    service = SimpleNamespace(
        session_conversation=AsyncMock(return_value={'profile':'agent','active_run':'run'}),
        authorize_conversation=AsyncMock(), authorize_history=AsyncMock(),
        store=SimpleNamespace(get=AsyncMock(return_value=run), transaction=transaction),
        admission=SimpleNamespace(state=AsyncMock(return_value={'admission':'GRANTED'}), require=AsyncMock()),
        bindings=SimpleNamespace(prepare_run=AsyncMock(return_value=run), gate_state=lambda _: {},
            evidence_summaries=lambda _: [], runtime_topic=AsyncMock(return_value=topic), initial_tools=lambda _: []),
        authorized_topic=AsyncMock(return_value=topic), host_call=AsyncMock(return_value=topic),
        context_request=lambda _: {}, tools_for=lambda _: [], instructions_for=lambda _: '',
        settings=SimpleNamespace(max_total_tokens=100000,model_output_tokens=8192,public_url='https://example.test'),
    )
    body={'session_id':'session', 'turn':1, 'profile':'agent', 'binding_protocol':2}
    for _ in range(2):
        result=await BusinessService.register_step(service,object(),body)
        assert result['context']
    assert service.authorized_topic.await_count == 2  # Each request still rechecks authority.
    assert service.host_call.await_count == (0 if packs else 2)
    assert all(call.args[2] is topic for call in service.bindings.runtime_topic.await_args_list)

    service.authorized_topic.side_effect=PermissionError('access revoked')
    with pytest.raises(PermissionError, match='access revoked'):
        await BusinessService.register_step(service,object(),body)
    assert service.bindings.runtime_topic.await_count == 2
