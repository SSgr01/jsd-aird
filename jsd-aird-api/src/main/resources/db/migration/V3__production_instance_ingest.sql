CREATE TABLE tpl.record_collection_item (
    id uuid PRIMARY KEY,
    revision_id uuid NOT NULL REFERENCES tpl.record_revision(id) ON DELETE CASCADE,
    production_order_id uuid NOT NULL REFERENCES mfg.production_order(id),
    record_kind varchar(16) NOT NULL CHECK (record_kind IN ('DETAIL', 'MATRIX')),
    parent_field_code varchar(160) NOT NULL,
    parent_data_path varchar(400) NOT NULL,
    record_key varchar(240) NOT NULL,
    record_index integer NOT NULL CHECK (record_index >= 0),
    member_key varchar(240),
    data_jsonb jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (revision_id, parent_data_path, record_key)
);

ALTER TABLE tpl.record_value_index
    ADD COLUMN collection_item_id uuid REFERENCES tpl.record_collection_item(id) ON DELETE CASCADE;

CREATE INDEX idx_record_collection_order
    ON tpl.record_collection_item (production_order_id, parent_field_code, record_index);

CREATE INDEX idx_record_value_collection
    ON tpl.record_value_index (collection_item_id)
    WHERE collection_item_id IS NOT NULL;

CREATE TABLE mfg.production_ingest_job (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    production_order_id uuid NOT NULL REFERENCES mfg.production_order(id) ON DELETE CASCADE,
    requested_template_version_id uuid NOT NULL REFERENCES tpl.template_version(id),
    selected_template_version_id uuid REFERENCES tpl.template_version(id),
    source_type varchar(16) NOT NULL CHECK (source_type IN ('XLSX', 'PHOTO')),
    match_mode varchar(32) NOT NULL DEFAULT 'PENDING'
        CHECK (match_mode IN (
            'PENDING', 'EXACT_MANIFEST', 'SIMILAR_AUTO', 'USER_REVIEW',
            'USER_SELECTED_TEMPLATE', 'INSTANCE_VALUE_EXTRACTION'
        )),
    status varchar(24) NOT NULL
        CHECK (status IN ('QUEUED', 'PROCESSING', 'REVIEW_REQUIRED', 'CONFIRMED', 'FAILED', 'CANCELLED')),
    template_match_score numeric(6,5),
    result_version integer NOT NULL DEFAULT 0,
    result_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    error_message text,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    confirmed_at timestamptz
);

CREATE TABLE mfg.production_ingest_source (
    ingest_job_id uuid NOT NULL REFERENCES mfg.production_ingest_job(id) ON DELETE CASCADE,
    file_object_id uuid NOT NULL REFERENCES ops.file_object(id),
    page_order integer NOT NULL DEFAULT 0 CHECK (page_order >= 0),
    PRIMARY KEY (ingest_job_id, file_object_id)
);

CREATE TABLE mfg.production_ingest_item (
    id uuid PRIMARY KEY,
    ingest_job_id uuid NOT NULL REFERENCES mfg.production_ingest_job(id) ON DELETE CASCADE,
    item_key varchar(240) NOT NULL,
    item_kind varchar(16) NOT NULL CHECK (item_kind IN ('SCALAR', 'DETAIL', 'MATRIX')),
    binding_id varchar(160),
    field_code varchar(160),
    data_path varchar(400) NOT NULL,
    record_key varchar(240),
    record_index integer,
    raw_value_jsonb jsonb,
    normalized_value_jsonb jsonb,
    user_value_jsonb jsonb,
    source_locator_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    confidence numeric(6,5) NOT NULL DEFAULT 1.0,
    review_status varchar(20) NOT NULL
        CHECK (review_status IN ('EXTRACTED', 'NEEDS_REVIEW', 'CONFIRMED', 'REJECTED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (ingest_job_id, item_key)
);

CREATE INDEX idx_production_ingest_job_order
    ON mfg.production_ingest_job (production_order_id, created_at DESC);

CREATE INDEX idx_production_ingest_item_job
    ON mfg.production_ingest_item (ingest_job_id, item_kind, record_index);
