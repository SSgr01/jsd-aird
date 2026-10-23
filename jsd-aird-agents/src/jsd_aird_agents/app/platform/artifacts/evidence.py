import hashlib

from jsd_aird_agents.contracts import Evidence, canonical, digest


def build_evidence(context, source_type, resource_id, version, title, data, locator=None):
    """Provider-neutral normalization used by both reads and action receipts."""
    content = data if isinstance(data, str) else canonical(data)
    content_hash = hashlib.sha256(content.encode()).hexdigest()
    evidence_id = (
        "ev_"
        + digest(
            [
                context.run_id,
                context.pack_id,
                context.resource_id or context.project_id,
                source_type,
                str(resource_id),
                str(version),
                content_hash,
            ]
        )[:32]
    )
    return Evidence(
        id=evidence_id,
        run_id=context.run_id,
        organization_id=context.principal.organization_id,
        project_id=context.project_id,
        pack_id=context.pack_id,
        scope_id=context.resource_id or context.project_id,
        source_type=source_type,
        resource_id=str(resource_id),
        resource_version=str(version),
        title=title,
        locator=locator or {"kind": "mock_resource", "resource_id": str(resource_id)},
        content=content,
        content_sha256=content_hash,
    )
