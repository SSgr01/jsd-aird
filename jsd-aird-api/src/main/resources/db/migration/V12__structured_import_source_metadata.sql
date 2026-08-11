ALTER TABLE data.staging_row
    ADD COLUMN source_metadata_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb;
