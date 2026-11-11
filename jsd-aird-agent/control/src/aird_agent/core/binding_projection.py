"""Small host binding snapshots; evidence and retrieval state stay in their ledger."""

from fastapi import HTTPException

from aird_agent.core.contracts import digest
from aird_agent.core.stage_timing import measure
from aird_agent.evidence.links import citation_links


def binding_projection(service, run, body, context):
    known = body.get("known_citation_evidence_ids", [])
    tags = body.get("binding_etags", {})
    if (not isinstance(known, list) or len(known) > 10000 or any(not isinstance(eid, str) for eid in known)
            or not isinstance(tags, dict)):
        raise HTTPException(422, "INVALID_BINDING_CURSOR")
    with measure("binding_projection_ms"):
        known = set(known)
        sources = {eid: value for eid, value in run.get("evidence", {}).items() if eid not in known}
        values = {"tools": service.tools_for(run), "active_tools": run.get("active_tools", service.bindings.initial_tools(run)),
                  "instructions": service.instructions_for(run), "context": context}
        versions = {key: digest(value) for key, value in values.items()}
        return {"binding_protocol": 2, "id": run["id"], "dsh_turn": run["dsh_turn"], "epoch":run["epoch"],
                "pack_ids": run.get("pack_ids", []), "automation": run.get("automation"),
                "binding_etags": versions, "citation_evidence_ids": list(sources),
                "citation_links": citation_links(sources, service.settings.public_url, quote_aliases=True),
                **{key: value for key, value in values.items() if tags.get(key) != versions[key]}}
