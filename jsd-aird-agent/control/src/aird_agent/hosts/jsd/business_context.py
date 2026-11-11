"""Refresh changed business records without replacing the historical evidence ledger."""

import copy

from fastapi import HTTPException

from aird_agent.core.contracts import digest
from aird_agent.evidence.registry import canonical_candidate, register_candidates


def identity(source):
    return source.get("resource_type"), source.get("resource_id")


def version_conflict(error):
    return (getattr(error, "status_code", None) == 409
            and getattr(error, "tool_error", {}).get("error_code") == "VERSION_CONFLICT")


async def authorize_history_snapshot(bindings, principal, previous):
    """Re-read mutable records on a new turn; ACL failures always propagate.

    Old runs remain immutable. Only the evidence inherited by the new turn is
    refreshed, so an earlier draft or project count cannot block the conversation
    forever or silently supply stale facts after a page edit.
    """
    request = await bindings.source_authorization(principal, previous, historical=True)
    try:
        await bindings.java(principal, "authorize_sources", request, previous["id"])
        return previous
    except HTTPException as error:
        if not version_conflict(error) or request.get("purpose"):
            raise
    refreshed, superseded = [], set()
    for source in request["sources"]:
        try:
            await bindings.java(principal, "authorize_sources", {"sources": [source]}, previous["id"])
            continue
        except HTTPException as error:
            if not version_conflict(error):
                raise
        kind, resource_id = identity(source)
        if source.get("kind") != "BUSINESS_RECORD" or kind not in {
            "PROJECT", "STAGE", "TASK", "REQUIREMENT", "MATERIAL", "EXPERIMENT",
        }:
            raise HTTPException(409, "SOURCE_REFRESH_REQUIRED")
        # These domain readers recheck current authorization. Keep experiment
        # identity/version pinned while dropping only its obsolete draft hash.
        ref = {"type": kind, "id": resource_id}
        if kind == "EXPERIMENT":
            ref["version_id"] = source["version_id"]
        project = source.get("project_id") or source.get("anchor", {}).get("ref", {}).get("project_id")
        if project:
            ref["project_id"] = project
        page = source.get("anchor", {}).get("page", {})
        args = {"ref": ref, **{key: page[key] for key in ("section", "offset", "limit") if key in page}}
        result = await bindings.java(principal, "business_read", args, previous["id"])
        candidates = [canonical_candidate(item) for item in result.get("candidates", [])
                      if identity(item.get("source", {})) == (kind, resource_id)]
        if not candidates:
            raise HTTPException(409, "SOURCE_REFRESH_REQUIRED")
        await bindings.java(principal, "authorize_sources", {"sources": [item["source"] for item in candidates]}, previous["id"])
        refreshed.extend(candidates)
        superseded.add(digest(source))
    snapshot = copy.deepcopy(previous)
    stale = {key: item for key, item in snapshot.get("evidence", {}).items()
             if digest(item.get("source", {})) in superseded}
    snapshot.setdefault("superseded_evidence", {}).update(stale)
    snapshot["evidence"] = register_candidates(
        {key: item for key, item in snapshot.get("evidence", {}).items() if key not in stale}, refreshed)
    refs = {digest(source): source for source in snapshot.get("source_refs", []) if digest(source) not in superseded}
    refs.update({digest(item["source"]): item["source"] for item in refreshed})
    snapshot["source_refs"] = list(refs.values())
    return snapshot


async def refresh_draft_context(bindings, principal, run):
    pending = [output for output in run.get("registered_outputs", [])
               if output.get("kind") in {"experiment_draft", "experiment_measurements"} and not output.get("context_refreshed")]
    for output in pending:
        candidates = []
        project = output.get("project_id")
        affected = {("PROJECT", project), ("STAGE", output.get("stage_id")), ("TASK", output.get("task_id"))}
        sources = {identity(source) for source in run.get("source_refs", [])} & affected
        for kind, resource_id in sorted(sources):
            args = {"project_id": project}
            action = {"PROJECT": "get_project_snapshot", "STAGE": "list_project_stages", "TASK": "get_task_detail"}[kind]
            if kind == "TASK":
                args["task_id"] = resource_id
            while True:
                page = await bindings.java(principal, action, args, run["id"])
                selected = [candidate for candidate in page.get("candidates", [])
                            if identity(candidate.get("source", {})) == (kind, resource_id)]
                if selected:
                    candidates.extend(selected)
                    break
                offset = page.get("next_offset")
                if kind != "STAGE" or not isinstance(offset, int) or offset <= args.get("offset", 0):
                    raise HTTPException(409, "DRAFT_CONTEXT_REFRESH_REQUIRED")
                args["offset"] = offset
        page = await bindings.java(principal, "get_experiment_record", {
            "experiment_id": output["id"], "version_id": output["version_id"], "section": "overview",
        }, run["id"])
        candidates.extend(page.get("candidates", []))
        if not any(identity(item.get("source", {})) == ("EXPERIMENT", output["id"]) for item in candidates):
            raise HTTPException(502, "DRAFT_CONTEXT_REFRESH_INVALID")
        candidates = [canonical_candidate(item) for item in candidates]
        refreshed = {identity(item["source"]) for item in candidates}
        async with bindings.store.transaction(principal, run["id"]) as conn:
            current = await bindings.store.get(principal, "run", run["id"], conn)
            receipt = next((value for value in current.get("registered_outputs", [])
                            if value.get("kind") == output.get("kind") and value["id"] == output["id"]
                            and value.get("revision") == output.get("revision")), None)
            if receipt is None or receipt.get("context_refreshed"):
                continue
            superseded = {key: item for key, item in current.get("evidence", {}).items()
                          if identity(item.get("source", {})) in refreshed
                          and key not in {candidate["evidence_id"] for candidate in candidates}}
            active = {key: item for key, item in current.get("evidence", {}).items() if key not in superseded}
            evidence = register_candidates(active, candidates)
            refs = {digest(source): source for source in current.get("source_refs", []) if identity(source) not in refreshed}
            refs.update({digest(item["source"]): item["source"] for item in candidates})
            current.setdefault("superseded_evidence", {}).update(superseded)
            current.update(evidence=evidence, source_refs=list(refs.values()))
            current["turn_evidence_ids"] = sorted(set(current.get("turn_evidence_ids", [])) - superseded.keys())
            current["business_context_updates"] = {
                "reason": "Records refreshed after a confirmed experiment write; use these current sources.",
                "experiment_id": output["id"],
                "superseded_evidence_ids": sorted(superseded),
                "current_records": [{"evidence_id": item["evidence_id"], "text": item["text"]} for item in candidates],
            }
            receipt["context_refreshed"] = True
            current.pop("completion", None)
            await bindings.store.update(principal, "run", current, conn)
            await bindings.store.event(principal, run["id"], "sources.refreshed", {
                "experiment_id": output["id"], "superseded_evidence_ids": sorted(superseded),
                "evidence_ids": [item["evidence_id"] for item in candidates],
            }, conn)
            run.update({key: current[key] for key in ("evidence", "source_refs", "registered_outputs", "business_context_updates")})
