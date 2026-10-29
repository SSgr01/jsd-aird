CREATE TABLE data.import_workbook_draft (
    organization_id uuid NOT NULL,
    import_job_id uuid NOT NULL,
    snapshot_jsonb jsonb NOT NULL CHECK (jsonb_typeof(snapshot_jsonb) = 'object'),
    instance_jsonb jsonb NOT NULL CHECK (jsonb_typeof(instance_jsonb) = 'object'),
    operations_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(operations_jsonb) = 'array'),
    updated_by uuid NOT NULL REFERENCES iam.app_user(id),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (organization_id, import_job_id),
    FOREIGN KEY (organization_id, import_job_id) REFERENCES data.import_job(organization_id, id) ON DELETE CASCADE
);
