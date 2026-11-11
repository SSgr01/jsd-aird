"""Shared initial execution state; profile-specific policy stays in bindings."""


def initial_run_state(principal):
    return {
        "principal": principal.model_dump(mode="json"),
        "engine_version": "dsh-v1",
        "status": "QUEUED",
        "epoch": 0,
        "model_calls": 0,
        "tool_calls": 0,
        "steps": 0,
        "active_seconds": 0,
        "evidence": {},
        "messages": [],
        "assistant_messages": [],
        "registered_outputs": [],
        "required_artifacts": [],
        "started_at": None,
        "last_text": "",
        "approvals": {},
        "error": None,
    }
