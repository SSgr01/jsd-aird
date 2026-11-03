"""Canonical source identity, shared by fixed QA and research."""

from typing import Any, Literal

from pydantic import Field

from aird_agent.core.contracts import Contract


class SourceRef(Contract):
    kind: Literal[
        "KB_PUBLICATION",
        "RUN_UPLOAD",
        "EXTERNAL_FULLTEXT",
        "BUSINESS_RECORD",
        "DATA_SOURCE_FILE",
        "WEB_RESULT",
    ]
    document_id: str
    version_id: str
    publication_id: str | None = None
    source_node_key: str
    content_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    anchor: dict[str, Any]
    title: str
    url: str | None = None
    resource_id: str | None = None
    resource_type: str | None = None
    project_id: str | None = None
    retrieved_at: str | None = None
