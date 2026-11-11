"""Retrieval-only history; native dsh owns model context."""




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
