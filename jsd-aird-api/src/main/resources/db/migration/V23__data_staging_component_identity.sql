-- A physical row number is not a logical record identity when one Sheet contains
-- multiple forms, tables or matrices. V7 contracts persist componentId and
-- recordKey in source_metadata_jsonb; use them as the unique staging identity.
ALTER TABLE data.staging_row
    DROP CONSTRAINT IF EXISTS staging_row_import_job_id_import_sheet_id_source_row_number_key;

DROP INDEX IF EXISTS data.idx_staging_row_job_sheet_row;

CREATE UNIQUE INDEX uq_staging_row_logical_record_v7
    ON data.staging_row (
        import_job_id,
        import_sheet_id,
        coalesce(source_metadata_jsonb->>'componentId', ''),
        coalesce(source_metadata_jsonb->>'recordKey', source_row_number::text)
    );

CREATE INDEX idx_staging_row_job_sheet_row
    ON data.staging_row (import_job_id, import_sheet_id, source_row_number);
