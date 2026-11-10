-- 报价模块表结构（合并自 V99~V105 的 DDL 部分）：
-- 报价记录/明细/导入任务、项目关联列、联系人/价格规范化结构、工作区版本、任务计划完成时间、报价审核。

-- 报价记录：一张上传的报价单文件归档为一条客户报价记录，明细逐行保存。
CREATE TABLE rnd.quotation_record (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    quotation_no varchar(80) NOT NULL,
    customer_name varchar(300) NOT NULL,
    related_company varchar(300),
    contact_name varchar(300),
    contact_title varchar(160),
    phone varchar(300),
    fax varchar(120),
    email varchar(300),
    address_line1 varchar(600),
    address_line2 varchar(600),
    website varchar(300),
    note text,
    source_file_id uuid REFERENCES ops.file_object(id),
    source_file_name varchar(500),
    source_format varchar(32),
    deleted boolean NOT NULL DEFAULT false,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id)
);

CREATE UNIQUE INDEX uq_quotation_no_active
    ON rnd.quotation_record (organization_id, quotation_no) WHERE deleted = false;
CREATE INDEX idx_quotation_record_list
    ON rnd.quotation_record (organization_id, created_at DESC) WHERE deleted = false;
CREATE INDEX idx_quotation_record_customer
    ON rnd.quotation_record (organization_id, lower(customer_name)) WHERE deleted = false;

CREATE TABLE rnd.quotation_item (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    quotation_id uuid NOT NULL,
    line_no integer NOT NULL,
    product_name varchar(300) NOT NULL,
    unit_price varchar(160),
    specification varchar(200),
    quote_date date,
    customer_code varchar(120),
    note text,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, quotation_id)
        REFERENCES rnd.quotation_record (organization_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_quotation_item_quotation
    ON rnd.quotation_item (organization_id, quotation_id, line_no);
CREATE INDEX idx_quotation_item_product
    ON rnd.quotation_item (organization_id, lower(product_name));

CREATE TABLE rnd.quotation_import_job (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    source_file_id uuid NOT NULL REFERENCES ops.file_object(id),
    source_file_name varchar(500) NOT NULL,
    source_sha256 char(64) NOT NULL,
    source_format varchar(32) NOT NULL,
    status varchar(30) NOT NULL,
    quotation_id uuid,
    error_message text,
    parse_result_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, quotation_id)
        REFERENCES rnd.quotation_record (organization_id, id)
);

CREATE INDEX idx_quotation_import_job_org
    ON rnd.quotation_import_job (organization_id, created_at DESC);
CREATE INDEX idx_quotation_import_job_hash
    ON rnd.quotation_import_job (organization_id, source_sha256);

ALTER TABLE rnd.quotation_import_job
    ADD COLUMN template_version_id uuid REFERENCES tpl.template_version(id),
    ADD COLUMN project_id uuid,
    ADD COLUMN project_name varchar(300),
    ADD COLUMN stage_id uuid,
    ADD COLUMN stage_name varchar(300),
    ADD COLUMN task_id uuid,
    ADD COLUMN task_name varchar(300),
    ADD COLUMN visibility varchar(20) NOT NULL DEFAULT 'ALL';

ALTER TABLE rnd.quotation_record
    ADD COLUMN template_version_id uuid REFERENCES tpl.template_version(id),
    ADD COLUMN project_id uuid,
    ADD COLUMN project_name varchar(300),
    ADD COLUMN stage_id uuid,
    ADD COLUMN stage_name varchar(300),
    ADD COLUMN task_id uuid,
    ADD COLUMN task_name varchar(300),
    ADD COLUMN visibility varchar(20) NOT NULL DEFAULT 'ALL';

-- Keep the existing record and item columns as import/display snapshots. Contacts
-- and price options are the normalized values; raw item text remains lossless.
CREATE TABLE rnd.quotation_contact (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    quotation_id uuid NOT NULL,
    position integer NOT NULL CHECK (position > 0),
    source_column integer NOT NULL CHECK (source_column > 0),
    contact_name varchar(300),
    contact_title varchar(160),
    phone varchar(300),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, quotation_id, position),
    FOREIGN KEY (organization_id, quotation_id)
        REFERENCES rnd.quotation_record (organization_id, id) ON DELETE CASCADE
);

ALTER TABLE rnd.quotation_item
    ADD COLUMN source_row_no integer;

CREATE TABLE rnd.quotation_item_price (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    quotation_item_id uuid NOT NULL,
    position integer NOT NULL CHECK (position > 0),
    unit_price numeric(20, 6) NOT NULL CHECK (unit_price >= 0),
    specification varchar(200),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, quotation_item_id, position),
    FOREIGN KEY (organization_id, quotation_item_id)
        REFERENCES rnd.quotation_item (organization_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_quotation_contact_record
    ON rnd.quotation_contact (organization_id, quotation_id, position);
CREATE INDEX idx_quotation_item_price_item
    ON rnd.quotation_item_price (organization_id, quotation_item_id, position);

-- Existing rows already have a combined contact snapshot. Preserve it as one
-- contact until the source file is re-imported with column-level parsing.
INSERT INTO rnd.quotation_contact
    (id, organization_id, quotation_id, position, source_column,
     contact_name, contact_title, phone)
SELECT gen_random_uuid(), organization_id, id, 1, 2,
       contact_name, contact_title, phone
FROM rnd.quotation_record
WHERE contact_name IS NOT NULL OR contact_title IS NOT NULL OR phone IS NOT NULL;

ALTER TABLE rnd.quotation_record
    ADD COLUMN workbook_snapshot jsonb,
    ADD COLUMN version_no integer NOT NULL DEFAULT 1;

CREATE TABLE rnd.quotation_version (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    quotation_id uuid NOT NULL,
    version_no integer NOT NULL,
    detail_jsonb jsonb NOT NULL,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, quotation_id, version_no),
    FOREIGN KEY (organization_id, quotation_id)
        REFERENCES rnd.quotation_record (organization_id, id) ON DELETE CASCADE
);

CREATE TABLE rnd.quotation_review (
    quotation_id uuid PRIMARY KEY REFERENCES rnd.quotation_record(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    review_status varchar(20) NOT NULL DEFAULT 'NOT_SUBMITTED'
        CHECK (review_status IN ('NOT_SUBMITTED', 'SUBMITTED', 'APPROVED', 'REJECTED')),
    submitted_by uuid REFERENCES iam.app_user(id),
    submitted_at timestamptz,
    reviewed_by uuid REFERENCES iam.app_user(id),
    reviewed_at timestamptz,
    review_comment text,
    lock_version bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE rnd.quotation_review_event (
    id uuid PRIMARY KEY,
    quotation_id uuid NOT NULL REFERENCES rnd.quotation_record(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    from_status varchar(20),
    to_status varchar(20) NOT NULL
        CHECK (to_status IN ('NOT_SUBMITTED', 'SUBMITTED', 'APPROVED', 'REJECTED')),
    quotation_version integer NOT NULL,
    actor_id uuid NOT NULL REFERENCES iam.app_user(id),
    comment text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_quotation_review_event_record
    ON rnd.quotation_review_event (organization_id, quotation_id, created_at DESC);
