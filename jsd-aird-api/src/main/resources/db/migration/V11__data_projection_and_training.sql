CREATE TABLE data.data_record (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    import_job_id uuid NOT NULL REFERENCES data.import_job(id) ON DELETE CASCADE,
    asset_id uuid REFERENCES data.data_asset(id) ON DELETE SET NULL,
    revision_id uuid REFERENCES data.data_asset_revision(id) ON DELETE SET NULL,
    record_key varchar(260) NOT NULL,
    record_index integer NOT NULL,
    sheet_id varchar(160),
    sheet_name varchar(260),
    source_row_number integer,
    raw_data_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    normalized_data_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    corrected_data_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    effective_data_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    quality_status varchar(24) NOT NULL DEFAULT 'VALID'
        CHECK (quality_status IN ('VALID', 'WARNING', 'BLOCKED')),
    synthetic_key boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (revision_id, record_index)
);

CREATE INDEX idx_data_record_job ON data.data_record(organization_id, import_job_id, record_index);
CREATE INDEX idx_data_record_asset ON data.data_record(asset_id, revision_id);

CREATE TABLE data.data_value (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    record_id uuid NOT NULL REFERENCES data.data_record(id) ON DELETE CASCADE,
    field_code varchar(160) NOT NULL,
    data_path varchar(400),
    value_jsonb jsonb NOT NULL DEFAULT 'null'::jsonb,
    value_text text,
    normalized_unit varchar(64),
    source_anchor_id uuid REFERENCES data.source_anchor(id) ON DELETE SET NULL,
    training_eligible boolean NOT NULL DEFAULT true,
    exclusion_reason varchar(200),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (record_id, field_code, data_path)
);

CREATE INDEX idx_data_value_search ON data.data_value(organization_id, field_code, training_eligible);

CREATE TABLE data.import_mapping_profile (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    template_version_id uuid NOT NULL,
    source_fingerprint char(64) NOT NULL,
    mapping_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    approved_by uuid REFERENCES iam.app_user(id),
    approved_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, template_version_id, source_fingerprint)
);

CREATE TABLE ai.training_dataset (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    import_job_id uuid REFERENCES data.import_job(id) ON DELETE SET NULL,
    template_version_id uuid NOT NULL,
    projection_version varchar(40) NOT NULL,
    name varchar(260) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'REVIEWING', 'APPROVED', 'RETIRED')),
    schema_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    quality_summary_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    source_revision_ids_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_by uuid REFERENCES iam.app_user(id),
    approved_by uuid REFERENCES iam.app_user(id),
    approved_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_ai_training_dataset_list
    ON ai.training_dataset(organization_id, status, updated_at DESC);

CREATE TABLE ai.training_dataset_record (
    id uuid PRIMARY KEY,
    dataset_id uuid NOT NULL REFERENCES ai.training_dataset(id) ON DELETE CASCADE,
    source_record_id uuid REFERENCES data.data_record(id) ON DELETE SET NULL,
    record_key varchar(260) NOT NULL,
    dimensions_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    measures_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    source_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    training_eligible boolean NOT NULL DEFAULT true,
    exclusion_reason varchar(200),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (dataset_id, record_key)
);

CREATE INDEX idx_ai_training_dataset_record_list
    ON ai.training_dataset_record(dataset_id, training_eligible);

ALTER TABLE data.import_job
    ADD COLUMN latest_training_dataset_id uuid REFERENCES ai.training_dataset(id) ON DELETE SET NULL;
