"""Public HTTP input adapter into the same native task path as the workbench."""

from uuid import UUID

from pydantic import Field

from aird_agent.core.contracts import Contract
from aird_agent.server.contracts import RetrievalScope, RunRequest


class TaskRequest(Contract):
    canonical_conversation_id: UUID
    canonical_user_message_id: UUID
    question: str = Field(min_length=1, max_length=12000)
    retrieval_scope: RetrievalScope = Field(default_factory=RetrievalScope)


async def create_task(service, principal, request, key):
    # Only task input enters the runtime. Native messages, prompt assembly,
    # tool selection and model history remain owned by dsh.
    native = RunRequest(request=request.question, retrieval_scope=request.retrieval_scope)
    payload, _ = await service.bindings.prepare_request(principal, native)

    async def channel(conn):
        return await service.store.create(principal, "conversation", {
            **service.bindings.conversation_metadata(payload),
            "engine_version": "dsh-v1", "visibility": "HOST_MANAGED",
            "canonical_conversation_id": str(request.canonical_conversation_id),
            "title": request.question[:80], "messages": [], "notes": [],
        }, conn)

    conversation = await service.store.once(principal, "task-channel",
        str(request.canonical_conversation_id),
        {"canonical_conversation_id": str(request.canonical_conversation_id)}, channel)
    async def accept(conn):
        return {"accepted": True}
    await service.store.once(principal, "task-input", key, request.model_dump(mode="json"), accept)
    native = native.model_copy(update={"conversation_id": UUID(conversation["id"])})
    # create_run performs reauthorization and native Inbox admission; its
    # idempotency record is committed before any remote prompt dispatch.
    run = await service.create_run(principal, native, key, dispatch=False)
    async with service.store.transaction(principal, run["id"]) as conn:
        current = await service.store.get(principal, "run", run["id"], conn)
        current["canonical_conversation_id"] = str(request.canonical_conversation_id)
        current["canonical_user_message_id"] = str(request.canonical_user_message_id)
        await service.store.update(principal, "run", current, conn)
    await service.launch(principal, run["id"])
    return await service.store.get(principal, "run", run["id"])
