CREATE TABLE IF NOT EXISTS kb.upload_task (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    actor_id uuid NOT NULL REFERENCES iam.app_user(id),
    file_name varchar(500) NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes >= 0),
    status varchar(24) NOT NULL CHECK (status IN (
        'STAGING', 'CHECKING', 'SUBMITTING', 'QUEUED', 'COMPLETED', 'DUPLICATE', 'FAILED'
    )),
    progress smallint NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    document_id uuid REFERENCES kb.document(id) ON DELETE SET NULL,
    document_version_id uuid REFERENCES kb.document_version(id) ON DELETE SET NULL,
    message text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_kb_upload_task_actor_recent
    ON kb.upload_task (organization_id, actor_id, created_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS uq_kb_upload_task_document_version
    ON kb.upload_task (organization_id, document_version_id)
    WHERE document_version_id IS NOT NULL;

-- Rebuild successful historical upload cards from persisted document versions.
-- Earlier client-only failures without a document cannot be recovered server-side.
INSERT INTO kb.upload_task (
    id, organization_id, actor_id, file_name, size_bytes, status, progress,
    document_id, document_version_id, message, created_at, updated_at
)
SELECT gen_random_uuid(), d.organization_id, d.created_by, v.original_name, v.size_bytes,
       CASE v.status
           WHEN 'READY' THEN 'COMPLETED'
           WHEN 'FAILED' THEN 'FAILED'
           WHEN 'REJECTED' THEN 'FAILED'
           WHEN 'PENDING_PROVIDER' THEN 'FAILED'
           ELSE 'QUEUED'
       END,
       100,
       d.id, v.id,
       CASE
           WHEN v.status = 'READY' THEN '解析完成，等待人工校对'
           WHEN v.status IN ('FAILED', 'REJECTED', 'PENDING_PROVIDER') THEN coalesce(v.error_message, d.parse_error)
           ELSE '已提交解析'
       END,
       v.created_at, v.updated_at
  FROM kb.document_version v
  JOIN kb.document d ON d.id = v.document_id
ON CONFLICT (organization_id, document_version_id) WHERE document_version_id IS NOT NULL DO NOTHING;

-- Shared upload workspaces keep their upload-history rows independently from
-- the business documents/assets created by a successful upload.
ALTER TABLE kb.upload_task
    ADD COLUMN IF NOT EXISTS source_file_id uuid REFERENCES ops.file_object(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS upload_context_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS hidden_at timestamptz;

CREATE INDEX IF NOT EXISTS idx_kb_upload_task_visible_recent
    ON kb.upload_task (organization_id, actor_id, created_at DESC)
    WHERE hidden_at IS NULL;

ALTER TABLE tpl.template_import_job
    ADD COLUMN IF NOT EXISTS hidden_at timestamptz;

CREATE INDEX IF NOT EXISTS idx_tpl_template_import_job_visible_recent
    ON tpl.template_import_job (organization_id, created_at DESC)
    WHERE hidden_at IS NULL;

ALTER TABLE spc.chart_upload_attempt
    ADD COLUMN IF NOT EXISTS upload_context_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS parent_request_id uuid,
    ADD COLUMN IF NOT EXISTS hidden_at timestamptz;

CREATE INDEX IF NOT EXISTS idx_spc_chart_upload_attempt_visible_recent
    ON spc.chart_upload_attempt (organization_id, created_at DESC)
    WHERE hidden_at IS NULL;

-- Preserve the former graph list as upload history when switching the page to
-- attempts. Older chart assets have no request ID, so use their stable asset ID.
INSERT INTO spc.chart_upload_attempt (
    id, organization_id, request_id, category_id, chart_id, file_object_id,
    original_name, size_bytes, status, created_by, created_at
)
SELECT gen_random_uuid(), chart.organization_id, coalesce(chart.request_id, chart.id), chart.category_id,
       chart.id, chart.file_object_id, chart.original_name, chart.size_bytes, 'SUCCEEDED',
       chart.created_by, chart.created_at
  FROM spc.chart_asset chart
 WHERE chart.status = 'READY'
ON CONFLICT (organization_id, request_id) DO NOTHING;

-- Retain the previous parsed/committed projection when a data upload is
-- reprocessed in place. This is upload-run history, not a second live dataset.
CREATE TABLE IF NOT EXISTS data.import_job_attempt (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    import_job_id uuid NOT NULL REFERENCES data.import_job(id) ON DELETE CASCADE,
    attempt_no integer NOT NULL CHECK (attempt_no > 0),
    reason varchar(40) NOT NULL,
    status varchar(32) NOT NULL,
    snapshot_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_by uuid REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, import_job_id, attempt_no)
);

CREATE INDEX IF NOT EXISTS idx_data_import_job_attempt_recent
    ON data.import_job_attempt (organization_id, import_job_id, created_at DESC);
