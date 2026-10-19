ALTER TABLE rnd.experiment_import_job ADD COLUMN import_options_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb;
CREATE UNIQUE INDEX experiment_content_import_source_uidx ON rnd.experiment_import_job(organization_id,source_file_id) WHERE import_options_jsonb->>'processingMode'='CONTENT_ONLY';
