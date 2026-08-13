ALTER TABLE tpl.template_import_job
    ADD COLUMN IF NOT EXISTS category_id uuid REFERENCES tpl.template_category(id),
    ADD COLUMN IF NOT EXISTS source_sha256 char(64),
    ADD COLUMN IF NOT EXISTS duplicate_override boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS duplicate_source_job_id uuid REFERENCES tpl.template_import_job(id),
    ADD COLUMN IF NOT EXISTS operation_source varchar(40) NOT NULL DEFAULT 'UPLOAD';

ALTER TABLE tpl.template_import_job
    DROP CONSTRAINT IF EXISTS template_import_job_category_id_fkey;
ALTER TABLE tpl.template_import_job
    ADD CONSTRAINT template_import_job_category_id_fkey
    FOREIGN KEY (category_id) REFERENCES tpl.template_category(id) ON DELETE SET NULL;

UPDATE tpl.template_import_job tij
SET source_sha256 = fo.sha256
FROM ops.file_object fo
WHERE fo.id = tij.source_file_id
  AND tij.source_sha256 IS NULL;

CREATE INDEX IF NOT EXISTS idx_template_import_job_duplicate_lookup
    ON tpl.template_import_job (organization_id, source_sha256, format, created_at DESC)
    WHERE source_sha256 IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_template_import_job_category
    ON tpl.template_import_job (organization_id, category_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_template_logical_list
    ON tpl.template (organization_id, updated_at DESC, id);

CREATE INDEX IF NOT EXISTS idx_template_version_current_draft
    ON tpl.template_version (template_id, version_no DESC)
    WHERE status = 'DRAFT';
