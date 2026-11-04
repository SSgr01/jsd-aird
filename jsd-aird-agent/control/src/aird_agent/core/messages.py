"""Keep unconsumed steering ahead of later follow-up work."""

from uuid import uuid4


def requeue_unconsumed(run, conversation):
    pending = [
        {**message, "status": "QUEUED", "delivery": "follow_up"}
        for message in run.get("messages", [])
        if message["seq"] > run.get("checkpoint_message_seq", 0)
    ]
    pending_ids = {message["id"] for message in pending}
    conversation["queued"] = pending + [
        message for message in conversation.get("queued", []) if message["id"] not in pending_ids
    ]
    by_id = {message["id"]: message for message in pending}
    for message in conversation["messages"]:
        if message["id"] in by_id:
            message.update(by_id[message["id"]])


def record_replies(run, conversation):
    """Preserve actual reply boundaries when a run becomes conversation history."""
    queued_ids = {message["id"] for message in conversation.get("queued", [])}
    steering = [message for message in run.get("messages", []) if message["id"] not in queued_ids]
    delivered_seq = max((message["seq"] for message in steering), default=0)
    replies = [dict(message) for message in run.get("assistant_messages", []) if message["text"].strip()]
    final_text = run.get("result") or run.get("last_text")
    if final_text and (not replies or replies[-1]["text"].strip() != final_text.strip()):
        replies.append({"id": str(uuid4()), "text": final_text, "after_message_seq": delivered_seq})
    if not replies:
        return
    messages = conversation["messages"]
    owned = [
        message
        for message in messages
        if message.get("run_id") == run["id"] and message["id"] not in queued_ids
    ]
    steering_ids = {message["id"] for message in run.get("messages", [])}
    ordered = [
        message for message in owned if message["role"] == "user" and message["id"] not in steering_ids
    ]
    groups = sorted(
        {
            0,
            *(message["seq"] for message in steering),
            *(message.get("after_message_seq", delivered_seq) for message in replies),
        }
    )
    for seq in groups:
        ordered.extend(message for message in steering if message["seq"] == seq)
        ordered.extend(
            {**message, "role": "assistant", "run_id": run["id"]}
            for message in replies
            if message.get("after_message_seq", delivered_seq) == seq
        )
    owned_ids = {message["id"] for message in owned}
    first = next(
        (index for index, message in enumerate(messages) if message["id"] in owned_ids), len(messages)
    )
    conversation["messages"] = [
        *messages[:first],
        *ordered,
        *(message for message in messages[first:] if message["id"] not in owned_ids),
    ]
