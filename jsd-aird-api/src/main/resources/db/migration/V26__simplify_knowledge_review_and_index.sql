-- Simplify knowledge governance around editable recognition text and publication-time indexing.

-- Requeue media documents that were previously blocked by per-file consent.
INSERT INTO ops.async_job (
    id, organization_id, job_type, status, priority, payload_jsonb, idempotency_key
)
SELECT gen_random_uuid(), r.organization_id, 'KB_INGEST_DOCUMENT', 'READY', 40,
       jsonb_build_object(
           'organizationId', r.organization_id,
           'documentId', r.document_id,
           'versionId', r.document_version_id,
           'actorId', d.created_by,
           'fileId', v.file_object_id
       ),
       'kb-ingest-without-consent:' || r.document_version_id
FROM kb.document_parse_run r
JOIN kb.document d ON d.id = r.document_id
JOIN kb.document_version v ON v.id = r.document_version_id
WHERE r.status = 'WAITING_MEDIA_CONSENT'
  AND NOT EXISTS (
      SELECT 1 FROM kb.document_parse_run newer
      WHERE newer.document_version_id = r.document_version_id AND newer.run_no > r.run_no
  )
ON CONFLICT (organization_id, idempotency_key) DO NOTHING;

UPDATE kb.document_version v
SET status = 'QUEUED', review_status = 'PENDING_REVIEW', error_message = NULL, updated_at = now()
WHERE EXISTS (
    SELECT 1 FROM kb.document_parse_run r
    WHERE r.document_version_id = v.id AND r.status = 'WAITING_MEDIA_CONSENT'
);

UPDATE kb.document d
SET status = 'QUEUED', parse_error = NULL, updated_at = now()
WHERE EXISTS (
    SELECT 1 FROM kb.document_parse_run r
    WHERE r.document_id = d.id AND r.status = 'WAITING_MEDIA_CONSENT'
);

UPDATE kb.document_parse_run
SET status = 'FAILED', error_message = '媒体确认机制已移除，任务已重新排队', finished_at = now()
WHERE status = 'WAITING_MEDIA_CONSENT';

ALTER TABLE kb.document_parse_run
    DROP CONSTRAINT IF EXISTS document_parse_run_status_check;
ALTER TABLE kb.document_parse_run
    ADD CONSTRAINT document_parse_run_status_check CHECK (status IN (
        'QUEUED', 'PROCESSING', 'PENDING_REVIEW', 'INDEXING', 'PUBLISHED', 'REJECTED', 'FAILED'
    ));

-- Processing state is tracked per parse run so one file version can have multiple reviewed revisions.
ALTER TABLE kb.document_processing_step
    ADD COLUMN parse_run_id uuid REFERENCES kb.document_parse_run(id) ON DELETE CASCADE;

UPDATE kb.document_processing_step s
SET parse_run_id = (
    SELECT r.id
    FROM kb.document_parse_run r
    WHERE r.document_version_id = s.document_version_id
    ORDER BY r.run_no DESC
    LIMIT 1
)
WHERE s.parse_run_id IS NULL;

ALTER TABLE kb.document_processing_step
    DROP CONSTRAINT IF EXISTS document_processing_step_document_version_id_step_key_key,
    DROP CONSTRAINT IF EXISTS document_processing_step_status_check;

ALTER TABLE kb.document_processing_step
    ADD CONSTRAINT document_processing_step_status_check CHECK (status IN (
        'PENDING', 'STALE', 'RUNNING', 'SUCCEEDED', 'FAILED', 'PENDING_PROVIDER', 'NOT_REQUIRED'
    ));

CREATE UNIQUE INDEX uq_kb_processing_step_parse_run
    ON kb.document_processing_step(parse_run_id, step_key)
    WHERE parse_run_id IS NOT NULL;
CREATE UNIQUE INDEX uq_kb_processing_step_legacy_version
    ON kb.document_processing_step(document_version_id, step_key)
    WHERE parse_run_id IS NULL;

-- Remove the hard-coded extraction model while keeping block-level parse issues.
ALTER TABLE kb.document_parse_issue
    DROP COLUMN IF EXISTS extract_field_id;
DROP TABLE IF EXISTS kb.document_extract_field;

-- Remove object relations and the generated knowledge-page feature.
DROP TABLE IF EXISTS kb.knowledge_page_source;
ALTER TABLE kb.knowledge_page
    DROP CONSTRAINT IF EXISTS fk_kb_page_current_version;
DROP TABLE IF EXISTS kb.knowledge_page_version;
DROP TABLE IF EXISTS kb.knowledge_page;
DROP TABLE IF EXISTS kb.document_relation;
DROP TABLE IF EXISTS core.business_object_ref;

-- Publication snapshots no longer expose deleted metadata.
UPDATE kb.publication
SET metadata_snapshot_jsonb = metadata_snapshot_jsonb - 'documentType' - 'relations';

DROP INDEX IF EXISTS kb.idx_kb_document_duplicate_candidates;
CREATE INDEX idx_kb_document_duplicate_candidates
    ON kb.document(organization_id, category_id, updated_at DESC);

ALTER TABLE kb.document
    DROP COLUMN IF EXISTS document_type;

ALTER TABLE kb.document_version
    DROP COLUMN IF EXISTS media_processing_consent,
    DROP COLUMN IF EXISTS media_consent_by,
    DROP COLUMN IF EXISTS media_consent_at;

-- AI permission belongs to the logical document and is inherited by all later publications.
CREATE TABLE kb.document_ai_grant (
    document_id uuid PRIMARY KEY REFERENCES kb.document(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    status varchar(24) NOT NULL CHECK (status IN ('APPROVED', 'REVOKED', 'REJECTED')),
    reason varchar(500),
    updated_by uuid NOT NULL REFERENCES iam.app_user(id),
    updated_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO kb.document_ai_grant (document_id, organization_id, status, reason, updated_by, updated_at)
SELECT DISTINCT ON (p.document_id)
       p.document_id, g.organization_id, g.status, g.reason, g.updated_by, g.updated_at
FROM kb.ai_usage_grant g
JOIN kb.publication p ON p.id = g.publication_id
ORDER BY p.document_id, (p.status = 'CURRENT') DESC, p.publication_no DESC
ON CONFLICT (document_id) DO NOTHING;

INSERT INTO kb.document_ai_grant (document_id, organization_id, status, reason, updated_by, updated_at)
SELECT d.id, d.organization_id, d.ai_status, 'Migrated from document AI status', d.created_by, d.updated_at
FROM kb.document d
WHERE d.ai_status IN ('APPROVED', 'REVOKED', 'REJECTED')
ON CONFLICT (document_id) DO NOTHING;

DROP TABLE kb.ai_usage_grant;
DROP INDEX IF EXISTS kb.idx_kb_document_status;
ALTER TABLE kb.document DROP COLUMN ai_status;
CREATE INDEX idx_kb_document_status ON kb.document(organization_id, status);
CREATE INDEX idx_kb_document_ai_grant_status
    ON kb.document_ai_grant(organization_id, status, updated_at DESC);

-- Existing approved publications may have been published without vectors; rebuild them once.
INSERT INTO ops.async_job (
    id, organization_id, job_type, status, priority, payload_jsonb, idempotency_key
)
SELECT gen_random_uuid(), p.organization_id, 'KB_BUILD_KNOWLEDGE_VECTOR', 'READY', 55,
       jsonb_build_object(
           'organizationId', p.organization_id,
           'documentId', p.document_id,
           'publicationId', p.id,
           'parseRunId', p.parse_run_id
       ),
       'kb-vector-v26:' || p.id
FROM kb.publication p
JOIN kb.document_ai_grant g ON g.document_id = p.document_id AND g.status = 'APPROVED'
WHERE p.status = 'CURRENT'
ON CONFLICT (organization_id, idempotency_key) DO NOTHING;
