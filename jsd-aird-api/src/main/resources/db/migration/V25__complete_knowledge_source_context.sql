-- V22 was extended after it had already been applied in development databases.
-- Keep the added source-lineage/search capabilities in a new, idempotent migration.

ALTER TABLE kb.document
    ADD COLUMN IF NOT EXISTS source_info_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE kb.document_version
    ADD COLUMN IF NOT EXISTS source_info_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb;

CREATE INDEX IF NOT EXISTS idx_kb_document_version_sha256 ON kb.document_version(sha256, document_id);
CREATE INDEX IF NOT EXISTS idx_kb_document_duplicate_candidates
    ON kb.document(organization_id, document_type, updated_at DESC);

CREATE OR REPLACE FUNCTION kb.reject_duplicate_version_sha256() RETURNS trigger AS $$
DECLARE
    target_organization_id uuid;
BEGIN
    SELECT organization_id INTO target_organization_id FROM kb.document WHERE id = NEW.document_id;
    IF target_organization_id IS NULL THEN RETURN NEW; END IF;
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

DROP TRIGGER IF EXISTS trg_kb_document_version_sha256 ON kb.document_version;
CREATE TRIGGER trg_kb_document_version_sha256
    BEFORE INSERT ON kb.document_version
    FOR EACH ROW EXECUTE FUNCTION kb.reject_duplicate_version_sha256();

ALTER TABLE kb.document_chunk
    ADD COLUMN IF NOT EXISTS parse_run_id uuid REFERENCES kb.document_parse_run(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS sheet_name varchar(260),
    ADD COLUMN IF NOT EXISTS cell_range varchar(160),
    ADD COLUMN IF NOT EXISTS paragraph_id varchar(160),
    ADD COLUMN IF NOT EXISTS bbox_jsonb jsonb,
    ADD COLUMN IF NOT EXISTS start_time_ms bigint,
    ADD COLUMN IF NOT EXISTS end_time_ms bigint;

ALTER TABLE kb.document_chunk
    DROP CONSTRAINT IF EXISTS document_chunk_document_version_id_chunk_no_key;

CREATE UNIQUE INDEX IF NOT EXISTS uq_kb_chunk_parse_run_no
    ON kb.document_chunk(parse_run_id, chunk_no) WHERE parse_run_id IS NOT NULL;

CREATE OR REPLACE VIEW kb.current_file_search_projection AS
SELECT d.organization_id, d.id AS logical_document_id, p.id AS publication_id,
       v.id AS file_version_id, v.file_object_id,
       coalesce(p.metadata_snapshot_jsonb->>'title', d.title) AS title, v.original_name,
       v.content_type, v.size_bytes, v.version_no, p.published_at AS updated_at
FROM kb.document d
JOIN kb.publication p ON p.id = d.current_publication_id AND p.status = 'CURRENT'
JOIN kb.document_version v ON v.id = p.document_version_id
JOIN ops.file_object f ON f.id = v.file_object_id AND f.organization_id = d.organization_id AND f.status <> 'DELETED'
WHERE d.lifecycle_status = 'ACTIVE';

CREATE OR REPLACE VIEW data.completed_source_file_projection AS
SELECT j.organization_id, j.id AS import_job_id, j.source_file_id AS file_object_id,
       j.source_file_name AS original_name, j.source_format,
       coalesce(j.completed_at, j.updated_at) AS updated_at
FROM data.import_job j
JOIN ops.file_object f ON f.id = j.source_file_id AND f.organization_id = j.organization_id AND f.status <> 'DELETED'
WHERE j.status = 'COMPLETED';

ALTER TABLE data.staging_row
    ADD COLUMN IF NOT EXISTS source_search_vector tsvector GENERATED ALWAYS AS
        (to_tsvector('simple', coalesce(raw_values_jsonb::text, ''))) STORED;

CREATE INDEX IF NOT EXISTS idx_data_staging_source_search
    ON data.staging_row USING gin(source_search_vector);

INSERT INTO kb.document_parse_run (
    id, organization_id, document_id, document_version_id, run_no, status,
    parser_version, finished_at, created_at
)
SELECT gen_random_uuid(), d.organization_id, d.id, v.id, 1, 'PENDING_REVIEW',
       coalesce(v.parser_version, 'legacy-v1'), v.updated_at, v.created_at
FROM kb.document d
JOIN kb.document_version v ON v.document_id = d.id AND v.version_no = d.current_version_no
WHERE d.status = 'READY' AND v.status = 'READY'
ON CONFLICT (document_version_id, run_no) DO NOTHING;

UPDATE kb.document_chunk c
SET parse_run_id = r.id
FROM kb.document_parse_run r
WHERE r.document_version_id = c.document_version_id AND c.parse_run_id IS NULL;

INSERT INTO kb.document_parse_block (
    id, parse_run_id, block_no, page_no, section, raw_text, normalized_text,
    confirmed_text, confidence, review_status
)
SELECT gen_random_uuid(), r.id, c.chunk_no, c.page_no, c.section, c.content, c.content,
       c.content, 1.0000, 'CONFIRMED'
FROM kb.document_parse_run r
JOIN kb.document_chunk c ON c.document_version_id = r.document_version_id
ON CONFLICT (parse_run_id, block_no) DO NOTHING;

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
WHERE d.status = 'READY' AND v.status = 'READY'
ON CONFLICT (document_id, publication_no) DO NOTHING;

UPDATE kb.document d SET current_publication_id = p.id
FROM kb.publication p
WHERE p.document_id = d.id AND p.status = 'CURRENT' AND d.current_publication_id IS DISTINCT FROM p.id;

UPDATE kb.document_version v
SET review_status = 'PUBLISHED', reviewed_at = p.published_at, reviewed_by = p.published_by
FROM kb.publication p
WHERE p.document_version_id = v.id AND v.review_status <> 'PUBLISHED';

UPDATE kb.document_parse_run r SET status = 'PUBLISHED', finished_at = p.published_at
FROM kb.publication p WHERE p.parse_run_id = r.id AND r.status <> 'PUBLISHED';

INSERT INTO kb.ai_usage_grant (publication_id, organization_id, status, reason, updated_by, updated_at)
SELECT p.id, p.organization_id, 'APPROVED', 'Migrated from document AI status',
       p.published_by, p.published_at
FROM kb.publication p JOIN kb.document d ON d.id = p.document_id
WHERE d.ai_status = 'APPROVED'
ON CONFLICT (publication_id) DO NOTHING;

INSERT INTO ops.audit_log (
    id, organization_id, actor_id, action, aggregate_type, aggregate_id, detail_jsonb, created_at
)
SELECT gen_random_uuid(), p.organization_id, p.published_by, 'KB_GOVERNANCE_MIGRATED',
       'KB_DOCUMENT', p.document_id,
       jsonb_build_object('publicationId', p.id, 'versionId', p.document_version_id,
                          'publicationNo', p.publication_no,
                          'aiStatus', coalesce(g.status, 'PENDING')),
       p.published_at
FROM kb.publication p LEFT JOIN kb.ai_usage_grant g ON g.publication_id = p.id
WHERE NOT EXISTS (
    SELECT 1 FROM ops.audit_log a
    WHERE a.organization_id = p.organization_id AND a.action = 'KB_GOVERNANCE_MIGRATED'
      AND a.aggregate_id = p.document_id
);

INSERT INTO ops.async_job (
    id, organization_id, job_type, status, priority, payload_jsonb, idempotency_key
)
SELECT gen_random_uuid(), o.id, 'KB_REBUILD_FILE_SEARCH', 'READY', 80,
       jsonb_build_object('organizationId', o.id), 'kb-file-search-v25'
FROM iam.organization o
ON CONFLICT (organization_id, idempotency_key) DO NOTHING;
