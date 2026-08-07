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
    dictionary_admin boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, username)
);

INSERT INTO iam.organization (id, code, name)
VALUES ('00000000-0000-0000-0000-000000000001', 'DEV', '本地开发组织');

INSERT INTO iam.app_user (id, organization_id, username, display_name, dictionary_admin)
VALUES (
    '00000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000001',
    'developer',
    '本地开发用户',
    true
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

CREATE TABLE tpl.template_mapping (
    id uuid PRIMARY KEY,
    template_version_id uuid NOT NULL REFERENCES tpl.template_version(id) ON DELETE CASCADE,
    binding_id varchar(120) NOT NULL,
    field_id uuid,
    parent_binding_id varchar(120),
    marker_id varchar(160),
    format varchar(16) NOT NULL CHECK (format IN ('XLSX', 'DOCX')),
    field_code varchar(160),
    data_path varchar(400) NOT NULL,
    binding_role varchar(32) NOT NULL DEFAULT 'FIELD'
        CHECK (binding_role IN ('FIELD', 'REPEAT_REGION', 'CONDITIONAL')),
    mapping_kind varchar(32) NOT NULL DEFAULT 'SCALAR'
        CHECK (mapping_kind IN ('SCALAR', 'REPEAT_REGION', 'REPEAT_FIELD', 'MATRIX_REGION', 'MATRIX_FIELD')),
    repeat_axis varchar(12)
        CHECK (repeat_axis IS NULL OR repeat_axis IN ('ROW', 'COLUMN')),
    record_height integer NOT NULL DEFAULT 1 CHECK (record_height > 0),
    record_width integer NOT NULL DEFAULT 1 CHECK (record_width > 0),
    record_stride integer NOT NULL DEFAULT 1 CHECK (record_stride > 0),
    termination_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
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
    source varchar(20) NOT NULL CHECK (source IN ('RULE', 'MODEL', 'PHYSICAL', 'HUMAN')),
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
    created_at timestamptz NOT NULL DEFAULT now(),
    recognition_run_id uuid,
    recognition_call_id uuid,
    region_id varchar(96),
    relation_id varchar(64),
    block_id varchar(64),
    parent_suggestion_id uuid,
    field_id uuid,
    semantic_fingerprint char(64),
    suggestion_level varchar(16) NOT NULL DEFAULT 'ROOT'
        CHECK (suggestion_level IN ('ROOT', 'CHILD', 'SCALAR')),
    filter_stage varchar(48),
    filter_reason_code varchar(80),
    filter_detail text,
    CONSTRAINT ck_recognition_suggestion_decision
        CHECK (decision IN ('PENDING', 'ACCEPTED', 'REJECTED'))
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

CREATE TABLE tpl.recognition_run (
    id uuid PRIMARY KEY,
    import_job_id uuid NOT NULL REFERENCES tpl.template_import_job(id) ON DELETE CASCADE,
    scope varchar(24) NOT NULL DEFAULT 'WORKBOOK',
    status varchar(24) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED')),
    structure_version integer NOT NULL,
    snapshot_format_version integer NOT NULL,
    region_count integer NOT NULL DEFAULT 0,
    call_count integer NOT NULL DEFAULT 0,
    succeeded_call_count integer NOT NULL DEFAULT 0,
    failed_call_count integer NOT NULL DEFAULT 0,
    prompt_tokens bigint NOT NULL DEFAULT 0,
    completion_tokens bigint NOT NULL DEFAULT 0,
    total_tokens bigint NOT NULL DEFAULT 0,
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    recognition_protocol_version integer NOT NULL DEFAULT 1,
    source_snapshot_hash char(64),
    parent_run_id uuid REFERENCES tpl.recognition_run(id) ON DELETE SET NULL,
    run_reason varchar(48) NOT NULL DEFAULT 'INITIAL_RECOGNITION'
);

CREATE TABLE tpl.recognition_call (
    id uuid PRIMARY KEY,
    recognition_run_id uuid NOT NULL REFERENCES tpl.recognition_run(id) ON DELETE CASCADE,
    region_id varchar(96) NOT NULL,
    attempt integer NOT NULL DEFAULT 1,
    provider varchar(100) NOT NULL,
    model varchar(100) NOT NULL,
    prompt_version varchar(80) NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED')),
    http_status integer,
    started_at timestamptz NOT NULL,
    finished_at timestamptz NOT NULL,
    duration_ms bigint NOT NULL CHECK (duration_ms >= 0),
    prompt_tokens integer NOT NULL DEFAULT 0,
    completion_tokens integer NOT NULL DEFAULT 0,
    total_tokens integer NOT NULL DEFAULT 0,
    request_payload_gzip bytea,
    response_payload_gzip bytea,
    request_hash char(64) NOT NULL,
    response_hash char(64),
    error_type varchar(160),
    error_message text,
    finish_reason varchar(40),
    outcome_code varchar(80),
    response_truncated boolean NOT NULL DEFAULT false,
    payload_expires_at timestamptz NOT NULL DEFAULT (now() + interval '90 days'),
    payload_purged_at timestamptz,
    phase varchar(40) NOT NULL DEFAULT 'REGION_INFERENCE',
    parent_call_id uuid REFERENCES tpl.recognition_call(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (recognition_run_id, region_id, phase, attempt)
);

CREATE TABLE tpl.recognition_trace (
    id uuid PRIMARY KEY,
    recognition_run_id uuid NOT NULL REFERENCES tpl.recognition_run(id) ON DELETE CASCADE,
    recognition_suggestion_id uuid REFERENCES tpl.recognition_suggestion(id) ON DELETE SET NULL,
    stage varchar(48) NOT NULL,
    reason_code varchar(80) NOT NULL,
    message text NOT NULL,
    detail_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE tpl.template_quality_issue (
    id uuid PRIMARY KEY,
    import_job_id uuid NOT NULL REFERENCES tpl.template_import_job(id) ON DELETE CASCADE,
    recognition_run_id uuid NOT NULL REFERENCES tpl.recognition_run(id) ON DELETE CASCADE,
    recognition_call_id uuid REFERENCES tpl.recognition_call(id) ON DELETE SET NULL,
    region_id varchar(96),
    issue_type varchar(80) NOT NULL,
    severity varchar(16) NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'BLOCKER')),
    confidence numeric(5,4) NOT NULL,
    sheet_id varchar(160),
    sheet_name varchar(200),
    address varchar(80) NOT NULL,
    title varchar(240) NOT NULL,
    description text NOT NULL,
    business_impact text NOT NULL,
    evidence_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    suggested_patch_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    inverse_patch_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    auto_fixable boolean NOT NULL DEFAULT false,
    status varchar(24) NOT NULL CHECK (
        status IN ('DETECTED', 'AUTO_APPLIED', 'CONFIRMED', 'IGNORED', 'ROLLED_BACK', 'FAILED')
    ),
    before_snapshot_hash char(64),
    after_snapshot_hash char(64),
    root_block_id varchar(64),
    customer_issue_category varchar(80),
    decided_by uuid REFERENCES iam.app_user(id),
    decided_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE tpl.template_structure_change (
    id uuid PRIMARY KEY,
    template_version_id uuid NOT NULL REFERENCES tpl.template_version(id) ON DELETE CASCADE,
    operation_order integer NOT NULL CHECK (operation_order >= 0),
    operation_type varchar(32) NOT NULL CHECK (
        operation_type IN ('INSERT_ROWS', 'DELETE_ROWS', 'INSERT_COLUMNS', 'DELETE_COLUMNS', 'RENAME_SHEET')
    ),
    sheet_id varchar(160) NOT NULL,
    operation_jsonb jsonb NOT NULL,
    source varchar(16) NOT NULL CHECK (source IN ('CUSTOMER', 'AI')),
    before_mapping_hash char(64) NOT NULL,
    after_mapping_hash char(64) NOT NULL,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE tpl.standard_field_dictionary (
    id uuid PRIMARY KEY,
    dictionary_code varchar(100) NOT NULL,
    version_no integer NOT NULL CHECK (version_no > 0),
    display_name varchar(160) NOT NULL,
    data_type varchar(32) NOT NULL,
    default_unit varchar(40),
    description text,
    repeatable boolean NOT NULL DEFAULT false,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    group_code varchar(100),
    ui_type varchar(24) NOT NULL DEFAULT 'TEXT'
        CHECK (ui_type IN ('TEXT', 'SIGNATURE')),
    supersedes_id uuid REFERENCES tpl.standard_field_dictionary(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (dictionary_code, version_no)
);

CREATE TABLE tpl.standard_field_alias (
    id uuid PRIMARY KEY,
    dictionary_id uuid NOT NULL REFERENCES tpl.standard_field_dictionary(id) ON DELETE CASCADE,
    alias varchar(160) NOT NULL,
    normalized_alias varchar(160) NOT NULL,
    UNIQUE (dictionary_id, normalized_alias)
);

CREATE TABLE tpl.standard_field_request (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    template_version_id uuid REFERENCES tpl.template_version(id) ON DELETE SET NULL,
    field_id varchar(180),
    display_name varchar(160) NOT NULL,
    data_type varchar(32) NOT NULL,
    ui_type varchar(24) NOT NULL DEFAULT 'TEXT'
        CHECK (ui_type IN ('TEXT', 'SIGNATURE')),
    group_code varchar(100),
    description text,
    status varchar(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    proposed_field_code varchar(160),
    approved_dictionary_id uuid REFERENCES tpl.standard_field_dictionary(id),
    review_comment text,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    reviewed_by uuid REFERENCES iam.app_user(id),
    reviewed_at timestamptz
);

CREATE TABLE tpl.template_static_region (
    id uuid PRIMARY KEY,
    template_version_id uuid NOT NULL REFERENCES tpl.template_version(id) ON DELETE CASCADE,
    sheet_id varchar(160) NOT NULL,
    sheet_name varchar(200),
    address varchar(120) NOT NULL,
    region_type varchar(32) NOT NULL
        CHECK (region_type IN ('STATIC_REFERENCE', 'INSTRUCTION', 'NOTE')),
    display_name varchar(160) NOT NULL,
    source varchar(24) NOT NULL DEFAULT 'TEMPLATE_BASELINE'
        CHECK (source IN ('TEMPLATE_BASELINE', 'MODEL', 'HUMAN')),
    content_hash char(64),
    locked boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (template_version_id, sheet_id, address)
);

CREATE INDEX idx_recognition_suggestion_review
    ON tpl.recognition_suggestion (import_job_id, decision, confidence DESC);

CREATE INDEX idx_recognition_run_job
    ON tpl.recognition_run (import_job_id, created_at DESC);

CREATE INDEX idx_recognition_call_expiry
    ON tpl.recognition_call (payload_expires_at)
    WHERE payload_purged_at IS NULL;

CREATE INDEX idx_recognition_suggestion_region
    ON tpl.recognition_suggestion (recognition_run_id, region_id, confidence DESC);

CREATE INDEX idx_recognition_suggestion_relation
    ON tpl.recognition_suggestion (recognition_run_id, relation_id)
    WHERE relation_id IS NOT NULL;

CREATE INDEX idx_recognition_suggestion_parent
    ON tpl.recognition_suggestion (parent_suggestion_id, decision);

CREATE UNIQUE INDEX uq_recognition_suggestion_fingerprint
    ON tpl.recognition_suggestion (recognition_run_id, semantic_fingerprint)
    WHERE semantic_fingerprint IS NOT NULL;

CREATE INDEX idx_recognition_trace_run
    ON tpl.recognition_trace (recognition_run_id, created_at);

CREATE INDEX idx_template_mapping_parent
    ON tpl.template_mapping (template_version_id, parent_binding_id);

CREATE UNIQUE INDEX uq_template_open_draft
    ON tpl.template_version (template_id)
    WHERE status = 'DRAFT';

CREATE INDEX idx_template_quality_issue_run
    ON tpl.template_quality_issue (recognition_run_id, status, severity, created_at);

CREATE INDEX idx_template_quality_issue_location
    ON tpl.template_quality_issue (import_job_id, sheet_id, address);

CREATE INDEX idx_template_quality_issue_customer_root
    ON tpl.template_quality_issue (
        recognition_run_id, sheet_id, root_block_id, customer_issue_category, status
    );

CREATE INDEX idx_template_structure_change_version
    ON tpl.template_structure_change (template_version_id, created_at, operation_order);

CREATE INDEX idx_standard_field_dictionary_name
    ON tpl.standard_field_dictionary (lower(display_name), status);

CREATE INDEX idx_standard_field_request_queue
    ON tpl.standard_field_request (organization_id, status, created_at DESC);

INSERT INTO tpl.standard_field_dictionary (
    id, dictionary_code, version_no, display_name, data_type, default_unit,
    description, repeatable, status, group_code, ui_type
)
VALUES
    (gen_random_uuid(), 'PRODUCTION.PRODUCT_NAME', 1, '品名', 'string', NULL, '产品或原料的名称', false, 'ACTIVE', 'Production', 'TEXT'),
    (gen_random_uuid(), 'PRODUCTION.ACTUAL_OUTPUT', 1, '实际产量', 'number', NULL, '本次生产实际产出数量', false, 'ACTIVE', 'Production', 'TEXT'),
    (gen_random_uuid(), 'FORMULA.ITEM.MATERIAL_CODE', 1, '原料编号', 'string', NULL, '配方明细中的物料编号', true, 'ACTIVE', 'FormulaDetail', 'TEXT'),
    (gen_random_uuid(), 'FORMULA.ITEM.RATIO', 1, '配方比例', 'number', '%', '配方明细中的比例', true, 'ACTIVE', 'FormulaDetail', 'TEXT'),
    (gen_random_uuid(), 'FORMULA.ITEM.THEORETICAL_KG', 1, '理论投料量', 'number', 'KG', '按配方计算的理论投料量', true, 'ACTIVE', 'FormulaDetail', 'TEXT'),
    (gen_random_uuid(), 'FORMULA.ITEM.ACTUAL_KG', 1, '实际投料量', 'number', 'KG', '实际投料数量', true, 'ACTIVE', 'FormulaDetail', 'TEXT'),
    (gen_random_uuid(), 'FORMULA.ITEM.BATCH_NO', 1, '批号', 'string', NULL, '原料或生产批次编号', true, 'ACTIVE', 'FormulaDetail', 'TEXT'),
    (gen_random_uuid(), 'FORMULA.ITEM.SEQUENCE', 1, '序号', 'integer', NULL, '明细序号', true, 'ACTIVE', 'FormulaDetail', 'TEXT'),
    (gen_random_uuid(), 'FORMULA.ITEM.REMARK', 1, '备注', 'string', NULL, '补充说明', true, 'ACTIVE', 'FormulaDetail', 'TEXT'),
    (gen_random_uuid(), 'PRODUCTION.CATEGORY', 1, '类别', 'string', NULL, '生产或物料类别', false, 'ACTIVE', 'Production', 'TEXT'),
    (gen_random_uuid(), 'PRODUCTION.ORDER_NO', 1, '订单号', 'string', NULL, '生产订单编号', false, 'ACTIVE', 'Production', 'TEXT'),
    (gen_random_uuid(), 'DOCUMENT.FORM_NO', 1, '表单编号', 'string', NULL, '业务表单编号', false, 'ACTIVE', 'Document', 'TEXT'),
    (gen_random_uuid(), 'PRODUCTION.REACTOR', 1, '反应釜', 'string', NULL, '生产使用的反应釜', false, 'ACTIVE', 'Production', 'TEXT'),
    (gen_random_uuid(), 'PRODUCTION.PACKAGE_BATCH_NO', 1, '包装批号', 'string', NULL, '包装批次编号', false, 'ACTIVE', 'Packaging', 'TEXT'),
    (gen_random_uuid(), 'PRODUCTION.MANUFACTURE_DATE', 1, '制造日期', 'date', NULL, '制造或生产日期', false, 'ACTIVE', 'Production', 'TEXT'),
    (gen_random_uuid(), 'PACKAGING.MATERIAL', 1, '包装物料', 'string', NULL, '包装使用的物料', false, 'ACTIVE', 'Packaging', 'TEXT'),
    (gen_random_uuid(), 'PACKAGING.SPECIFICATION', 1, '包装规格', 'string', NULL, '包装规格说明', false, 'ACTIVE', 'Packaging', 'TEXT'),
    (gen_random_uuid(), 'PACKAGING.QUANTITY', 1, '包装数量', 'number', NULL, '包装数量', false, 'ACTIVE', 'Packaging', 'TEXT');

INSERT INTO tpl.standard_field_alias (id, dictionary_id, alias, normalized_alias)
SELECT gen_random_uuid(), d.id, a.alias,
       lower(regexp_replace(replace(a.alias, '：', ':'), '[[:space:]:：]', '', 'g'))
FROM tpl.standard_field_dictionary d
JOIN (VALUES
    ('PRODUCTION.PRODUCT_NAME', '产品名称'),
    ('PRODUCTION.PRODUCT_NAME', '产品名'),
    ('PRODUCTION.ACTUAL_OUTPUT', '实际生产量'),
    ('FORMULA.ITEM.MATERIAL_CODE', '物料编号'),
    ('FORMULA.ITEM.RATIO', '比例'),
    ('FORMULA.ITEM.THEORETICAL_KG', '理论用量'),
    ('FORMULA.ITEM.ACTUAL_KG', '实际投料'),
    ('FORMULA.ITEM.BATCH_NO', '原料批号')
) AS a(dictionary_code, alias) ON a.dictionary_code = d.dictionary_code
WHERE d.version_no = 1;

-- ============================================================================
-- 标准字段字典增量：由 7 份客户模板归并生成
-- 归并原则：
-- 1. 只为稳定业务概念和需要跨记录检索/分析的指标创建专用字段。
-- 2. 基材、条件、时点、介质不与指标拼成新字段，分别写入 TEST.RESULT.* 限定字段。
-- 3. 低频长尾测试项目进入 TEST.RESULT.ITEM_NAME / TEST.RESULT.VALUE，不直接扩张字典。
-- 4. 公司名、表单标题、章节标题、固定说明、测试方法值、公式合计保留为模板静态区。
-- 5. 后续新字段先进入 tpl.standard_field_request，经复用性和分析价值评审后再提升。
-- ============================================================================

-- 收紧两个已有字段的语义边界，避免与新增字段重叠。
UPDATE tpl.standard_field_dictionary
SET description = '成品或生产对象的名称'
WHERE dictionary_code = 'PRODUCTION.PRODUCT_NAME' AND version_no = 1;

UPDATE tpl.standard_field_dictionary
SET description = '配方明细中的原料批号'
WHERE dictionary_code = 'FORMULA.ITEM.BATCH_NO' AND version_no = 1;

WITH seed (
           dictionary_code, version_no, display_name, data_type, default_unit,
           description, repeatable, status, group_code, ui_type
  ) AS (
  VALUES
    ('PRODUCTION.BATCH_NO', 1, '生产批号', 'string', NULL, '成品生产批次编号；不与配方明细中的原料批号混用', false, 'ACTIVE', 'Production', 'TEXT'),
    ('PRODUCTION.QUANTITY', 1, '生产数量', 'number', NULL, '生产任务或批次的数量', false, 'ACTIVE', 'Production', 'TEXT'),
    ('PRODUCTION.OPERATION_PROCEDURE', 1, '操作程序', 'string', NULL, '生产任务中的操作步骤、工艺程序或投料说明', false, 'ACTIVE', 'Production', 'TEXT'),
    ('FORMULA.ITEM.MATERIAL_NAME', 1, '原料名称', 'string', NULL, '配方或实验配方明细中的原料名称', true, 'ACTIVE', 'FormulaDetail', 'TEXT'),
    ('FORMULA.ITEM.AMOUNT', 1, '配方用量', 'number', NULL, '实验配方或无固定计量单位配方中的用量', true, 'ACTIVE', 'FormulaDetail', 'TEXT'),
    ('DOCUMENT.VERSION', 1, '版本', 'string', NULL, '受控文件版本', false, 'ACTIVE', 'Document', 'TEXT'),
    ('DOCUMENT.PAGE_NO', 1, '页次', 'string', NULL, '文件页次或页码，如 1/2', false, 'ACTIVE', 'Document', 'TEXT'),
    ('DOCUMENT.FILE_TYPE', 1, '文件类型', 'string', NULL, '受控文件类型', false, 'ACTIVE', 'Document', 'TEXT'),
    ('DOCUMENT.EFFECTIVE_DATE', 1, '生效日期', 'date', NULL, '文件生效日期', false, 'ACTIVE', 'Document', 'TEXT'),
    ('DOCUMENT.FILE_NAME', 1, '文件名称', 'string', NULL, '受控文件名称', false, 'ACTIVE', 'Document', 'TEXT'),
    ('DOCUMENT.FILE_NO', 1, '文件编号', 'string', NULL, '受控文件编号；与业务表单编号区分', false, 'ACTIVE', 'Document', 'TEXT'),
    ('WORKFLOW.PREPARED_BY', 1, '制定/制单人', 'string', NULL, '文件制定人或业务单据制单人', false, 'ACTIVE', 'Workflow', 'SIGNATURE'),
    ('WORKFLOW.COMPLETED_BY', 1, '完成人', 'string', NULL, '任务实际完成人', false, 'ACTIVE', 'Workflow', 'SIGNATURE'),
    ('WORKFLOW.SUPERVISED_BY', 1, '监管人', 'string', NULL, '任务监管或现场监督人员', false, 'ACTIVE', 'Workflow', 'SIGNATURE'),
    ('WORKFLOW.REVIEWED_BY', 1, '审核人', 'string', NULL, '文件或业务单据审核人员', false, 'ACTIVE', 'Workflow', 'SIGNATURE'),
    ('WORKFLOW.APPROVED_BY', 1, '核准人', 'string', NULL, '文件或业务单据核准人员', false, 'ACTIVE', 'Workflow', 'SIGNATURE'),
    ('WORKFLOW.APPROVAL.ITEM.ROLE', 1, '审批角色', 'string', NULL, '审批、处理或确认步骤的角色/部门', true, 'ACTIVE', 'WorkflowApproval', 'TEXT'),
    ('WORKFLOW.APPROVAL.ITEM.SIGNATURE', 1, '签名', 'string', NULL, '审批或处理步骤的签名；兼容模板中的“签名/日期”组合区', true, 'ACTIVE', 'WorkflowApproval', 'SIGNATURE'),
    ('WORKFLOW.APPROVAL.ITEM.SIGNED_AT', 1, '签署日期', 'date', NULL, '审批或处理步骤的签署日期', true, 'ACTIVE', 'WorkflowApproval', 'TEXT'),
    ('WORKFLOW.APPROVAL.ITEM.COMMENT', 1, '审批意见', 'string', NULL, '审批、处理或确认步骤的意见', true, 'ACTIVE', 'WorkflowApproval', 'TEXT'),
    ('TEST.PROJECT_NAME', 1, '实验项目', 'string', NULL, '实验、测试或验证项目名称', false, 'ACTIVE', 'TestReport', 'TEXT'),
    ('TEST.PURPOSE', 1, '实验目的', 'string', NULL, '实验或测试目的', false, 'ACTIVE', 'TestReport', 'TEXT'),
    ('TEST.OPERATOR', 1, '实验人员', 'string', NULL, '实验人、测试人或执行人员', false, 'ACTIVE', 'TestReport', 'SIGNATURE'),
    ('TEST.DATE', 1, '实验日期', 'date', NULL, '实验或测试日期', false, 'ACTIVE', 'TestReport', 'TEXT'),
    ('TEST.AMBIENT_TEMPERATURE', 1, '环境温度', 'number', '℃', '实验环境温度', false, 'ACTIVE', 'TestReport', 'TEXT'),
    ('TEST.AMBIENT_HUMIDITY', 1, '环境湿度', 'number', '%', '实验环境相对湿度', false, 'ACTIVE', 'TestReport', 'TEXT'),
    ('TEST.SAMPLE_MATERIAL', 1, '测试用素材', 'string', NULL, '测试使用的素材、基材或样品说明', false, 'ACTIVE', 'TestReport', 'TEXT'),
    ('TEST.APPLICATION_METHOD', 1, '施工方式', 'string', NULL, '涂布、喷涂、刮涂、淋涂等施工方式', false, 'ACTIVE', 'TestReport', 'TEXT'),
    ('TEST.CURE_CONDITION', 1, '固化/测试条件', 'string', NULL, '报告级固化条件或整体测试条件', false, 'ACTIVE', 'TestReport', 'TEXT'),
    ('TEST.SUPPLEMENTARY_NOTE', 1, '补充说明', 'string', NULL, '实验方案的补充说明', false, 'ACTIVE', 'TestReport', 'TEXT'),
    ('TEST.CONCLUSION', 1, '结果/结论/小结', 'string', NULL, '测试结果汇总、结论或小结', false, 'ACTIVE', 'TestReport', 'TEXT'),
    ('TEST.REMARK', 1, '测试备注', 'string', NULL, '测试报告级备注', false, 'ACTIVE', 'TestReport', 'TEXT'),
    ('TEST.EXPERIMENT_NO', 1, '实验编号', 'string', NULL, '实验配方、样品或方案编号', true, 'ACTIVE', 'TestSample', 'TEXT'),
    ('TEST.SAMPLE_CODE', 1, '样品编号', 'string', NULL, '树脂、样品或测试对象编号', true, 'ACTIVE', 'TestSample', 'TEXT'),
    ('TEST.SAMPLE_DESCRIPTION', 1, '样品内容', 'string', NULL, '样品、树脂内容或引发剂方案描述', true, 'ACTIVE', 'TestSample', 'TEXT'),
    ('TEST.PLATFORM', 1, '测试平台', 'string', NULL, '测试所用平台、板材或设备平台', true, 'ACTIVE', 'TestContext', 'TEXT'),
    ('TEST.METHOD', 1, '测试方法', 'string', NULL, '测试方法、仪器或判定方式', true, 'ACTIVE', 'TestContext', 'TEXT'),
    ('TEST.RESULT.ITEM_NAME', 1, '测试项目名称', 'string', NULL, '长尾测试项目或可编辑检测项目名称', true, 'ACTIVE', 'TestResult', 'TEXT'),
    ('TEST.RESULT.VALUE', 1, '通用测试结果', 'string', NULL, '尚未提升为专用标准字段的长尾测试结果', true, 'ACTIVE', 'TestResult', 'TEXT'),
    ('TEST.RESULT.UNIT', 1, '结果单位', 'string', NULL, '测试结果的计量单位', true, 'ACTIVE', 'TestResult', 'TEXT'),
    ('TEST.RESULT.CONDITION', 1, '结果测试条件', 'string', NULL, '单项结果对应的温度、湿度、固化能量、水煮条件等', true, 'ACTIVE', 'TestResult', 'TEXT'),
    ('TEST.RESULT.SUBSTRATE', 1, '测试基材', 'string', NULL, '单项结果对应的 PC、PET、玻璃、ABS 等基材', true, 'ACTIVE', 'TestResult', 'TEXT'),
    ('TEST.RESULT.TIMEPOINT', 1, '测试时点', 'string', NULL, '初始、12 小时后、300h、500h 等测试时点', true, 'ACTIVE', 'TestResult', 'TEXT'),
    ('TEST.RESULT.MEDIUM', 1, '测试介质', 'string', NULL, '防晒霜、芥末酱、人工汗、酒精等介质', true, 'ACTIVE', 'TestResult', 'TEXT'),
    ('TEST.RESULT.REPLICATE_NO', 1, '平行样编号', 'integer', NULL, '板①、板②、板③等平行样序号', true, 'ACTIVE', 'TestResult', 'TEXT'),
    ('TEST.RESULT.REMARK', 1, '结果备注', 'string', NULL, '单项测试结果的备注或异常说明', true, 'ACTIVE', 'TestResult', 'TEXT'),
    ('MATERIAL.PROPERTY.APPEARANCE', 1, '原料/树脂外观', 'string', NULL, '原料、树脂或成品物性外观', true, 'ACTIVE', 'MaterialProperty', 'TEXT'),
    ('MATERIAL.PROPERTY.SOLIDS_CONTENT', 1, '材料固含', 'number', '%', '树脂、原料或成品的固含量', true, 'ACTIVE', 'MaterialProperty', 'TEXT'),
    ('MATERIAL.PROPERTY.VISCOSITY', 1, '材料粘度', 'number', NULL, '材料粘度；具体方法和单位由测试方法/结果单位限定', true, 'ACTIVE', 'MaterialProperty', 'TEXT'),
    ('MATERIAL.PROPERTY.SPECIFIC_GRAVITY', 1, '比重', 'number', NULL, '材料比重或密度相关结果', true, 'ACTIVE', 'MaterialProperty', 'TEXT'),
    ('MATERIAL.PROPERTY.REFRACTIVE_INDEX', 1, '折射率', 'number', NULL, '材料折射率/折光率', true, 'ACTIVE', 'MaterialProperty', 'TEXT'),
    ('MATERIAL.PROPERTY.ACID_VALUE', 1, '酸值', 'number', 'mgKOH/g', '材料酸值', true, 'ACTIVE', 'MaterialProperty', 'TEXT'),
    ('MATERIAL.PROPERTY.WATER_CONTENT', 1, '含水率', 'number', '%', '材料含水率', true, 'ACTIVE', 'MaterialProperty', 'TEXT'),
    ('MATERIAL.PROPERTY.MOLECULAR_WEIGHT', 1, '分子量', 'number', NULL, '材料分子量或相对分子量', true, 'ACTIVE', 'MaterialProperty', 'TEXT'),
    ('MATERIAL.PROPERTY.FINENESS', 1, '细度', 'number', 'μm', '材料细度', true, 'ACTIVE', 'MaterialProperty', 'TEXT'),
    ('MATERIAL.PROPERTY.AVERAGE_PARTICLE_SIZE', 1, '平均粒径', 'number', 'nm', '材料平均粒径', true, 'ACTIVE', 'MaterialProperty', 'TEXT'),
    ('MATERIAL.PROPERTY.D90_PARTICLE_SIZE', 1, 'D90粒径', 'number', 'nm', '材料 D90 粒径', true, 'ACTIVE', 'MaterialProperty', 'TEXT'),
    ('MATERIAL.PROPERTY.PARTICLE_PEAK_COUNT', 1, '粒径峰数', 'integer', NULL, '粒径分布峰数量', true, 'ACTIVE', 'MaterialProperty', 'TEXT'),
    ('MATERIAL.PROPERTY.PH', 1, 'pH值', 'number', NULL, '材料 pH 值', true, 'ACTIVE', 'MaterialProperty', 'TEXT'),
    ('MATERIAL.PROPERTY.HYDROXYL_VALUE', 1, '羟值', 'number', 'mgKOH/g', '材料羟值', true, 'ACTIVE', 'MaterialProperty', 'TEXT'),
    ('MATERIAL.PROPERTY.AMINE_EQUIVALENT', 1, '胺当量', 'number', 'g/eq', '材料胺当量', true, 'ACTIVE', 'MaterialProperty', 'TEXT'),
    ('MATERIAL.PROPERTY.EPOXY_EQUIVALENT', 1, '环氧当量', 'number', 'g/eq', '材料环氧当量', true, 'ACTIVE', 'MaterialProperty', 'TEXT'),
    ('MATERIAL.PROPERTY.SOLVENT_COMPOSITION', 1, '溶剂组成', 'string', NULL, '材料中的溶剂组成', true, 'ACTIVE', 'MaterialProperty', 'TEXT'),
    ('COATING.PROPERTY.SOLIDS_CONTENT', 1, '涂料固含', 'number', '%', '涂料体系固含量', true, 'ACTIVE', 'CoatingProperty', 'TEXT'),
    ('COATING.PROPERTY.APPEARANCE', 1, '涂料外观', 'string', NULL, '涂料体系外观', true, 'ACTIVE', 'CoatingProperty', 'TEXT'),
    ('FILM.PROPERTY.SURFACE_DRYNESS', 1, '漆膜表干性', 'string', NULL, '漆膜表干、干性或固化后的表面干燥状态', true, 'ACTIVE', 'FilmProperty', 'TEXT'),
    ('FILM.PROPERTY.APPEARANCE', 1, '漆膜外观', 'string', NULL, '固化后漆膜外观', true, 'ACTIVE', 'FilmProperty', 'TEXT'),
    ('FILM.PROPERTY.THICKNESS', 1, '膜厚', 'number', 'μm', '漆膜厚度', true, 'ACTIVE', 'FilmProperty', 'TEXT'),
    ('FILM.PROPERTY.WARPAGE', 1, '干膜翘曲度', 'number', NULL, '干膜翘曲度；基材与放置时点通过限定字段表达', true, 'ACTIVE', 'FilmProperty', 'TEXT'),
    ('FILM.PROPERTY.ADHESION', 1, '附着力', 'string', NULL, '初始或条件处理后的附着力；基材和处理条件单独记录', true, 'ACTIVE', 'FilmProperty', 'TEXT'),
    ('FILM.PROPERTY.ELONGATION', 1, '拉伸/延展率', 'number', '%', '漆膜拉伸率或延展率', true, 'ACTIVE', 'FilmProperty', 'TEXT'),
    ('FILM.PROPERTY.HARDNESS', 1, '硬度', 'string', NULL, '漆膜硬度；方法、负重、膜厚由限定字段表达', true, 'ACTIVE', 'FilmProperty', 'TEXT'),
    ('FILM.PROPERTY.GLOSS', 1, '光泽', 'number', 'GU', '漆膜光泽或光泽变化', true, 'ACTIVE', 'FilmProperty', 'TEXT'),
    ('FILM.PROPERTY.HAZE', 1, '雾度', 'number', '%', '漆膜或透明材料雾度', true, 'ACTIVE', 'FilmProperty', 'TEXT'),
    ('FILM.PROPERTY.TRANSMITTANCE', 1, '透过率', 'number', '%', '漆膜或透明材料透过率', true, 'ACTIVE', 'FilmProperty', 'TEXT'),
    ('FILM.PROPERTY.COLOR_L', 1, 'L值', 'number', NULL, '色差体系中的 L 值', true, 'ACTIVE', 'FilmProperty', 'TEXT'),
    ('FILM.PROPERTY.COLOR_DIFFERENCE', 1, '色差ΔE', 'number', NULL, '初始或老化后的色差 ΔE', true, 'ACTIVE', 'FilmProperty', 'TEXT'),
    ('FILM.PROPERTY.ABRASION_RESISTANCE', 1, '耐磨性', 'string', NULL, '钢丝绒、橡皮擦或 RCA 等耐磨测试结果', true, 'ACTIVE', 'FilmProperty', 'TEXT'),
    ('FILM.PROPERTY.GEL_TIME', 1, '胶化时间', 'number', 's', '体系或漆膜胶化时间', true, 'ACTIVE', 'FilmProperty', 'TEXT'),
    ('FILM.PROPERTY.CURE_PERFORMANCE', 1, '固化性能', 'string', NULL, 'UV 固化性或表面交联程度', true, 'ACTIVE', 'FilmProperty', 'TEXT'),
    ('QUALITY.NONCONFORMITY.DESCRIPTION', 1, '不合格描述', 'string', NULL, '品质部门对不合格现象的描述', false, 'ACTIVE', 'QualityNonconformity', 'TEXT'),
    ('QUALITY.NONCONFORMITY.CAUSE_AND_DISPOSITION', 1, '原因分析及处理', 'string', NULL, '技术部门的原因分析与处置方案', false, 'ACTIVE', 'QualityNonconformity', 'TEXT'),
    ('QUALITY.NONCONFORMITY.MANAGER_DECISION', 1, '总经理批示', 'string', NULL, '管理层对品质不良事项的批示', false, 'ACTIVE', 'QualityNonconformity', 'TEXT'),
    ('QUALITY.NONCONFORMITY.CORRECTIVE_PREVENTIVE_ACTION', 1, '改进和预防措施', 'string', NULL, '后续改进、纠正和预防措施', false, 'ACTIVE', 'QualityNonconformity', 'TEXT'),
    ('QUALITY.NONCONFORMITY.FOLLOWUP_VERIFICATION', 1, '跟踪效果确认', 'string', NULL, '改进措施实施后的效果跟踪与确认', false, 'ACTIVE', 'QualityNonconformity', 'TEXT')
)
INSERT INTO tpl.standard_field_dictionary (
    id, dictionary_code, version_no, display_name, data_type, default_unit,
    description, repeatable, status, group_code, ui_type
)
SELECT
  gen_random_uuid(), dictionary_code, version_no, display_name, data_type, default_unit,
  description, repeatable, status, group_code, ui_type
FROM seed
  ON CONFLICT (dictionary_code, version_no) DO NOTHING;

WITH alias_seed (dictionary_code, alias) AS (
  VALUES
    ('COATING.PROPERTY.APPEARANCE', '涂料外观'),
    ('COATING.PROPERTY.SOLIDS_CONTENT', '涂料固含'),
    ('DOCUMENT.EFFECTIVE_DATE', '生效日期'),
    ('DOCUMENT.FILE_NAME', '文件名称'),
    ('DOCUMENT.FILE_NO', '文件编号'),
    ('DOCUMENT.FILE_TYPE', '文件类型'),
    ('DOCUMENT.PAGE_NO', '页次'),
    ('DOCUMENT.PAGE_NO', '页码'),
    ('DOCUMENT.VERSION', '版本'),
    ('FILM.PROPERTY.ABRASION_RESISTANCE', 'RCA次数'),
    ('FILM.PROPERTY.ABRASION_RESISTANCE', '耐橡皮擦1000次'),
    ('FILM.PROPERTY.ABRASION_RESISTANCE', '耐钢丝绒1000次'),
    ('FILM.PROPERTY.ABRASION_RESISTANCE', '钢丝绒耐磨'),
    ('FILM.PROPERTY.ABRASION_RESISTANCE', '钢丝绒耐磨（1X1，1KG负重）'),
    ('FILM.PROPERTY.ABRASION_RESISTANCE', '钢丝绒耐磨（1x1，1KG负重）'),
    ('FILM.PROPERTY.ABRASION_RESISTANCE', '钢丝绒耐磨（1x1，500g负重）'),
    ('FILM.PROPERTY.ADHESION', '85度水煮1小时后的附着力'),
    ('FILM.PROPERTY.ADHESION', '再沸水煮1小时后的附着力'),
    ('FILM.PROPERTY.ADHESION', '初始附着力'),
    ('FILM.PROPERTY.ADHESION', '初始附着力测试'),
    ('FILM.PROPERTY.ADHESION', '水煮后附着力测试'),
    ('FILM.PROPERTY.ADHESION', '水煮附着力'),
    ('FILM.PROPERTY.ADHESION', '沸水煮1小时后附着力'),
    ('FILM.PROPERTY.ADHESION', '附着力'),
    ('FILM.PROPERTY.APPEARANCE', '漆膜外观'),
    ('FILM.PROPERTY.COLOR_DIFFERENCE', '△E平均值'),
    ('FILM.PROPERTY.COLOR_DIFFERENCE', '固化出来立刻测试色差△E'),
    ('FILM.PROPERTY.COLOR_DIFFERENCE', '耐芥末酱△E'),
    ('FILM.PROPERTY.COLOR_DIFFERENCE', '色差ΔE'),
    ('FILM.PROPERTY.COLOR_DIFFERENCE', '色差△E'),
    ('FILM.PROPERTY.COLOR_L', 'L值'),
    ('FILM.PROPERTY.CURE_PERFORMANCE', 'UV固化性'),
    ('FILM.PROPERTY.CURE_PERFORMANCE', 'UV固化性(表面交联度)'),
    ('FILM.PROPERTY.CURE_PERFORMANCE', '表面交联度'),
    ('FILM.PROPERTY.ELONGATION', '延展率'),
    ('FILM.PROPERTY.ELONGATION', '拉伸率'),
    ('FILM.PROPERTY.ELONGATION', '拉伸率（PC膜，热拉）'),
    ('FILM.PROPERTY.ELONGATION', '拉伸率（PC）'),
    ('FILM.PROPERTY.GEL_TIME', '胶化时间'),
    ('FILM.PROPERTY.GLOSS', '光泽'),
    ('FILM.PROPERTY.GLOSS', '光泽变化'),
    ('FILM.PROPERTY.HARDNESS', '硬度'),
    ('FILM.PROPERTY.HARDNESS', '硬度（10μm厚，PET）'),
    ('FILM.PROPERTY.HARDNESS', '硬度（1KG负重,PET膜）'),
    ('FILM.PROPERTY.HAZE', '雾度'),
    ('FILM.PROPERTY.SURFACE_DRYNESS', '120度烘烤1小时后的表干性'),
    ('FILM.PROPERTY.SURFACE_DRYNESS', '干性'),
    ('FILM.PROPERTY.SURFACE_DRYNESS', '漆膜固化的表干性'),
    ('FILM.PROPERTY.SURFACE_DRYNESS', '表干性'),
    ('FILM.PROPERTY.THICKNESS', '膜厚'),
    ('FILM.PROPERTY.THICKNESS', '膜厚/μm'),
    ('FILM.PROPERTY.THICKNESS', '膜厚：'),
    ('FILM.PROPERTY.TRANSMITTANCE', '透过率'),
    ('FILM.PROPERTY.WARPAGE', 'UV后干膜室温放置12小时后的翘曲度（PET膜）'),
    ('FILM.PROPERTY.WARPAGE', 'UV后干膜翘曲度'),
    ('FILM.PROPERTY.WARPAGE', 'UV后干膜翘曲度（PET膜）'),
    ('FORMULA.ITEM.ACTUAL_KG', '实际投料'),
    ('FORMULA.ITEM.AMOUNT', '实验配方用量'),
    ('FORMULA.ITEM.AMOUNT', '用量'),
    ('FORMULA.ITEM.AMOUNT', '配方用量'),
    ('FORMULA.ITEM.BATCH_NO', '原料批号'),
    ('FORMULA.ITEM.MATERIAL_CODE', '物料编号'),
    ('FORMULA.ITEM.MATERIAL_NAME', '原料'),
    ('FORMULA.ITEM.MATERIAL_NAME', '原料名称'),
    ('FORMULA.ITEM.MATERIAL_NAME', '物料名称'),
    ('FORMULA.ITEM.RATIO', '比例'),
    ('FORMULA.ITEM.THEORETICAL_KG', '理论用量'),
    ('MATERIAL.PROPERTY.ACID_VALUE', '酸值'),
    ('MATERIAL.PROPERTY.AMINE_EQUIVALENT', '胺当量'),
    ('MATERIAL.PROPERTY.APPEARANCE', '原料外观'),
    ('MATERIAL.PROPERTY.APPEARANCE', '外观'),
    ('MATERIAL.PROPERTY.APPEARANCE', '树脂外观'),
    ('MATERIAL.PROPERTY.AVERAGE_PARTICLE_SIZE', '平均粒径'),
    ('MATERIAL.PROPERTY.D90_PARTICLE_SIZE', 'D90粒径'),
    ('MATERIAL.PROPERTY.EPOXY_EQUIVALENT', '环氧当量'),
    ('MATERIAL.PROPERTY.FINENESS', '细度'),
    ('MATERIAL.PROPERTY.HYDROXYL_VALUE', '羟值'),
    ('MATERIAL.PROPERTY.MOLECULAR_WEIGHT', '分子量（液相色谱）'),
    ('MATERIAL.PROPERTY.MOLECULAR_WEIGHT', '相对分子量'),
    ('MATERIAL.PROPERTY.PARTICLE_PEAK_COUNT', '粒径峰数'),
    ('MATERIAL.PROPERTY.PH', 'PH值'),
    ('MATERIAL.PROPERTY.PH', 'pH值'),
    ('MATERIAL.PROPERTY.REFRACTIVE_INDEX', '折光率'),
    ('MATERIAL.PROPERTY.REFRACTIVE_INDEX', '折射率'),
    ('MATERIAL.PROPERTY.SOLIDS_CONTENT', '固含'),
    ('MATERIAL.PROPERTY.SOLIDS_CONTENT', '实测固含（120℃×1h）'),
    ('MATERIAL.PROPERTY.SOLIDS_CONTENT', '实测固含（120度烘烤1小时）'),
    ('MATERIAL.PROPERTY.SOLVENT_COMPOSITION', '溶剂组成'),
    ('MATERIAL.PROPERTY.SPECIFIC_GRAVITY', '比重'),
    ('MATERIAL.PROPERTY.SPECIFIC_GRAVITY', '比重（25℃）'),
    ('MATERIAL.PROPERTY.VISCOSITY', '粘度'),
    ('MATERIAL.PROPERTY.VISCOSITY', '粘度（25℃）'),
    ('MATERIAL.PROPERTY.VISCOSITY', '粘度（估的值）'),
    ('MATERIAL.PROPERTY.WATER_CONTENT', '含水率'),
    ('PRODUCTION.ACTUAL_OUTPUT', '实际生产量'),
    ('PRODUCTION.BATCH_NO', '成品批号'),
    ('PRODUCTION.BATCH_NO', '生产批号'),
    ('PRODUCTION.MANUFACTURE_DATE', '生产日期'),
    ('PRODUCTION.OPERATION_PROCEDURE', '工艺程序'),
    ('PRODUCTION.OPERATION_PROCEDURE', '操作步骤'),
    ('PRODUCTION.OPERATION_PROCEDURE', '操作程序'),
    ('PRODUCTION.PRODUCT_NAME', '产品名'),
    ('PRODUCTION.PRODUCT_NAME', '产品名称'),
    ('PRODUCTION.QUANTITY', '批次数量'),
    ('PRODUCTION.QUANTITY', '生产数量'),
    ('QUALITY.NONCONFORMITY.CAUSE_AND_DISPOSITION', '原因分析及处理'),
    ('QUALITY.NONCONFORMITY.CAUSE_AND_DISPOSITION', '原因分析及处理（技术部）'),
    ('QUALITY.NONCONFORMITY.CORRECTIVE_PREVENTIVE_ACTION', '后续改进和预防措施'),
    ('QUALITY.NONCONFORMITY.CORRECTIVE_PREVENTIVE_ACTION', '改进和预防措施'),
    ('QUALITY.NONCONFORMITY.DESCRIPTION', '不合格描述'),
    ('QUALITY.NONCONFORMITY.DESCRIPTION', '不合格描述（品管部）'),
    ('QUALITY.NONCONFORMITY.FOLLOWUP_VERIFICATION', '跟踪效果确认'),
    ('QUALITY.NONCONFORMITY.MANAGER_DECISION', '总经理批示'),
    ('TEST.AMBIENT_HUMIDITY', '湿度'),
    ('TEST.AMBIENT_HUMIDITY', '环境湿度'),
    ('TEST.AMBIENT_TEMPERATURE', '温度'),
    ('TEST.AMBIENT_TEMPERATURE', '环境温度'),
    ('TEST.APPLICATION_METHOD', '施工方式'),
    ('TEST.CONCLUSION', '小结'),
    ('TEST.CONCLUSION', '结果/结论/小结'),
    ('TEST.CONCLUSION', '结果/结论/小结：'),
    ('TEST.CURE_CONDITION', '固化/测试条件'),
    ('TEST.CURE_CONDITION', '固化条件'),
    ('TEST.DATE', '实验日期'),
    ('TEST.DATE', '日期'),
    ('TEST.DATE', '测试日期'),
    ('TEST.EXPERIMENT_NO', '实验编号'),
    ('TEST.METHOD', '测试方法'),
    ('TEST.OPERATOR', '实验人'),
    ('TEST.OPERATOR', '实验人员'),
    ('TEST.OPERATOR', '测试人'),
    ('TEST.PLATFORM', '测试平台'),
    ('TEST.PROJECT_NAME', '实验项目'),
    ('TEST.PROJECT_NAME', '项目'),
    ('TEST.PURPOSE', '实验目的'),
    ('TEST.PURPOSE', '目的'),
    ('TEST.REMARK', '备注：'),
    ('TEST.RESULT.CONDITION', 'PU固化温度'),
    ('TEST.RESULT.CONDITION', 'UV固化能量'),
    ('TEST.RESULT.CONDITION', '条件'),
    ('TEST.RESULT.CONDITION', '水煮时间'),
    ('TEST.RESULT.CONDITION', '水煮温度'),
    ('TEST.RESULT.CONDITION', '测试条件'),
    ('TEST.RESULT.ITEM_NAME', '性能项目'),
    ('TEST.RESULT.ITEM_NAME', '检测项目'),
    ('TEST.RESULT.ITEM_NAME', '测试项目'),
    ('TEST.RESULT.MEDIUM', '介质'),
    ('TEST.RESULT.MEDIUM', '测试介质'),
    ('TEST.RESULT.REMARK', '结果备注'),
    ('TEST.RESULT.REPLICATE_NO', '平行样编号'),
    ('TEST.RESULT.REPLICATE_NO', '板号'),
    ('TEST.RESULT.SUBSTRATE', '基材'),
    ('TEST.RESULT.SUBSTRATE', '测试基材'),
    ('TEST.RESULT.SUBSTRATE', '素材类型'),
    ('TEST.RESULT.TIMEPOINT', '时间点'),
    ('TEST.RESULT.TIMEPOINT', '测试时点'),
    ('TEST.RESULT.UNIT', '单位'),
    ('TEST.RESULT.VALUE', 'PH=2.6人工汗测试'),
    ('TEST.RESULT.VALUE', 'PH=8.8人工汗测试'),
    ('TEST.RESULT.VALUE', '丰满度'),
    ('TEST.RESULT.VALUE', '其他性能测试'),
    ('TEST.RESULT.VALUE', '双杰笔'),
    ('TEST.RESULT.VALUE', '喷板（配方）'),
    ('TEST.RESULT.VALUE', '弯曲韧性'),
    ('TEST.RESULT.VALUE', '弹性'),
    ('TEST.RESULT.VALUE', '手感'),
    ('TEST.RESULT.VALUE', '指甲刮伤'),
    ('TEST.RESULT.VALUE', '检测结果'),
    ('TEST.RESULT.VALUE', '水接触角'),
    ('TEST.RESULT.VALUE', '油接触角'),
    ('TEST.RESULT.VALUE', '流平'),
    ('TEST.RESULT.VALUE', '测试结果'),
    ('TEST.RESULT.VALUE', '滑动系数'),
    ('TEST.RESULT.VALUE', '漆膜气味'),
    ('TEST.RESULT.VALUE', '结果'),
    ('TEST.RESULT.VALUE', '缩孔'),
    ('TEST.RESULT.VALUE', '耐油笔'),
    ('TEST.RESULT.VALUE', '耐芥末酱测试'),
    ('TEST.RESULT.VALUE', '耐防晒霜测试'),
    ('TEST.RESULT.VALUE', '膜强'),
    ('TEST.RESULT.VALUE', '蓝色油性笔'),
    ('TEST.RESULT.VALUE', '颗粒状态'),
    ('TEST.RESULT.VALUE', '黑色油性笔'),
    ('TEST.SAMPLE_CODE', '树脂编号'),
    ('TEST.SAMPLE_CODE', '样品编号'),
    ('TEST.SAMPLE_DESCRIPTION', '引发剂'),
    ('TEST.SAMPLE_DESCRIPTION', '树脂内容'),
    ('TEST.SAMPLE_DESCRIPTION', '样品内容'),
    ('TEST.SAMPLE_MATERIAL', '测试树脂样品或配方'),
    ('TEST.SAMPLE_MATERIAL', '测试用素材'),
    ('TEST.SAMPLE_MATERIAL', '素材'),
    ('TEST.SUPPLEMENTARY_NOTE', '补充说明'),
    ('WORKFLOW.APPROVAL.ITEM.COMMENT', '处理意见'),
    ('WORKFLOW.APPROVAL.ITEM.COMMENT', '审批意见'),
    ('WORKFLOW.APPROVAL.ITEM.ROLE', '处理部门'),
    ('WORKFLOW.APPROVAL.ITEM.ROLE', '审批角色'),
    ('WORKFLOW.APPROVAL.ITEM.ROLE', '确认部门'),
    ('WORKFLOW.APPROVAL.ITEM.SIGNATURE', '签名'),
    ('WORKFLOW.APPROVAL.ITEM.SIGNATURE', '签名/日期'),
    ('WORKFLOW.APPROVAL.ITEM.SIGNED_AT', '审批日期'),
    ('WORKFLOW.APPROVAL.ITEM.SIGNED_AT', '签署日期'),
    ('WORKFLOW.APPROVED_BY', '核准'),
    ('WORKFLOW.APPROVED_BY', '核准人'),
    ('WORKFLOW.COMPLETED_BY', '完成人'),
    ('WORKFLOW.PREPARED_BY', '制单人'),
    ('WORKFLOW.PREPARED_BY', '制定'),
    ('WORKFLOW.REVIEWED_BY', '审核'),
    ('WORKFLOW.REVIEWED_BY', '审核人'),
    ('WORKFLOW.SUPERVISED_BY', '监管人')
)
INSERT INTO tpl.standard_field_alias (id, dictionary_id, alias, normalized_alias)
SELECT
  gen_random_uuid(), d.id, a.alias,
  lower(regexp_replace(replace(a.alias, '：', ':'), '[[:space:]:：]', '', 'g'))
FROM alias_seed a
       JOIN tpl.standard_field_dictionary d
            ON d.dictionary_code = a.dictionary_code
              AND d.version_no = 1
  ON CONFLICT (dictionary_id, normalized_alias) DO NOTHING;



ALTER TABLE tpl.template
    ADD CONSTRAINT fk_template_current_published
    FOREIGN KEY (current_published_version_id) REFERENCES tpl.template_version(id);

ALTER TABLE tpl.recognition_suggestion
    ADD CONSTRAINT recognition_suggestion_recognition_run_id_fkey
        FOREIGN KEY (recognition_run_id) REFERENCES tpl.recognition_run(id) ON DELETE SET NULL,
    ADD CONSTRAINT recognition_suggestion_recognition_call_id_fkey
        FOREIGN KEY (recognition_call_id) REFERENCES tpl.recognition_call(id) ON DELETE SET NULL,
    ADD CONSTRAINT recognition_suggestion_parent_suggestion_id_fkey
        FOREIGN KEY (parent_suggestion_id) REFERENCES tpl.recognition_suggestion(id) ON DELETE SET NULL;
