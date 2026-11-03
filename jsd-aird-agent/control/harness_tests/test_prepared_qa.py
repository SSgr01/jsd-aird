import hashlib
import time
from uuid import uuid4

import pytest
from fastapi import HTTPException
from pydantic import ValidationError

from aird_agent.capabilities.fast_qa.generation import PreparedQa, create, validate_answer
from aird_agent.hosts.jsd.composition import Service
from aird_agent.server.api import public_run


@pytest.fixture
def prepared_request():
    return PreparedQa(
        canonical_conversation_id=str(uuid4()),
        canonical_user_message_id=str(uuid4()),
        question="What is the test value?",
        prompt="Synthetic value is 12 kPa. [evidenceRef=K1]",
        instructions="Answer from the authorized evidence using [[K1]].",
        allowed_refs=["K1"],
        candidates=[
            {
                "label": "K1",
                "text": "Synthetic value is 12 kPa.",
                "source": {
                    "kind": "KB_PUBLICATION",
                    "document_id": str(uuid4()),
                    "version_id": str(uuid4()),
                    "publication_id": str(uuid4()),
                    "source_node_key": "node-1",
                    "anchor": {},
                    "title": "Synthetic",
                    "content_sha256": hashlib.sha256(b"Synthetic value is 12 kPa.").hexdigest(),
                },
            }
        ],
    )


def test_prepared_references_cannot_exceed_registered_sources(prepared_request):
    payload = prepared_request.model_dump()
    payload["allowed_refs"] = ["K2"]
    with pytest.raises(ValidationError):
        PreparedQa(**payload)
    validate_answer("12 kPa [[K1]]", prepared_request.model_dump())
    with pytest.raises(HTTPException, match="UNKNOWN_CITATION"):
        validate_answer("12 MPa [[K2]]", prepared_request.model_dump())


@pytest.mark.integration
async def test_prepared_generation_is_idempotent_private_and_preserves_host_history_contract(
    settings, store, principal, prepared_request
):
    class Host:
        async def call(self, user, action, args, run_id=None):
            if action == "runtime_context":
                return {
                    "pack_ids": ["core", "fast-qa"],
                    "documents": [],
                    "context_refs": [],
                    "scope_mode": "AUTOMATIC",
                    "retrieval_scope": prepared_request.retrieval_scope.model_dump(mode="json"),
                }
            return {}

    service = Service(settings, store, host=Host())
    try:
        created = await create(service, principal, prepared_request, "canonical-turn")
        assert await create(service, principal, prepared_request, "canonical-turn") == created
        run = await store.get(principal, "run", created["id"])
        assert len(run["evidence"]) == 1
        assert run["evidence_aliases"]["K1"] in run["evidence"]
        assert run["tool_calls"] == 0
        assert "prepared_qa" not in public_run(run)
        conversation = await store.get(principal, "conversation", run["conversation_id"])
        assert conversation["visibility"] == "HOST_MANAGED"
        async with store.transaction(principal, run["id"]) as conn:
            run.update(status="RUNNING", epoch=1, started_at=time.time(), lease_until=time.time() + 180)
            await store.update(principal, "run", run, conn)
        run = await service.prepare_fast_qa(principal, await store.get(principal, "run", run["id"]))
        assert run["model_calls"] == 1 and run["evidence"]
        assert run["tool_calls"] == 0
        assert "[[K1]]" in service.instructions_for(run)
        await service.callback(principal, run["id"], "settled", {"epoch": 1, "text": "12 kPa [[K1]]"})
        final = await store.get(principal, "run", run["id"])
        assert final["status"] == "SUCCEEDED" and final["result"] == "12 kPa [[K1]]"
        assert final["prepared_qa"]["canonical_user_message_id"] == prepared_request.canonical_user_message_id
    finally:
        await service.http.aclose()
