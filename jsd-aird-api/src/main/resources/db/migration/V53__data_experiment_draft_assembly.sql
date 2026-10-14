ALTER TABLE data.import_job
    ADD COLUMN IF NOT EXISTS import_purpose varchar(32) NOT NULL DEFAULT 'DATA_ONLY',
    ADD COLUMN IF NOT EXISTS target_experiment_category_id uuid;

ALTER TABLE data.import_job
    DROP CONSTRAINT IF EXISTS import_job_import_purpose_check;

ALTER TABLE data.import_job
    ADD CONSTRAINT import_job_import_purpose_check
        CHECK (import_purpose IN ('DATA_ONLY', 'EXPERIMENT_DRAFT'));

CREATE TABLE IF NOT EXISTS data.import_experiment_link (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    import_job_id uuid NOT NULL REFERENCES data.import_job(id) ON DELETE CASCADE,
    assembly_key varchar(160) NOT NULL,
    parent_assembly_key varchar(160),
    source_identity varchar(260),
    source_identity_type varchar(32),
    source_record_keys_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    shared_context_record_keys_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    plan_hash char(64) NOT NULL,
    content_hash char(64) NOT NULL,
    status varchar(32) NOT NULL,
    resolution_action varchar(32),
    resolution_reason varchar(500),
    conflicts_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    warnings_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    experiment_id uuid,
    experiment_version_id uuid,
    experiment_no varchar(80),
    error_message text,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    resolved_by uuid REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT import_experiment_link_status_check CHECK (status IN (
        'READY', 'NEEDS_REVIEW', 'RUNNING', 'SYNCED', 'ALREADY_CREATED',
        'BLOCKED', 'FAILED', 'SKIPPED'
    )),
    CONSTRAINT import_experiment_link_resolution_check CHECK (
        resolution_action IS NULL OR resolution_action IN ('SPLIT_BY_RECORD')
    ),
    UNIQUE (organization_id, import_job_id, assembly_key)
);

CREATE INDEX IF NOT EXISTS idx_import_experiment_link_job
    ON data.import_experiment_link(organization_id, import_job_id, status, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_import_experiment_link_experiment
    ON data.import_experiment_link(organization_id, experiment_id)
    WHERE experiment_id IS NOT NULL;

-- The pre-existing ELN schema is deployment-owned rather than created by this
-- Flyway history. Imported historical facts may legitimately omit owner/date,
-- while the interactive create flow continues to require them in application code.
ALTER TABLE IF EXISTS rnd.experiment
    ALTER COLUMN owner_name DROP NOT NULL,
    ALTER COLUMN experiment_date DROP NOT NULL;

COMMENT ON TABLE data.import_experiment_link IS
    'Auditable and idempotent link between committed data-center source records and generated ELN V2 drafts';

COMMENT ON COLUMN data.import_job.import_purpose IS
    'DATA_ONLY keeps committed source facts only; EXPERIMENT_DRAFT requests a later explicit ELN draft assembly';

COMMENT ON COLUMN data.import_job.target_experiment_category_id IS
    'Opaque RND category identity selected by the user; the data module does not own or join the RND category table';
