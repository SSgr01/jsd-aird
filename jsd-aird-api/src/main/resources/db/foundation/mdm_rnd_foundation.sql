-- R01 MDM/RND foundation reconstructed from the active repositories.
-- This file is intentionally outside db/migration. Run it only after
-- r01-preflight classifies the target as EMPTY_FOUNDATION.
-- Do not add IF NOT EXISTS: a partial foundation must fail visibly.

CREATE SEQUENCE rnd.experiment_no_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE rnd.comprehensive_report_no_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE mdm.business_partner (
    id uuid PRIMARY KEY,
    partner_code varchar(32) NOT NULL UNIQUE,
    name varchar(200) NOT NULL,
    normalized_name varchar(200) NOT NULL UNIQUE,
    industry varchar(100), address varchar(500), status varchar(20) NOT NULL,
    remark varchar(1000), customer_level varchar(50), cooperation_status varchar(50), main_business varchar(500),
    custom_fields jsonb NOT NULL DEFAULT '{}'::jsonb,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(160) NOT NULL, updated_by varchar(160) NOT NULL,
    CONSTRAINT business_partner_custom_fields_object CHECK (jsonb_typeof(custom_fields) = 'object'),
    CONSTRAINT business_partner_status_check CHECK (status IN ('ACTIVE','INACTIVE'))
);

CREATE TABLE mdm.partner_contact (
    id uuid PRIMARY KEY,
    partner_id uuid NOT NULL REFERENCES mdm.business_partner(id) ON DELETE CASCADE,
    name varchar(100), department varchar(100), title varchar(100), phone varchar(50), email varchar(200),
    status varchar(20) NOT NULL DEFAULT 'ACTIVE', assigned_project_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    members varchar(1000) NOT NULL DEFAULT '', wechat varchar(200), custom_fields jsonb NOT NULL DEFAULT '{}'::jsonb,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(160) NOT NULL, updated_by varchar(160) NOT NULL,
    CONSTRAINT partner_contact_projects_array CHECK (jsonb_typeof(assigned_project_ids) = 'array'),
    CONSTRAINT partner_contact_custom_fields_object CHECK (jsonb_typeof(custom_fields) = 'object'),
    CONSTRAINT partner_contact_status_check CHECK (status IN ('ACTIVE','INACTIVE'))
);

CREATE TABLE mdm.communication_record (
    id uuid PRIMARY KEY, record_code varchar(32) NOT NULL UNIQUE, name varchar(200),
    partner_id uuid NOT NULL REFERENCES mdm.business_partner(id), communicated_at timestamptz NOT NULL,
    internal_participants varchar(500), communication_method varchar(30) NOT NULL, content text NOT NULL, status varchar(30) NOT NULL,
    custom_fields jsonb NOT NULL DEFAULT '{}'::jsonb, version bigint NOT NULL DEFAULT 0,
    deleted boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(160) NOT NULL, updated_by varchar(160),
    CONSTRAINT communication_custom_fields_object CHECK (jsonb_typeof(custom_fields) = 'object'),
    CONSTRAINT communication_status_check CHECK (status IN ('OPEN','FOLLOWING','CLOSED'))
);

CREATE TABLE mdm.customer_requirement (
    id uuid PRIMARY KEY, requirement_code varchar(32) NOT NULL UNIQUE,
    partner_id uuid REFERENCES mdm.business_partner(id), title varchar(200) NOT NULL,
    raw_requirement text, urgency varchar(20), raised_at date, delivery_date date,
    status varchar(30) NOT NULL, custom_status_name varchar(50), project_id uuid,
    assigned_project_ids jsonb NOT NULL DEFAULT '[]'::jsonb, custom_fields jsonb NOT NULL DEFAULT '{}'::jsonb,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(160) NOT NULL, updated_by varchar(160) NOT NULL,
    CONSTRAINT requirement_projects_array CHECK (jsonb_typeof(assigned_project_ids) = 'array'),
    CONSTRAINT requirement_custom_fields_object CHECK (jsonb_typeof(custom_fields) = 'object'),
    CONSTRAINT requirement_status_check CHECK (status IN ('DRAFT','CONFIRMED','IN_PROJECT','COMPLETED','CANCELLED'))
);

CREATE TABLE mdm.material (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), code varchar(64) NOT NULL UNIQUE, name varchar(200) NOT NULL,
    category varchar(50) NOT NULL, source_category varchar(50) NOT NULL, source_module varchar(50) NOT NULL, stage varchar(30),
    contact_person varchar(50), status varchar(30) NOT NULL DEFAULT 'DRAFT', description text,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(), created_by varchar(160) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(), updated_by varchar(160) NOT NULL
);

CREATE TABLE mdm.project (
    id uuid PRIMARY KEY, project_code varchar(64) NOT NULL, name varchar(300) NOT NULL,
    partner_id uuid REFERENCES mdm.business_partner(id), partner_name varchar(300), owner varchar(100),
    start_date date, end_date date, priority varchar(20) NOT NULL DEFAULT 'MEDIUM', status varchar(30) NOT NULL DEFAULT 'NOT_STARTED', team_size integer NOT NULL DEFAULT 0,
    background text, custom_fields jsonb NOT NULL DEFAULT '{}'::jsonb, team_members jsonb NOT NULL DEFAULT '[]'::jsonb,
    version bigint NOT NULL DEFAULT 0, deleted boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(160) NOT NULL, updated_by varchar(160) NOT NULL,
    CONSTRAINT project_custom_fields_object CHECK (jsonb_typeof(custom_fields) = 'object'),
    CONSTRAINT project_team_members_array CHECK (jsonb_typeof(team_members) = 'array'),
    CONSTRAINT project_priority_check CHECK (priority IN ('HIGH','MEDIUM','LOW')),
    CONSTRAINT project_status_check CHECK (status IN ('NOT_STARTED','IN_PROGRESS','PAUSED','COMPLETED','CANCELLED')),
    CONSTRAINT project_team_size_check CHECK (team_size >= 0)
);
CREATE UNIQUE INDEX uq_project_code_active ON mdm.project(project_code) WHERE deleted = false;
ALTER TABLE mdm.customer_requirement ADD CONSTRAINT customer_requirement_project_fk FOREIGN KEY (project_id) REFERENCES mdm.project(id);

CREATE TABLE mdm.partner_contact_project (
    id uuid PRIMARY KEY, contact_id uuid NOT NULL REFERENCES mdm.partner_contact(id) ON DELETE CASCADE,
    project_id uuid NOT NULL REFERENCES mdm.project(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(160) NOT NULL, updated_by varchar(160) NOT NULL,
    UNIQUE (contact_id, project_id)
);

CREATE TABLE mdm.project_stage (
    id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES mdm.project(id) ON DELETE CASCADE,
    stage_code varchar(40), name varchar(120) NOT NULL, order_no integer NOT NULL CHECK (order_no > 0),
    status varchar(30) NOT NULL DEFAULT 'PENDING', owner varchar(100), description text,
    planned_start date, planned_end date, actual_start timestamptz, actual_end timestamptz,
    version bigint NOT NULL DEFAULT 0, deleted boolean NOT NULL DEFAULT false,
    deleted_at timestamptz, deleted_by varchar(160),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(160) NOT NULL, updated_by varchar(160) NOT NULL
);
CREATE UNIQUE INDEX uq_project_stage_order_active ON mdm.project_stage(project_id, order_no) WHERE deleted = false;
CREATE UNIQUE INDEX uq_project_stage_name_active ON mdm.project_stage(project_id, lower(name)) WHERE deleted = false;

CREATE TABLE mdm.project_task (
    id uuid PRIMARY KEY, task_code varchar(80) NOT NULL, project_id uuid NOT NULL REFERENCES mdm.project(id) ON DELETE CASCADE,
    stage_id uuid NOT NULL REFERENCES mdm.project_stage(id) ON DELETE CASCADE,
    name varchar(300) NOT NULL, owner varchar(100), priority varchar(20) NOT NULL DEFAULT 'MEDIUM', planned_date date, status varchar(30) NOT NULL DEFAULT 'PENDING',
    version bigint NOT NULL DEFAULT 0, deleted boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(160) NOT NULL, updated_by varchar(160) NOT NULL
);
CREATE UNIQUE INDEX uq_project_task_code_active ON mdm.project_task(project_id, task_code) WHERE deleted = false;

CREATE TABLE mdm.project_material (
    id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES mdm.project(id) ON DELETE CASCADE,
    material_id uuid NOT NULL REFERENCES mdm.material(id),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(160) NOT NULL, updated_by varchar(160) NOT NULL,
    UNIQUE (project_id, material_id)
);

CREATE TABLE mdm.meeting_minutes (
    id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES mdm.project(id) ON DELETE CASCADE,
    title varchar(200) NOT NULL, attendees jsonb NOT NULL DEFAULT '[]'::jsonb, summary text,
    occurred_at timestamptz NOT NULL, archived_to_kb boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(), created_by varchar(160) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(), updated_by varchar(160),
    CONSTRAINT meeting_attendees_array CHECK (jsonb_typeof(attendees) = 'array')
);

CREATE TABLE mdm.project_document (
    id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES mdm.project(id) ON DELETE CASCADE,
    title varchar(260) NOT NULL, format varchar(16) NOT NULL, source varchar(16) NOT NULL DEFAULT 'BLANK', status varchar(24) NOT NULL DEFAULT 'DRAFT',
    template_id uuid REFERENCES tpl.template(id), template_version_id uuid REFERENCES tpl.template_version(id),
    file_object_id uuid REFERENCES ops.file_object(id), current_version_id uuid,
    content_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb, content_schema jsonb NOT NULL DEFAULT '{}'::jsonb,
    content_mapping jsonb NOT NULL DEFAULT '[]'::jsonb, content_data jsonb NOT NULL DEFAULT '{}'::jsonb,
    content_recognition jsonb NOT NULL DEFAULT '{}'::jsonb, content_structure jsonb NOT NULL DEFAULT '{}'::jsonb,
    version bigint NOT NULL DEFAULT 0, deleted boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(), created_by varchar(160) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(), updated_by varchar(160) NOT NULL
);

CREATE TABLE mdm.project_document_version (
    id uuid PRIMARY KEY, document_id uuid NOT NULL REFERENCES mdm.project_document(id) ON DELETE CASCADE,
    version_no integer NOT NULL, content_snapshot jsonb, content_schema jsonb NOT NULL DEFAULT '{}'::jsonb,
    content_mapping jsonb NOT NULL DEFAULT '[]'::jsonb, content_data jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(32) NOT NULL DEFAULT 'DRAFT', content_jsonb jsonb DEFAULT '{}'::jsonb,
    snapshot_reason varchar(255), template_version_id uuid REFERENCES tpl.template_version(id),
    template_snapshot_hash varchar(64), template_snapshot_jsonb jsonb,
    submitted_at timestamptz, published_at timestamptz,
    created_by varchar(160) NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (document_id, version_no)
);
-- The active repository inserts the document head before its first version row.
-- Keep current_version_id as a logical pointer, matching the supplied test schema,
-- instead of adding an immediate circular FK that would break that transaction.

CREATE TABLE mdm.project_document_review (
    id uuid PRIMARY KEY, document_id uuid NOT NULL REFERENCES mdm.project_document(id) ON DELETE CASCADE,
    document_version_id uuid REFERENCES mdm.project_document_version(id), action varchar(40) NOT NULL, comment text,
    operator_id uuid, operator_name varchar(160), created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE mdm.project_document_audit (
    id uuid PRIMARY KEY, document_id uuid NOT NULL REFERENCES mdm.project_document(id) ON DELETE CASCADE,
    document_version_id uuid REFERENCES mdm.project_document_version(id), action varchar(80) NOT NULL,
    before_jsonb jsonb, after_jsonb jsonb, operator_id uuid, operator_name varchar(160), trace_id varchar(100),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE rnd.experiment_category (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id),
    code varchar(80) NOT NULL, name varchar(160) NOT NULL, description text, active boolean NOT NULL DEFAULT true,
    revision bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id)
);
CREATE UNIQUE INDEX uq_experiment_category_active_name ON rnd.experiment_category(organization_id, lower(name)) WHERE active;

CREATE TABLE rnd.experiment (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), experiment_no varchar(80) NOT NULL,
    legacy_experiment_no varchar(120), title varchar(300) NOT NULL, category_id uuid, category_name varchar(100), source_type varchar(30) NOT NULL,
    status varchar(30) NOT NULL, classification_status varchar(30) NOT NULL DEFAULT 'CLASSIFIED',
    project_id uuid REFERENCES mdm.project(id), stage_id uuid REFERENCES mdm.project_stage(id),
    task_id uuid REFERENCES mdm.project_task(id), owner_id uuid, owner_name varchar(100), experiment_date date,
    current_version_id uuid, revision bigint NOT NULL DEFAULT 0, void_reason varchar(1000), source_file_id uuid REFERENCES ops.file_object(id),
    deleted boolean NOT NULL DEFAULT false, created_by uuid NOT NULL REFERENCES iam.app_user(id),
    updated_by uuid NOT NULL REFERENCES iam.app_user(id), created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id),
    CONSTRAINT experiment_source_type_check CHECK (source_type IN ('PROJECT','TEMPLATE','MANUAL','EXCEL_IMPORT','OCR_IMPORT')),
    CONSTRAINT experiment_status_check CHECK (status IN ('DRAFT','PENDING','IN_PROGRESS','PENDING_REVIEW','RETURNED','COMPLETED','VOIDED'))
);
CREATE UNIQUE INDEX uq_experiment_no_active ON rnd.experiment(organization_id, experiment_no) WHERE deleted = false;

CREATE TABLE rnd.experiment_version (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id),
    experiment_id uuid NOT NULL, version_no integer NOT NULL, status varchar(32) NOT NULL,
    template_version_id uuid REFERENCES tpl.template_version(id),
    template_snapshot_hash char(64), template_snapshot_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    edit_model_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb, revision_reason text,
    submitted_at timestamptz, published_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    UNIQUE (organization_id, id), UNIQUE (organization_id, experiment_id, version_no),
    FOREIGN KEY (organization_id, experiment_id) REFERENCES rnd.experiment(organization_id, id) ON DELETE CASCADE,
    CONSTRAINT experiment_version_status_check CHECK (status IN ('DRAFT','PENDING','IN_PROGRESS','PENDING_REVIEW','RETURNED','COMPLETED','VOIDED'))
);
ALTER TABLE rnd.experiment ADD CONSTRAINT experiment_current_version_fk FOREIGN KEY (organization_id, current_version_id) REFERENCES rnd.experiment_version(organization_id, id);
ALTER TABLE rnd.experiment ADD CONSTRAINT experiment_category_fk FOREIGN KEY (organization_id, category_id) REFERENCES rnd.experiment_category(organization_id, id);

CREATE TABLE rnd.experiment_review (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), experiment_id uuid NOT NULL,
    experiment_version_id uuid NOT NULL, action varchar(30) NOT NULL, comment varchar(2000), operator_id uuid NOT NULL, operator_name varchar(100),
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id, experiment_id) REFERENCES rnd.experiment(organization_id, id) ON DELETE CASCADE,
    FOREIGN KEY (organization_id, experiment_version_id) REFERENCES rnd.experiment_version(organization_id, id)
);
CREATE TABLE rnd.experiment_audit (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), experiment_id uuid NOT NULL,
    experiment_version_id uuid, action varchar(80) NOT NULL, before_jsonb jsonb, after_jsonb jsonb,
    operator_id uuid, operator_name varchar(160), created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id, experiment_id) REFERENCES rnd.experiment(organization_id, id) ON DELETE CASCADE,
    FOREIGN KEY (organization_id, experiment_version_id) REFERENCES rnd.experiment_version(organization_id, id)
);
CREATE TABLE rnd.experiment_attachment (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), experiment_id uuid NOT NULL,
    experiment_version_id uuid NOT NULL, file_id uuid NOT NULL REFERENCES ops.file_object(id), file_version_id uuid,
    attachment_type varchar(40) NOT NULL, section_key varchar(160), file_name varchar(500), description text,
    created_by uuid NOT NULL REFERENCES iam.app_user(id), created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id, experiment_id) REFERENCES rnd.experiment(organization_id, id) ON DELETE CASCADE,
    FOREIGN KEY (organization_id, experiment_version_id) REFERENCES rnd.experiment_version(organization_id, id)
);
CREATE TABLE rnd.experiment_outbox (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), aggregate_id uuid NOT NULL,
    event_type varchar(100) NOT NULL, payload_jsonb jsonb NOT NULL, status varchar(24) NOT NULL DEFAULT 'PENDING',
    attempts integer NOT NULL DEFAULT 0, available_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz,
    FOREIGN KEY (organization_id, aggregate_id) REFERENCES rnd.experiment(organization_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_experiment_outbox_pending ON rnd.experiment_outbox(status, available_at, created_at);

CREATE TABLE rnd.experiment_import_job (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id),
    source_file_id uuid NOT NULL REFERENCES ops.file_object(id), source_file_name varchar(500) NOT NULL,
    source_sha256 char(64) NOT NULL, source_format varchar(32) NOT NULL, category_name varchar(160),
    project_id uuid REFERENCES mdm.project(id), stage_id uuid REFERENCES mdm.project_stage(id), task_id uuid REFERENCES mdm.project_task(id),
    visibility varchar(24) NOT NULL DEFAULT 'ALL', status varchar(30) NOT NULL, experiment_id uuid,
    duplicate_override boolean NOT NULL DEFAULT false, duplicate_reason varchar(1000),
    parse_result_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb, error_message text,
    created_by uuid NOT NULL REFERENCES iam.app_user(id), created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id), FOREIGN KEY (organization_id, experiment_id) REFERENCES rnd.experiment(organization_id, id)
);

CREATE TABLE rnd.research_test_record (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), record_type varchar(24) NOT NULL,
    business_no varchar(80) NOT NULL, name varchar(240) NOT NULL, category varchar(160), applicable_scope text,
    owner_id uuid, owner_name varchar(160), business_date date, document_format varchar(32) NOT NULL, source_type varchar(32) NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'DRAFT', visibility varchar(32) NOT NULL DEFAULT 'ALL', project_id uuid REFERENCES mdm.project(id),
    stage_id uuid REFERENCES mdm.project_stage(id), task_id uuid REFERENCES mdm.project_task(id), source_file_id uuid REFERENCES ops.file_object(id),
    current_version_id uuid, lock_version bigint NOT NULL DEFAULT 0, deleted boolean NOT NULL DEFAULT false,
    created_by uuid NOT NULL REFERENCES iam.app_user(id), updated_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), UNIQUE (organization_id, id),
    CONSTRAINT research_test_record_type_check CHECK (record_type IN ('REPORT','STANDARD')),
    CONSTRAINT research_test_record_document_format_check CHECK (document_format IN ('WORD','EXCEL')),
    CONSTRAINT research_test_record_source_type_check CHECK (source_type IN ('BLANK','TEMPLATE','UPLOAD','GENERATED','IMPORT')),
    CONSTRAINT research_test_record_status_check CHECK (status IN ('DRAFT','PENDING_REVIEW','RETURNED','PUBLISHED','ARCHIVED')),
    CONSTRAINT research_test_record_visibility_check CHECK (visibility IN ('ALL','RND','PROJECT'))
);
CREATE UNIQUE INDEX uq_research_test_business_no_active ON rnd.research_test_record(organization_id, record_type, business_no) WHERE deleted = false;

CREATE TABLE rnd.research_test_version (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), record_id uuid NOT NULL,
    version_no integer NOT NULL, status varchar(32) NOT NULL, template_version_id uuid REFERENCES tpl.template_version(id),
    template_snapshot_hash char(64), template_snapshot_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    edit_model_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb, member_snapshot_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    effective_from date, effective_to date, change_summary text, submitted_at timestamptz, published_at timestamptz,
    created_by uuid NOT NULL REFERENCES iam.app_user(id), created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id), UNIQUE (organization_id, record_id, version_no),
    FOREIGN KEY (organization_id, record_id) REFERENCES rnd.research_test_record(organization_id, id) ON DELETE CASCADE,
    CONSTRAINT research_test_version_status_check CHECK (status IN ('DRAFT','PENDING_REVIEW','RETURNED','PUBLISHED','ARCHIVED'))
);
ALTER TABLE rnd.research_test_record ADD CONSTRAINT research_test_current_version_fk FOREIGN KEY (organization_id, current_version_id) REFERENCES rnd.research_test_version(organization_id, id);

CREATE TABLE rnd.research_test_review (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), record_id uuid NOT NULL, version_id uuid NOT NULL,
    action varchar(40) NOT NULL, comment text, operator_id uuid NOT NULL, operator_name varchar(160) NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id, record_id) REFERENCES rnd.research_test_record(organization_id, id) ON DELETE CASCADE,
    FOREIGN KEY (organization_id, version_id) REFERENCES rnd.research_test_version(organization_id, id)
);
CREATE TABLE rnd.research_test_audit (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), record_id uuid NOT NULL, version_id uuid,
    action varchar(80) NOT NULL, before_jsonb jsonb, after_jsonb jsonb, operator_id uuid NOT NULL, operator_name varchar(160) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id, record_id) REFERENCES rnd.research_test_record(organization_id, id) ON DELETE CASCADE,
    FOREIGN KEY (organization_id, version_id) REFERENCES rnd.research_test_version(organization_id, id)
);
CREATE TABLE rnd.research_test_upload (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), record_id uuid NOT NULL,
    file_id uuid NOT NULL REFERENCES ops.file_object(id), original_name varchar(500) NOT NULL, content_type varchar(200),
    file_size bigint NOT NULL, sha256 varchar(64), status varchar(24) NOT NULL DEFAULT 'DRAFT_CREATED',
    deleted boolean NOT NULL DEFAULT false, created_by uuid NOT NULL REFERENCES iam.app_user(id), created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id, record_id) REFERENCES rnd.research_test_record(organization_id, id)
);
CREATE UNIQUE INDEX uq_research_test_upload_content ON rnd.research_test_upload(organization_id, sha256) WHERE sha256 IS NOT NULL AND deleted = false;

CREATE OR REPLACE FUNCTION mdm.project_stage_task_count(stage_uuid uuid) RETURNS bigint LANGUAGE sql STABLE AS
$$ SELECT count(*) FROM mdm.project_task WHERE stage_id = stage_uuid AND deleted = false $$;
CREATE OR REPLACE FUNCTION mdm.project_stage_open_task_count(stage_uuid uuid) RETURNS bigint LANGUAGE sql STABLE AS
$$ SELECT count(*) FROM mdm.project_task WHERE stage_id = stage_uuid AND deleted = false AND status <> 'COMPLETED' $$;

CREATE INDEX idx_partner_contact_partner ON mdm.partner_contact(partner_id, status, created_at);
CREATE INDEX idx_communication_partner ON mdm.communication_record(partner_id, communicated_at DESC) WHERE deleted = false;
CREATE INDEX idx_requirement_partner ON mdm.customer_requirement(partner_id, updated_at DESC);
CREATE INDEX idx_customer_requirement_assigned_project_ids ON mdm.customer_requirement USING gin(assigned_project_ids);
CREATE INDEX idx_requirement_project ON mdm.customer_requirement(project_id);
CREATE INDEX idx_partner_contact_project ON mdm.partner_contact_project(project_id);
CREATE INDEX idx_project_updated ON mdm.project(updated_at DESC) WHERE deleted = false;
CREATE INDEX idx_project_document_project ON mdm.project_document(project_id);
CREATE INDEX idx_rnd_project_document_status ON mdm.project_document(project_id, status) WHERE deleted = false;
CREATE INDEX idx_rnd_project_document_template ON mdm.project_document(template_id) WHERE template_id IS NOT NULL;
CREATE INDEX idx_doc_version_document ON mdm.project_document_version(document_id, version_no DESC);
CREATE INDEX idx_doc_review_document ON mdm.project_document_review(document_id);
CREATE INDEX idx_doc_audit_document ON mdm.project_document_audit(document_id, created_at DESC);
CREATE INDEX idx_project_stage_code ON mdm.project_stage(stage_code) WHERE deleted = false;
CREATE INDEX idx_project_stage_owner ON mdm.project_stage(owner) WHERE deleted = false;
CREATE INDEX idx_project_stage_project_status_order ON mdm.project_stage(project_id, status, order_no) WHERE deleted = false;
CREATE INDEX idx_project_task_stage ON mdm.project_task(stage_id, status) WHERE deleted = false;
CREATE INDEX idx_meeting_minutes_project ON mdm.meeting_minutes(project_id, occurred_at DESC);
CREATE INDEX idx_eln_list ON rnd.experiment(organization_id, status, updated_at DESC) WHERE deleted = false;
CREATE INDEX idx_eln_project ON rnd.experiment(organization_id, project_id, stage_id, task_id) WHERE deleted = false;
CREATE INDEX idx_eln_audit ON rnd.experiment_audit(experiment_id, created_at DESC);
CREATE INDEX idx_eln_version_history ON rnd.experiment_version(experiment_id, version_no DESC);
CREATE INDEX idx_rnd_experiment_source_file ON rnd.experiment(organization_id, source_file_id) WHERE source_file_id IS NOT NULL;
CREATE INDEX idx_experiment_import_job_status ON rnd.experiment_import_job(organization_id, status, created_at DESC);
CREATE INDEX idx_experiment_import_job_context ON rnd.experiment_import_job(organization_id, project_id, stage_id, task_id, created_at DESC);
CREATE INDEX idx_research_test_list ON rnd.research_test_record(organization_id, record_type, updated_at DESC) WHERE deleted = false;
CREATE INDEX idx_research_test_project ON rnd.research_test_record(organization_id, project_id, stage_id) WHERE deleted = false;
CREATE INDEX idx_research_test_uploads ON rnd.research_test_upload(organization_id, created_at DESC) WHERE deleted = false;
