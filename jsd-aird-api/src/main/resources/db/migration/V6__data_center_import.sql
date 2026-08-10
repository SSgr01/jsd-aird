CREATE SCHEMA IF NOT EXISTS data;

COMMENT ON SCHEMA data IS '模板驱动的数据资产导入、暂存、质量和追溯';

CREATE TABLE data.import_job (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    source_file_id uuid NOT NULL,
    source_sha256 char(64) NOT NULL,
    source_file_name varchar(260) NOT NULL,
    source_format varchar(16) NOT NULL CHECK (source_format IN ('XLS', 'XLSX', 'CSV')),
    template_version_id uuid NOT NULL,
    target_data_type varchar(64) NOT NULL CHECK (target_data_type IN (
        'MATERIAL', 'FORMULA', 'PROCESS', 'EQUIPMENT', 'TEST_STANDARD'
    )),
    status varchar(32) NOT NULL CHECK (status IN (
        'CREATED', 'QUEUED', 'PARSING', 'WAITING_SHEET', 'WAITING_MAPPING',
        'VALIDATING', 'WAITING_CONFIRM', 'COMMITTING', 'COMPLETED', 'FAILED', 'CANCELLED'
    )),
    progress smallint NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    current_stage varchar(120),
    parser_version varchar(80),
    duplicate_override boolean NOT NULL DEFAULT false,
    error_message text,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);

CREATE INDEX idx_data_import_job_list
    ON data.import_job (organization_id, created_at DESC);

CREATE INDEX idx_data_import_job_duplicate
    ON data.import_job (organization_id, source_sha256, template_version_id, status);

CREATE TABLE data.import_sheet (
    id uuid PRIMARY KEY,
    import_job_id uuid NOT NULL REFERENCES data.import_job(id) ON DELETE CASCADE,
    sheet_id varchar(160) NOT NULL,
    sheet_name varchar(260) NOT NULL,
    sheet_order integer NOT NULL DEFAULT 0 CHECK (sheet_order >= 0),
    selected boolean NOT NULL DEFAULT true,
    header_rows_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    data_start_row integer,
    data_end_row integer,
    structure_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    confirmation_status varchar(24) NOT NULL DEFAULT 'PENDING'
        CHECK (confirmation_status IN ('PENDING', 'CONFIRMED', 'IGNORED')),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (import_job_id, sheet_id)
);

CREATE TABLE data.import_mapping (
    id uuid PRIMARY KEY,
    import_job_id uuid NOT NULL REFERENCES data.import_job(id) ON DELETE CASCADE,
    import_sheet_id uuid REFERENCES data.import_sheet(id) ON DELETE CASCADE,
    source_column varchar(32) NOT NULL,
    source_header varchar(260),
    field_code varchar(160),
    field_name varchar(200),
    action varchar(24) NOT NULL CHECK (action IN ('MAP', 'IGNORE', 'REQUEST_FIELD', 'PENDING')),
    value_type varchar(32),
    source_unit varchar(64),
    standard_unit varchar(64),
    mapping_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'MATCHED', 'CONFIRMED', 'IGNORED', 'REQUESTED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (import_job_id, import_sheet_id, source_column)
);

CREATE TABLE data.staging_row (
    id uuid PRIMARY KEY,
    import_job_id uuid NOT NULL REFERENCES data.import_job(id) ON DELETE CASCADE,
    import_sheet_id uuid NOT NULL REFERENCES data.import_sheet(id) ON DELETE CASCADE,
    source_row_number integer NOT NULL CHECK (source_row_number > 0),
    raw_values_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    normalized_values_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    corrected_values_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    row_hash char(64),
    status varchar(24) NOT NULL DEFAULT 'STAGED'
        CHECK (status IN ('STAGED', 'VALID', 'WARNING', 'BLOCKED', 'CONFIRMED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (import_job_id, import_sheet_id, source_row_number)
);

CREATE INDEX idx_data_staging_row_job
    ON data.staging_row (import_job_id, import_sheet_id, source_row_number);

CREATE TABLE data.import_issue (
    id uuid PRIMARY KEY,
    import_job_id uuid NOT NULL REFERENCES data.import_job(id) ON DELETE CASCADE,
    staging_row_id uuid REFERENCES data.staging_row(id) ON DELETE CASCADE,
    import_sheet_id uuid REFERENCES data.import_sheet(id) ON DELETE SET NULL,
    field_code varchar(160),
    severity varchar(16) NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'BLOCKER')),
    issue_type varchar(80) NOT NULL,
    source_row_number integer,
    source_column varchar(32),
    source_address varchar(80),
    message text NOT NULL,
    detail_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(24) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'CONFIRMED', 'RESOLVED', 'IGNORED')),
    resolved_by uuid REFERENCES iam.app_user(id),
    resolved_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_data_import_issue_job
    ON data.import_issue (import_job_id, severity, status, source_row_number);

CREATE TABLE data.data_asset (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    target_data_type varchar(64) NOT NULL CHECK (target_data_type IN (
        'MATERIAL', 'FORMULA', 'PROCESS', 'EQUIPMENT', 'TEST_STANDARD'
    )),
    asset_key varchar(260) NOT NULL,
    display_name varchar(260),
    current_revision_id uuid,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'RETIRED')),
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, target_data_type, asset_key)
);

CREATE TABLE data.data_asset_revision (
    id uuid PRIMARY KEY,
    asset_id uuid NOT NULL REFERENCES data.data_asset(id) ON DELETE CASCADE,
    revision_no integer NOT NULL CHECK (revision_no > 0),
    import_job_id uuid NOT NULL REFERENCES data.import_job(id),
    template_version_id uuid NOT NULL,
    raw_data_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    normalized_data_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    corrected_data_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    data_hash char(64) NOT NULL,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (asset_id, revision_no)
);

ALTER TABLE data.data_asset
    ADD CONSTRAINT fk_data_asset_current_revision
    FOREIGN KEY (current_revision_id) REFERENCES data.data_asset_revision(id);

CREATE INDEX idx_data_asset_type
    ON data.data_asset (organization_id, target_data_type, updated_at DESC);

CREATE INDEX idx_data_asset_revision_normalized_gin
    ON data.data_asset_revision USING gin (normalized_data_jsonb);

CREATE TABLE data.source_anchor (
    id uuid PRIMARY KEY,
    asset_revision_id uuid NOT NULL REFERENCES data.data_asset_revision(id) ON DELETE CASCADE,
    import_job_id uuid NOT NULL REFERENCES data.import_job(id),
    field_code varchar(160),
    file_id uuid NOT NULL,
    sheet_id varchar(160),
    sheet_name varchar(260),
    row_number integer,
    column_number integer,
    column_name varchar(32),
    cell_address varchar(80),
    raw_value_jsonb jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_data_source_anchor_revision
    ON data.source_anchor (asset_revision_id, field_code);
