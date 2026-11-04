-- Local development applies this SQL manually; do not repair or rewrite migration history.
CREATE TABLE kb.document_bibliography (
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    document_id uuid NOT NULL REFERENCES kb.document(id) ON DELETE CASCADE,
    version_id uuid NOT NULL REFERENCES kb.document_version(id) ON DELETE CASCADE,
    revision integer NOT NULL DEFAULT 0,
    status varchar(32) NOT NULL DEFAULT 'PENDING',
    document_type varchar(24) NOT NULL DEFAULT 'UNKNOWN',
    accepted_jsonb jsonb NOT NULL DEFAULT '{}',
    provenance_jsonb jsonb NOT NULL DEFAULT '{}',
    candidates_jsonb jsonb NOT NULL DEFAULT '[]',
    manual_fields_jsonb jsonb NOT NULL DEFAULT '[]',
    parse_run_id uuid,
    source_fingerprint varchar(64),
    message varchar(500),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, version_id)
);
CREATE INDEX idx_kb_bibliography_document ON kb.document_bibliography(organization_id, document_id);
CREATE TABLE kb.document_bibliography_revision (
    organization_id uuid NOT NULL,
    version_id uuid NOT NULL,
    revision integer NOT NULL,
    snapshot_jsonb jsonb NOT NULL,
    actor_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, version_id, revision),
    FOREIGN KEY (organization_id, version_id) REFERENCES kb.document_bibliography ON DELETE CASCADE
);
CREATE TABLE kb.bibliography_task (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    owner_id uuid NOT NULL REFERENCES iam.app_user(id),
    request_key varchar(180) NOT NULL,
    scope_fingerprint varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, owner_id, request_key)
);
CREATE TABLE kb.bibliography_task_item (
    id uuid PRIMARY KEY,
    task_id uuid NOT NULL REFERENCES kb.bibliography_task(id) ON DELETE CASCADE,
    document_id uuid NOT NULL REFERENCES kb.document(id) ON DELETE CASCADE,
    version_id uuid NOT NULL REFERENCES kb.document_version(id) ON DELETE CASCADE,
    status varchar(32) NOT NULL DEFAULT 'PENDING',
    generation integer NOT NULL DEFAULT 0,
    failure_count integer NOT NULL DEFAULT 0,
    retry_at timestamptz,
    error_code varchar(100),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (task_id, version_id)
);
CREATE INDEX idx_kb_bibliography_task_items ON kb.bibliography_task_item(task_id, id);
