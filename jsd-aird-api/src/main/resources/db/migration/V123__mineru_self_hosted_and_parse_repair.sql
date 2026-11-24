ALTER TABLE kb.mineru_task_checkpoint
    ADD COLUMN provider varchar(24) NOT NULL DEFAULT 'cloud',
    ADD COLUMN endpoint_fingerprint varchar(64),
    ADD COLUMN remote_file_id varchar(260),
    ADD COLUMN upload_headers_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb;

-- A candidate is separate from active parse runs/revisions until explicitly adopted.
ALTER TABLE kb.document_parse_run ADD COLUMN candidate_only boolean NOT NULL DEFAULT false;
CREATE TABLE kb.document_parse_repair (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    document_id uuid NOT NULL REFERENCES kb.document(id) ON DELETE CASCADE,
    document_version_id uuid NOT NULL REFERENCES kb.document_version(id) ON DELETE CASCADE,
    requested_by uuid NOT NULL REFERENCES iam.app_user(id),
    request_key varchar(200) NOT NULL,
    prefer_existing_zip boolean NOT NULL DEFAULT true,
    status varchar(24) NOT NULL DEFAULT 'QUEUED',
    job_id uuid,
    base_publication_id uuid,
    base_review_revision_id uuid,
    base_review_lock_version integer,
    parse_run_id uuid,
    result_jsonb jsonb,
    error_message text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, document_version_id, request_key),
    CHECK (status IN ('QUEUED','RUNNING','READY','ADOPTED','FAILED'))
);
CREATE UNIQUE INDEX uq_document_parse_repair_active ON kb.document_parse_repair(document_version_id)
    WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX ix_document_parse_repair_org ON kb.document_parse_repair(organization_id, created_at DESC);
