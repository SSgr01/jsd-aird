CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE iam.organization (
    id uuid PRIMARY KEY,
    code varchar(64) NOT NULL UNIQUE,
    name varchar(160) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE iam.app_user (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    username varchar(80) NOT NULL,
    display_name varchar(120) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, username)
);

INSERT INTO iam.organization (id, code, name)
VALUES ('00000000-0000-0000-0000-000000000001', 'DEV', '本地开发组织');

INSERT INTO iam.app_user (id, organization_id, username, display_name)
VALUES (
    '00000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000001',
    'developer',
    '本地开发用户'
);

CREATE TABLE ops.file_object (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    bucket varchar(100) NOT NULL,
    object_key varchar(500) NOT NULL,
    original_name varchar(260) NOT NULL,
    content_type varchar(160) NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes >= 0),
    sha256 char(64) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('STAGED', 'ACTIVE', 'DELETED')),
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    activated_at timestamptz,
    UNIQUE (bucket, object_key)
);

CREATE INDEX idx_file_object_staged
    ON ops.file_object (created_at)
    WHERE status = 'STAGED';

CREATE TABLE ops.async_job (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    job_type varchar(80) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('READY', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    priority smallint NOT NULL DEFAULT 100,
    payload_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    result_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    progress smallint NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    current_stage varchar(120),
    attempt_count integer NOT NULL DEFAULT 0,
    max_attempts integer NOT NULL DEFAULT 5 CHECK (max_attempts > 0),
    lease_owner varchar(160),
    lease_expires_at timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error text,
    idempotency_key varchar(160),
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    finished_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, idempotency_key)
);

CREATE INDEX idx_async_job_claim
    ON ops.async_job (priority, next_attempt_at, created_at)
    WHERE status IN ('READY', 'RUNNING');

CREATE TABLE ops.outbox_event (
    id uuid PRIMARY KEY,
    aggregate_type varchar(80) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(120) NOT NULL,
    payload_jsonb jsonb NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED')),
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_owner varchar(160),
    lease_expires_at timestamptz,
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);

CREATE INDEX idx_outbox_pending
    ON ops.outbox_event (next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'PROCESSING');

CREATE TABLE ops.audit_log (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    actor_id uuid NOT NULL REFERENCES iam.app_user(id),
    action varchar(120) NOT NULL,
    aggregate_type varchar(80) NOT NULL,
    aggregate_id uuid NOT NULL,
    detail_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE tpl.field_catalog (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    field_code varchar(160) NOT NULL,
    display_name varchar(160) NOT NULL,
    data_type varchar(40) NOT NULL,
    description text,
    aliases_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    system_field boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, field_code)
);

CREATE TABLE tpl.template (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    template_code varchar(80) NOT NULL,
    name varchar(200) NOT NULL,
    purpose varchar(160),
    category varchar(120),
    format varchar(16) NOT NULL CHECK (format IN ('XLSX', 'DOCX')),
    current_published_version_id uuid,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, template_code)
);

CREATE TABLE tpl.template_version (
    id uuid PRIMARY KEY,
    template_id uuid NOT NULL REFERENCES tpl.template(id),
    version_no integer NOT NULL CHECK (version_no > 0),
    status varchar(20) NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    schema_jsonb jsonb NOT NULL,
    layout_summary_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    source_file_id uuid REFERENCES ops.file_object(id),
    editor_snapshot_file_id uuid REFERENCES ops.file_object(id),
    editor_snapshot_hash char(64),
    snapshot_kind varchar(32) NOT NULL CHECK (snapshot_kind IN ('UNIVER_WORKBOOK', 'UNIVER_DOCUMENT')),
    editor_app_version varchar(80) NOT NULL,
    plugin_manifest_hash char(64) NOT NULL,
    snapshot_format_version integer NOT NULL DEFAULT 1,
    export_office_file_id uuid REFERENCES ops.file_object(id),
    export_office_hash char(64),
    schema_hash char(64) NOT NULL,
    mapping_hash char(64) NOT NULL,
    data_hash char(64) NOT NULL,
    workspace_hash char(64) NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    derived_from_version_id uuid REFERENCES tpl.template_version(id),
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    UNIQUE (template_id, version_no)
);

ALTER TABLE tpl.template
    ADD CONSTRAINT fk_template_current_published
    FOREIGN KEY (current_published_version_id) REFERENCES tpl.template_version(id);

CREATE UNIQUE INDEX uq_template_open_draft
    ON tpl.template_version (template_id)
    WHERE status = 'DRAFT';

CREATE TABLE tpl.template_mapping (
    id uuid PRIMARY KEY,
    template_version_id uuid NOT NULL REFERENCES tpl.template_version(id) ON DELETE CASCADE,
    binding_id varchar(120) NOT NULL,
    marker_id varchar(160),
    format varchar(16) NOT NULL CHECK (format IN ('XLSX', 'DOCX')),
    field_code varchar(160),
    data_path varchar(400) NOT NULL,
    binding_role varchar(32) NOT NULL DEFAULT 'FIELD'
        CHECK (binding_role IN ('FIELD', 'REPEAT_REGION', 'CONDITIONAL')),
    locator_type varchar(60) NOT NULL,
    locator_jsonb jsonb NOT NULL,
    sync_direction varchar(20) NOT NULL
        CHECK (sync_direction IN ('TWO_WAY', 'DATA_TO_EDITOR', 'EDITOR_TO_DATA')),
    primary_binding boolean NOT NULL DEFAULT true,
    binding_status varchar(20) NOT NULL DEFAULT 'VALID'
        CHECK (binding_status IN ('VALID', 'INVALID', 'AMBIGUOUS', 'MISSING')),
    diagnostic_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    last_validated_at timestamptz,
    UNIQUE (template_version_id, binding_id)
);

CREATE INDEX idx_template_mapping_path
    ON tpl.template_mapping (template_version_id, data_path);

CREATE TABLE tpl.template_import_job (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    source_file_id uuid NOT NULL REFERENCES ops.file_object(id),
    format varchar(16) NOT NULL CHECK (format IN ('XLSX', 'DOCX')),
    status varchar(24) NOT NULL,
    parser_version varchar(80),
    progress smallint NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    structure_summary_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    generated_template_version_id uuid REFERENCES tpl.template_version(id),
    async_job_id uuid REFERENCES ops.async_job(id),
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE tpl.template_import_issue (
    id uuid PRIMARY KEY,
    import_job_id uuid NOT NULL REFERENCES tpl.template_import_job(id) ON DELETE CASCADE,
    severity varchar(16) NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'BLOCKER')),
    issue_code varchar(100) NOT NULL,
    location_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    message text NOT NULL,
    resolution varchar(40) NOT NULL DEFAULT 'OPEN',
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE tpl.recognition_suggestion (
    id uuid PRIMARY KEY,
    import_job_id uuid NOT NULL REFERENCES tpl.template_import_job(id) ON DELETE CASCADE,
    source varchar(20) NOT NULL CHECK (source IN ('RULE', 'MODEL', 'HUMAN')),
    suggestion_type varchar(60) NOT NULL,
    payload_jsonb jsonb NOT NULL,
    confidence numeric(5,4),
    evidence_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    decision varchar(24) NOT NULL DEFAULT 'PENDING',
    provider varchar(100),
    model varchar(100),
    prompt_version varchar(60),
    request_hash char(64),
    response_hash char(64),
    decided_by uuid REFERENCES iam.app_user(id),
    decided_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE mfg.production_order (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    order_no varchar(80) NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('DRAFT', 'SUBMITTED', 'CANCELLED')),
    template_version_id uuid NOT NULL REFERENCES tpl.template_version(id),
    product_id uuid,
    quantity numeric(20,6),
    unit_code varchar(40),
    planned_date date,
    owner_id uuid REFERENCES iam.app_user(id),
    instance_schema_jsonb jsonb NOT NULL,
    instance_mapping_jsonb jsonb NOT NULL,
    draft_data_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    draft_editor_snapshot_file_id uuid REFERENCES ops.file_object(id),
    draft_editor_snapshot_hash char(64),
    snapshot_kind varchar(32) NOT NULL CHECK (snapshot_kind IN ('UNIVER_WORKBOOK', 'UNIVER_DOCUMENT')),
    editor_app_version varchar(80) NOT NULL,
    plugin_manifest_hash char(64) NOT NULL,
    snapshot_format_version integer NOT NULL DEFAULT 1,
    schema_hash char(64) NOT NULL,
    mapping_hash char(64) NOT NULL,
    data_hash char(64) NOT NULL,
    workspace_hash char(64) NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, order_no)
);

CREATE TABLE tpl.record_revision (
    id uuid PRIMARY KEY,
    production_order_id uuid NOT NULL REFERENCES mfg.production_order(id),
    revision_no integer NOT NULL CHECK (revision_no > 0),
    status varchar(24) NOT NULL CHECK (status IN ('SUBMITTED', 'SUPERSEDED')),
    core_snapshot_jsonb jsonb NOT NULL,
    schema_snapshot_jsonb jsonb NOT NULL,
    mapping_snapshot_jsonb jsonb NOT NULL,
    data_jsonb jsonb NOT NULL,
    editor_snapshot_file_id uuid REFERENCES ops.file_object(id),
    editor_snapshot_hash char(64),
    export_office_file_id uuid REFERENCES ops.file_object(id),
    export_office_hash char(64),
    schema_hash char(64) NOT NULL,
    mapping_hash char(64) NOT NULL,
    data_hash char(64) NOT NULL,
    workspace_hash char(64) NOT NULL,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (production_order_id, revision_no)
);

CREATE TABLE tpl.record_value_index (
    id uuid PRIMARY KEY,
    revision_id uuid NOT NULL REFERENCES tpl.record_revision(id) ON DELETE CASCADE,
    production_order_id uuid NOT NULL REFERENCES mfg.production_order(id),
    field_code varchar(160) NOT NULL,
    data_path varchar(400) NOT NULL,
    value_type varchar(20) NOT NULL,
    text_value text,
    numeric_value numeric,
    boolean_value boolean,
    date_value date,
    reference_value uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (revision_id, data_path)
);

CREATE INDEX idx_record_value_text
    ON tpl.record_value_index (field_code, text_value)
    WHERE text_value IS NOT NULL;

CREATE INDEX idx_record_value_numeric
    ON tpl.record_value_index (field_code, numeric_value)
    WHERE numeric_value IS NOT NULL;

CREATE TABLE tpl.record_attachment (
    id uuid PRIMARY KEY,
    production_order_id uuid NOT NULL REFERENCES mfg.production_order(id),
    revision_id uuid REFERENCES tpl.record_revision(id),
    file_object_id uuid NOT NULL REFERENCES ops.file_object(id),
    attachment_type varchar(40) NOT NULL,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO tpl.field_catalog (
    id, organization_id, field_code, display_name, data_type, description,
    aliases_jsonb, system_field, created_by
)
VALUES
(
    gen_random_uuid(),
    '00000000-0000-0000-0000-000000000001',
    'ORDER.ORDER_NO',
    '生产单号',
    'string',
    '生产单关系型核心字段',
    '["工单号", "单号"]'::jsonb,
    true,
    '00000000-0000-0000-0000-000000000002'
),
(
    gen_random_uuid(),
    '00000000-0000-0000-0000-000000000001',
    'PROCESS.TEMPERATURE',
    '工艺温度',
    'number',
    '工艺温度标准字段',
    '["温度", "设定温度"]'::jsonb,
    false,
    '00000000-0000-0000-0000-000000000002'
);
