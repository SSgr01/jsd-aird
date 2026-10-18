ALTER TABLE spc.chart_asset
    ADD COLUMN request_id uuid;

CREATE UNIQUE INDEX uq_spc_chart_asset_request
    ON spc.chart_asset (organization_id, request_id)
    WHERE request_id IS NOT NULL;

CREATE TABLE spc.chart_upload_attempt (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    request_id uuid NOT NULL,
    category_id uuid,
    chart_id uuid REFERENCES spc.chart_asset(id),
    file_object_id uuid,
    original_name varchar(500) NOT NULL,
    size_bytes bigint NOT NULL DEFAULT 0 CHECK (size_bytes >= 0),
    status varchar(24) NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED', 'DUPLICATE', 'CANCELLED')),
    error_code varchar(80),
    error_message varchar(2000),
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, request_id)
);

CREATE INDEX idx_spc_chart_upload_attempt_recent
    ON spc.chart_upload_attempt (organization_id, created_at DESC);

CREATE INDEX idx_spc_analysis_run_chart_ids
    ON spc.analysis_run USING gin (chart_ids_jsonb);

ALTER TABLE core.project_resource_link
    DROP CONSTRAINT IF EXISTS project_resource_link_resource_type_check;
ALTER TABLE core.project_resource_link
    ADD CONSTRAINT project_resource_link_resource_type_check
        CHECK (resource_type IN ('KNOWLEDGE_DOCUMENT', 'DATA_IMPORT_JOB', 'SPECTRUM_CHART'));

ALTER TABLE core.project_reference
    DROP CONSTRAINT IF EXISTS project_reference_resource_type_check;
ALTER TABLE core.project_reference
    ADD CONSTRAINT project_reference_resource_type_check
        CHECK (resource_type IN ('KNOWLEDGE_DOCUMENT', 'DATA_IMPORT_JOB', 'SPECTRUM_CHART'));

ALTER TABLE core.project_reference
    DROP CONSTRAINT IF EXISTS project_reference_source_module_check;
ALTER TABLE core.project_reference
    ADD CONSTRAINT project_reference_source_module_check
        CHECK (source_module IN ('KNOWLEDGE', 'DATA_CENTER', 'SPECTRUM'));
