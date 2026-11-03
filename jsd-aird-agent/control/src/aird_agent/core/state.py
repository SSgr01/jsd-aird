"""Shared initial execution state; profile-specific policy stays in bindings."""


def initial_run_state(principal):
    return {
        "principal": principal.model_dump(mode="json"),
        "engine_version": "unified-v1",
        "status": "QUEUED",
        "epoch": 0,
        "lease_until": 0,
        "model_calls": 0,
        "tool_calls": 0,
        "steps": 0,
        "active_seconds": 0,
        "evidence": {},
        "messages": [],
        "checkpoint": None,
        "assistant_messages": [],
        "registered_outputs": [],
        "checkpoint_message_seq": 0,
        "started_at": None,
        "last_text": "",
        "approvals": {},
        "error": None,
    }
