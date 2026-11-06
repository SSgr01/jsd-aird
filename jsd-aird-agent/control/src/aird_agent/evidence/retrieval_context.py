"""Retrieval-only history and evidence-label projection; native dsh owns model context."""

import json
import re

from fastapi import HTTPException

from aird_agent.evidence.metadata import metadata_preview


def answer_context(result, max_characters):
    context = result.get("context", "")
    if not isinstance(context, str) or len(context) > max_characters:
        raise HTTPException(502, "INVALID_ANSWER_CONTEXT")
    labels = {}
    for candidate in result.get("candidates", []):
        label = candidate.get("label")
        if isinstance(label, str) and re.fullmatch(r"[KDEW][0-9]+", label):
            eid = candidate["evidence_id"]
            if label in labels and labels[label] != eid:
                raise HTTPException(502, "AMBIGUOUS_ANSWER_CONTEXT_LABEL")
            labels[label] = eid
    context = re.sub(
        r"\[evidenceRef=([KDEW][0-9]+)\]",
        lambda match: "[E:" + labels[match[1]] + "]" if match[1] in labels else match[0],
        context,
    )
    metadata = metadata_preview(result.get("document_metadata", []), max_characters - len(context) - 128)
    if metadata:
        context += "\n\ndocument_metadata（书目数据，摘要不是全文实验依据）：\n" + json.dumps(metadata, ensure_ascii=False)
    return context


def qa_history(conversation, run):
    # Queued future questions may already be in the conversation. Neither
    # those nor the current question belong in query rewriting/history.
    prior_runs = []
    for message in conversation.get("messages", []):
        if message.get("run_id") == run["id"] and message["role"] == "user":
            break
        if (
            message["role"] == "user"
            and message.get("run_id")
            and message.get("status") not in {"QUEUED", "PENDING"}
        ):
            prior_runs.append(message["run_id"])
    # Queued user messages may precede the prior answer in append order.
    # Reconstruct completed turn order by run instead of append timestamp.
    history = [
        {"role": message["role"], "content": message["text"][:4000]}
        for rid in dict.fromkeys(prior_runs)
        for message in conversation.get("messages", [])
        if message.get("run_id") == rid
        and message["role"] in {"user", "assistant"}
        and message.get("status") not in {"QUEUED", "PENDING"}
    ]
    return history[-8:]
