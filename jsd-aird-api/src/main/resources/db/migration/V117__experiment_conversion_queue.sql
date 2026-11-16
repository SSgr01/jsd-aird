-- Conversion is preparation only. Human confirmation remains in data.import_job.
CREATE TABLE IF NOT EXISTS data.experiment_conversion (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    source_file_id uuid NOT NULL REFERENCES ops.file_object(id),
    source_name text NOT NULL,
    source_sha256 text NOT NULL,
    template_version_id uuid NOT NULL REFERENCES tpl.template_version(id),
    category_id uuid NOT NULL,
    kind text NOT NULL CHECK (kind IN ('test','synthesis')),
    status text NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','WAITING_REVIEW','FAILED')),
    result_artifact_id uuid,
    plan_artifact_id uuid,
    report_artifact_id uuid,
    import_job_id uuid REFERENCES data.import_job(id),
    report jsonb,
    error_message text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, owner_id, batch_id, source_file_id)
);
CREATE INDEX IF NOT EXISTS experiment_conversion_owner ON data.experiment_conversion(organization_id,owner_id,created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS experiment_conversion_import ON data.experiment_conversion(import_job_id) WHERE import_job_id IS NOT NULL;
