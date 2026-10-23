"""Use LangChain trimming; do not invent another conversation memory engine."""

from langchain_core.messages import trim_messages


def recent_context(messages, max_tokens=4000, *, pack_ids=None):
    if pack_ids is not None:
        allowed = set(pack_ids)
        messages = [
            m for m in messages if set(m.additional_kwargs.get("pack_ids", ["project"])).issubset(allowed)
        ]
    # Approximate deterministic counter also works without provider tokenization credentials.
    selected = trim_messages(
        messages,
        max_tokens=max_tokens,
        strategy="last",
        token_counter=lambda xs: sum(max(1, len(str(x.content)) // 2) for x in xs),
        start_on="human",
        include_system=True,
        allow_partial=False,
    )
    return [{"role": m.type, "content": m.content} for m in selected]


def summarization_middleware(model, *, trigger_tokens=8000, keep_messages=12):
    """Optional LangChain Agent adapter; persistence remains the shared LangGraph checkpointer."""
    from langchain.agents.middleware import SummarizationMiddleware

    return SummarizationMiddleware(
        model=model, trigger=("tokens", trigger_tokens), keep=("messages", keep_messages)
    )
