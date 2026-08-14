-- Data-center classification is independent from the template's business shape.
-- The old target_data_type was a second, conflicting classification selector.
ALTER TABLE data.import_job
    DROP COLUMN IF EXISTS target_data_type CASCADE;

ALTER TABLE data.data_category
    DROP COLUMN IF EXISTS target_data_type CASCADE;

ALTER TABLE data.data_asset
    DROP COLUMN IF EXISTS target_data_type CASCADE;
