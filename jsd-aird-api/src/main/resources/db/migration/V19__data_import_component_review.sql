ALTER TABLE data.import_job
    ADD COLUMN IF NOT EXISTS compatibility_report_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE data.data_value
    ADD COLUMN IF NOT EXISTS calculation_trust_status varchar(32)
        CHECK (calculation_trust_status IS NULL OR calculation_trust_status IN (
            'NOT_APPLICABLE', 'TRUSTED_RECALCULATED', 'UNVERIFIED_CACHE', 'MISSING_RESULT'
        ));

CREATE TABLE IF NOT EXISTS data.import_component_override (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    import_job_id uuid NOT NULL REFERENCES data.import_job(id) ON DELETE CASCADE,
    component_id varchar(256) NOT NULL,
    sheet_id varchar(256) NOT NULL,
    source_range varchar(64) NOT NULL,
    reason varchar(500) NOT NULL,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (import_job_id, component_id)
);

CREATE INDEX IF NOT EXISTS idx_import_component_override_job
    ON data.import_component_override (organization_id, import_job_id);
