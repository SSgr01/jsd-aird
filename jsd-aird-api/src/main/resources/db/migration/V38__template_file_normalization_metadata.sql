ALTER TABLE tpl.template_import_job
    ADD COLUMN IF NOT EXISTS original_source_file_id uuid REFERENCES ops.file_object(id),
    ADD COLUMN IF NOT EXISTS normalized_source_file_id uuid REFERENCES ops.file_object(id),
    ADD COLUMN IF NOT EXISTS original_format varchar(20),
    ADD COLUMN IF NOT EXISTS normalized_format varchar(20),
    ADD COLUMN IF NOT EXISTS normalization_status varchar(30),
    ADD COLUMN IF NOT EXISTS normalization_message text;

CREATE INDEX IF NOT EXISTS idx_template_import_job_normalized_source
    ON tpl.template_import_job (organization_id, normalized_source_file_id);
