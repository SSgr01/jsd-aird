CREATE TABLE core.business_object_ref (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    object_type varchar(32) NOT NULL CHECK (object_type IN (
        'PRODUCT', 'PROJECT', 'EXPERIMENT', 'FORMULA', 'PROCESS', 'BATCH', 'QUALITY_CASE', 'OTHER'
    )),
    external_id varchar(160) NOT NULL,
    name varchar(260) NOT NULL,
    source_system varchar(80) NOT NULL DEFAULT 'MANUAL',
    status varchar(24) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    metadata_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, object_type, external_id)
);

CREATE INDEX idx_business_object_ref_list
    ON core.business_object_ref (organization_id, object_type, status, name);

ALTER TABLE kb.document
    ADD COLUMN lifecycle_status varchar(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (lifecycle_status IN ('ACTIVE', 'DISABLED')),
    ADD COLUMN disabled_by uuid REFERENCES iam.app_user(id),
    ADD COLUMN disabled_at timestamptz,
    ADD COLUMN disabled_reason varchar(500),
    ADD COLUMN source_info_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN current_publication_id uuid;

ALTER TABLE kb.document_version
    ADD COLUMN review_status varchar(32) NOT NULL DEFAULT 'PENDING_REVIEW'
        CHECK (review_status IN ('PENDING_REVIEW', 'REJECTED', 'PUBLISHED', 'SUPERSEDED')),
    ADD COLUMN review_revision integer NOT NULL DEFAULT 0 CHECK (review_revision >= 0),
    ADD COLUMN review_reason varchar(1000),
    ADD COLUMN reviewed_by uuid REFERENCES iam.app_user(id),
    ADD COLUMN reviewed_at timestamptz,
    ADD COLUMN media_processing_consent boolean NOT NULL DEFAULT false,
    ADD COLUMN media_consent_by uuid REFERENCES iam.app_user(id),
    ADD COLUMN media_consent_at timestamptz,
    ADD COLUMN source_info_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb;

CREATE INDEX idx_kb_document_version_sha256 ON kb.document_version(sha256, document_id);
CREATE INDEX idx_kb_document_duplicate_candidates
    ON kb.document(organization_id, document_type, updated_at DESC);

CREATE FUNCTION kb.reject_duplicate_version_sha256() RETURNS trigger AS $$
DECLARE
    target_organization_id uuid;
BEGIN
    SELECT organization_id INTO target_organization_id FROM kb.document WHERE id = NEW.document_id;
    IF target_organization_id IS NULL THEN
        RETURN NEW;
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(target_organization_id::text || ':' || NEW.sha256, 0));
    IF EXISTS (
        SELECT 1 FROM kb.document_version existing
        JOIN kb.document d ON d.id = existing.document_id
        WHERE d.organization_id = target_organization_id AND existing.sha256 = NEW.sha256
    ) THEN
        RAISE unique_violation USING
            MESSAGE = 'duplicate knowledge file sha256 in organization',
            CONSTRAINT = 'uq_kb_document_version_org_sha256';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_kb_document_version_sha256
    BEFORE INSERT ON kb.document_version
    FOR EACH ROW EXECUTE FUNCTION kb.reject_duplicate_version_sha256();

CREATE TABLE kb.document_parse_run (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    document_id uuid NOT NULL REFERENCES kb.document(id) ON DELETE CASCADE,
    document_version_id uuid NOT NULL REFERENCES kb.document_version(id) ON DELETE CASCADE,
    run_no integer NOT NULL CHECK (run_no > 0),
    status varchar(32) NOT NULL CHECK (status IN (
        'QUEUED', 'PROCESSING', 'WAITING_MEDIA_CONSENT', 'PENDING_REVIEW', 'PUBLISHED', 'REJECTED', 'FAILED'
    )),
    parser_version varchar(80),
    provider varchar(120),
    provider_task_id varchar(260),
    error_message varchar(2000),
    result_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (document_version_id, run_no)
);

CREATE INDEX idx_kb_parse_run_current
    ON kb.document_parse_run (organization_id, document_id, document_version_id, run_no DESC);

ALTER TABLE kb.document_chunk
    ADD COLUMN parse_run_id uuid REFERENCES kb.document_parse_run(id) ON DELETE CASCADE,
    ADD COLUMN sheet_name varchar(260),
    ADD COLUMN cell_range varchar(160),
    ADD COLUMN paragraph_id varchar(160),
    ADD COLUMN bbox_jsonb jsonb,
    ADD COLUMN start_time_ms bigint,
    ADD COLUMN end_time_ms bigint;

ALTER TABLE kb.document_chunk
    DROP CONSTRAINT document_chunk_document_version_id_chunk_no_key;

CREATE UNIQUE INDEX uq_kb_chunk_parse_run_no
    ON kb.document_chunk(parse_run_id, chunk_no) WHERE parse_run_id IS NOT NULL;

CREATE TABLE kb.document_parse_block (
    id uuid PRIMARY KEY,
    parse_run_id uuid NOT NULL REFERENCES kb.document_parse_run(id) ON DELETE CASCADE,
    block_no integer NOT NULL CHECK (block_no >= 0),
    page_no integer,
    sheet_name varchar(260),
    cell_range varchar(160),
    paragraph_id varchar(160),
    bbox_jsonb jsonb,
    start_time_ms bigint,
    end_time_ms bigint,
    section varchar(500),
    raw_text text NOT NULL,
    normalized_text text NOT NULL,
    confirmed_text text,
    confidence numeric(5,4),
    review_status varchar(24) NOT NULL DEFAULT 'PENDING'
        CHECK (review_status IN ('PENDING', 'CONFIRMED', 'IGNORED', 'ISSUE')),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (parse_run_id, block_no)
);

CREATE INDEX idx_kb_parse_block_run ON kb.document_parse_block(parse_run_id, block_no);

CREATE TABLE kb.document_extract_field (
    id uuid PRIMARY KEY,
    parse_run_id uuid NOT NULL REFERENCES kb.document_parse_run(id) ON DELETE CASCADE,
    field_code varchar(160) NOT NULL,
    field_name varchar(200) NOT NULL,
    raw_value text,
    normalized_value text,
    confirmed_value text,
    source_unit varchar(64),
    standard_unit varchar(64),
    confidence numeric(5,4),
    required boolean NOT NULL DEFAULT false,
    conflict boolean NOT NULL DEFAULT false,
    candidates_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    review_status varchar(24) NOT NULL DEFAULT 'PENDING'
        CHECK (review_status IN ('PENDING', 'CONFIRMED', 'IGNORED', 'ISSUE')),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (parse_run_id, field_code)
);

CREATE INDEX idx_kb_extract_field_run ON kb.document_extract_field(parse_run_id, field_code);

CREATE TABLE kb.document_parse_issue (
    id uuid PRIMARY KEY,
    parse_run_id uuid NOT NULL REFERENCES kb.document_parse_run(id) ON DELETE CASCADE,
    parse_block_id uuid REFERENCES kb.document_parse_block(id) ON DELETE CASCADE,
    extract_field_id uuid REFERENCES kb.document_extract_field(id) ON DELETE CASCADE,
    issue_code varchar(80) NOT NULL,
    severity varchar(16) NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'BLOCKER')),
    message varchar(1000) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED', 'IGNORED')),
    resolution varchar(1000),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_kb_parse_issue_run ON kb.document_parse_issue(parse_run_id, status, severity);

CREATE TABLE kb.publication (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    document_id uuid NOT NULL REFERENCES kb.document(id) ON DELETE CASCADE,
    document_version_id uuid NOT NULL REFERENCES kb.document_version(id),
    parse_run_id uuid NOT NULL REFERENCES kb.document_parse_run(id),
    publication_no integer NOT NULL CHECK (publication_no > 0),
    status varchar(24) NOT NULL DEFAULT 'CURRENT' CHECK (status IN ('CURRENT', 'SUPERSEDED')),
    metadata_snapshot_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    published_by uuid NOT NULL REFERENCES iam.app_user(id),
    published_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (document_id, publication_no)
);

CREATE UNIQUE INDEX uq_kb_publication_current
    ON kb.publication(document_id) WHERE status = 'CURRENT';

ALTER TABLE kb.document
    ADD CONSTRAINT fk_kb_document_current_publication
    FOREIGN KEY (current_publication_id) REFERENCES kb.publication(id);

CREATE TABLE kb.ai_usage_grant (
    publication_id uuid PRIMARY KEY REFERENCES kb.publication(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    status varchar(24) NOT NULL CHECK (status IN ('APPROVED', 'REVOKED', 'REJECTED')),
    reason varchar(500),
    updated_by uuid NOT NULL REFERENCES iam.app_user(id),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE kb.tag (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    normalized_name varchar(120) NOT NULL,
    display_name varchar(120) NOT NULL,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, normalized_name)
);

CREATE TABLE kb.document_tag (
    document_id uuid NOT NULL REFERENCES kb.document(id) ON DELETE CASCADE,
    tag_id uuid NOT NULL REFERENCES kb.tag(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, tag_id)
);

CREATE TABLE kb.document_relation (
    document_id uuid NOT NULL REFERENCES kb.document(id) ON DELETE CASCADE,
    object_ref_id uuid NOT NULL REFERENCES core.business_object_ref(id),
    relation_type varchar(32) NOT NULL DEFAULT 'RELATED_TO',
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, object_ref_id)
);

CREATE INDEX idx_kb_document_relation_object ON kb.document_relation(object_ref_id, document_id);

CREATE TABLE kb.knowledge_page (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    object_ref_id uuid NOT NULL REFERENCES core.business_object_ref(id),
    title varchar(260) NOT NULL,
    draft_summary text NOT NULL DEFAULT '',
    draft_revision integer NOT NULL DEFAULT 0,
    current_version_id uuid,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, object_ref_id)
);

CREATE TABLE kb.knowledge_page_version (
    id uuid PRIMARY KEY,
    page_id uuid NOT NULL REFERENCES kb.knowledge_page(id) ON DELETE CASCADE,
    version_no integer NOT NULL CHECK (version_no > 0),
    title varchar(260) NOT NULL,
    summary text NOT NULL,
    published_by uuid NOT NULL REFERENCES iam.app_user(id),
    published_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (page_id, version_no)
);

ALTER TABLE kb.knowledge_page
    ADD CONSTRAINT fk_kb_page_current_version
    FOREIGN KEY (current_version_id) REFERENCES kb.knowledge_page_version(id);

CREATE TABLE kb.knowledge_page_source (
    page_version_id uuid NOT NULL REFERENCES kb.knowledge_page_version(id) ON DELETE CASCADE,
    publication_id uuid NOT NULL REFERENCES kb.publication(id),
    PRIMARY KEY (page_version_id, publication_id)
);

-- Unified file-search projections intentionally contain only immutable source files.
CREATE VIEW kb.current_file_search_projection AS
SELECT d.organization_id, d.id AS logical_document_id, p.id AS publication_id,
       v.id AS file_version_id, v.file_object_id,
       coalesce(p.metadata_snapshot_jsonb->>'title', d.title) AS title, v.original_name,
       v.content_type, v.size_bytes, v.version_no, p.published_at AS updated_at
FROM kb.document d
JOIN kb.publication p ON p.id = d.current_publication_id AND p.status = 'CURRENT'
JOIN kb.document_version v ON v.id = p.document_version_id
JOIN ops.file_object f ON f.id = v.file_object_id AND f.organization_id = d.organization_id AND f.status <> 'DELETED'
WHERE d.lifecycle_status = 'ACTIVE';

CREATE VIEW data.completed_source_file_projection AS
SELECT j.organization_id, j.id AS import_job_id, j.source_file_id AS file_object_id,
       j.source_file_name AS original_name, j.source_format,
       coalesce(j.completed_at, j.updated_at) AS updated_at
FROM data.import_job j
JOIN ops.file_object f ON f.id = j.source_file_id AND f.organization_id = j.organization_id AND f.status <> 'DELETED'
WHERE j.status = 'COMPLETED';

ALTER TABLE data.staging_row
    ADD COLUMN source_search_vector tsvector GENERATED ALWAYS AS
        (to_tsvector('simple', coalesce(raw_values_jsonb::text, ''))) STORED;

CREATE INDEX idx_data_staging_source_search
    ON data.staging_row USING gin(source_search_vector);

-- Preserve the currently visible corpus. Existing READY versions become an
-- approved parse snapshot and current publication; future versions retain the
-- PENDING_REVIEW default until a reviewer publishes them.
INSERT INTO kb.document_parse_run (
    id, organization_id, document_id, document_version_id, run_no, status,
    parser_version, finished_at, created_at
)
SELECT gen_random_uuid(), d.organization_id, d.id, v.id, 1, 'PENDING_REVIEW',
       coalesce(v.parser_version, 'legacy-v1'), v.updated_at, v.created_at
FROM kb.document d
JOIN kb.document_version v ON v.document_id = d.id AND v.version_no = d.current_version_no
WHERE d.status = 'READY' AND v.status = 'READY';

UPDATE kb.document_chunk c
SET parse_run_id = r.id
FROM kb.document_parse_run r
WHERE r.document_version_id = c.document_version_id;

INSERT INTO kb.document_parse_block (
    id, parse_run_id, block_no, page_no, section, raw_text, normalized_text,
    confirmed_text, confidence, review_status
)
SELECT gen_random_uuid(), r.id, c.chunk_no, c.page_no, c.section, c.content, c.content,
       c.content, 1.0000, 'CONFIRMED'
FROM kb.document_parse_run r
JOIN kb.document_chunk c ON c.document_version_id = r.document_version_id;

INSERT INTO kb.publication (
    id, organization_id, document_id, document_version_id, parse_run_id,
    publication_no, status, metadata_snapshot_jsonb, published_by, published_at
)
SELECT gen_random_uuid(), d.organization_id, d.id, v.id, r.id, 1, 'CURRENT',
       jsonb_build_object('title', d.title, 'documentType', d.document_type,
                          'libraryScope', d.library_scope, 'categoryId', d.category_id),
       d.created_by, coalesce(v.updated_at, d.updated_at)
FROM kb.document d
JOIN kb.document_version v ON v.document_id = d.id AND v.version_no = d.current_version_no
JOIN kb.document_parse_run r ON r.document_version_id = v.id
WHERE d.status = 'READY' AND v.status = 'READY';

UPDATE kb.document d
SET current_publication_id = p.id
FROM kb.publication p
WHERE p.document_id = d.id AND p.status = 'CURRENT';

UPDATE kb.document_version v
SET review_status = 'PUBLISHED', reviewed_at = p.published_at, reviewed_by = p.published_by
FROM kb.publication p
WHERE p.document_version_id = v.id;

UPDATE kb.document_parse_run r
SET status = 'PUBLISHED', finished_at = p.published_at
FROM kb.publication p
WHERE p.parse_run_id = r.id;

INSERT INTO kb.ai_usage_grant (
    publication_id, organization_id, status, reason, updated_by, updated_at
)
SELECT p.id, p.organization_id, 'APPROVED', 'Migrated from document AI status',
       p.published_by, p.published_at
FROM kb.publication p
JOIN kb.document d ON d.id = p.document_id
WHERE d.ai_status = 'APPROVED';

INSERT INTO ops.audit_log (
    id, organization_id, actor_id, action, aggregate_type, aggregate_id, detail_jsonb, created_at
)
SELECT gen_random_uuid(), p.organization_id, p.published_by, 'KB_GOVERNANCE_MIGRATED',
       'KB_DOCUMENT', p.document_id,
       jsonb_build_object('publicationId', p.id, 'versionId', p.document_version_id,
                          'publicationNo', p.publication_no,
                          'aiStatus', coalesce(g.status, 'PENDING')),
       p.published_at
FROM kb.publication p
LEFT JOIN kb.ai_usage_grant g ON g.publication_id = p.id;

-- Rebuilding corpus statistics is intentionally asynchronous and idempotent;
-- Flyway only schedules the work and never waits for the index rebuild.
INSERT INTO ops.async_job (
    id, organization_id, job_type, status, priority, payload_jsonb, idempotency_key
)
SELECT gen_random_uuid(), o.id, 'KB_REBUILD_FILE_SEARCH', 'READY', 80,
       jsonb_build_object('organizationId', o.id), 'kb-file-search-v22'
FROM iam.organization o
ON CONFLICT (organization_id, idempotency_key) DO NOTHING;
