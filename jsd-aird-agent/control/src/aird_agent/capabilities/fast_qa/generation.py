"""Trusted-host fixed generation; canonical conversation history stays with the host."""

from fastapi import HTTPException
from pydantic import Field, model_validator

from aird_agent.core.contracts import Contract
from aird_agent.core.state import initial_run_state
from aird_agent.evidence.models import SourceRef
from aird_agent.evidence.registry import alias_map, canonical_candidate, register_candidates
from aird_agent.hosts.jsd.task_state import scope_digest
from aird_agent.server.contracts import RetrievalScope, RunRequest


class PreparedCandidate(Contract):
    label: str = Field(pattern=r"^[KDE][0-9]+$")
    text: str = Field(max_length=100000)
    source: SourceRef
    evidence_id: str | None = None

    @model_validator(mode="after")
    def identity(self):
        self.evidence_id = canonical_candidate(self.model_dump(mode="json"))["evidence_id"]
        return self


class PreparedQa(Contract):
    canonical_conversation_id: str = Field(min_length=1, max_length=100)
    canonical_user_message_id: str = Field(min_length=1, max_length=100)
    question: str = Field(min_length=1, max_length=12000)
    prompt: str = Field(min_length=1, max_length=180000)
    instructions: str = Field(min_length=1, max_length=64000)
    candidates: list[PreparedCandidate] = Field(default_factory=list, max_length=100)
    allowed_refs: list[str] = Field(default_factory=list, max_length=100)
    retrieval_scope: RetrievalScope = Field(default_factory=RetrievalScope)

    @model_validator(mode="after")
    def references(self):
        labels = [item.label for item in self.candidates]
        if len(labels) != len(set(labels)) or set(self.allowed_refs) != set(labels):
            raise ValueError("Prepared evidence references must exactly match unique source labels")
        if sum(len(item.text) for item in self.candidates) > 2_000_000:
            raise ValueError("Prepared evidence budget exceeded")
        return self


def validate_answer(text, prepared):
    from aird_agent.evidence.citations import validate_generated

    return validate_generated(
        text, register_candidates({}, prepared.get("candidates", [])), prepared["allowed_refs"]
    )


async def create(service, principal, request, key):
    """Only the host-only endpoint exposes this; browser run requests forbid these fields."""
    payload = request.model_dump(mode="json")
    payload["aliases"] = alias_map(payload["candidates"])
    # Resolve the host's current account/scope rather than trusting source declarations.
    context = await service.java(
        principal,
        "runtime_context",
        {
            "profile": "fast_qa",
            "context_refs": [],
            "retrieval_scope": payload["retrieval_scope"],
        },
    )
    packs = ["core", "fast-qa"]
    if set(packs) - set(context.get("pack_ids", [])):
        raise HTTPException(403, "CAPABILITY_NOT_AUTHORIZED")
    refs = [item["source"] for item in payload["candidates"]]
    await service.java(principal, "authorize_sources", {"sources": refs})

    async def save(conn):
        conversation = await service.store.create(
            principal,
            "conversation",
            {
                "profile": "fast_qa",
                "pack_ids": packs,
                "pack_versions": service.packs.pin(packs),
                "topic_id": None,
                "context_refs": [],
                "retrieval_scope": payload["retrieval_scope"],
                "title": request.question[:80],
                "visibility": "HOST_MANAGED",
                "messages": [],
                "notes": [],
                "queued": [],
                "checkpoint": None,
            },
            conn,
        )
        ordinary = RunRequest(
            request=request.question, profile="fast_qa", retrieval_scope=request.retrieval_scope
        )
        run = await service.store.create(
            principal,
            "run",
            {
                **ordinary.model_dump(mode="json"),
                "conversation_id": conversation["id"],
                "pack_ids": packs,
                "pack_versions": service.packs.pin(packs),
                "prepared_qa": payload,
                **initial_run_state(principal),
                "evidence": register_candidates({}, payload["candidates"]),
                "evidence_aliases": payload["aliases"],
                "checkpoint": None,
                "scope_hash": scope_digest(context),
                "source_documents": [],
                "source_refs": refs,
            },
            conn,
        )
        conversation["active_run"] = run["id"]
        conversation["scope_hash"] = run["scope_hash"]
        await service.store.update(principal, "conversation", conversation, conn)
        await service.store.event(
            principal, run["id"], "run.queued", {"conversationId": conversation["id"]}, conn
        )
        return {"id": run["id"], "conversation_id": conversation["id"], "status": run["status"]}

    return await service.store.once(principal, "prepared-qa", key, payload, save)
