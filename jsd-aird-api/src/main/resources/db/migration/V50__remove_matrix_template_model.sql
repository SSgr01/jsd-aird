-- The simple-region release is intentionally non-converting. Any environment
-- that still contains matrix state must be cleaned explicitly before Flyway is
-- allowed to narrow the database contracts.
DO $$
DECLARE
    matrix_mapping_count bigint;
    matrix_schema_count bigint;
    matrix_suggestion_count bigint;
    matrix_contract_count bigint;
    matrix_business_count bigint;
BEGIN
    SELECT count(*) INTO matrix_mapping_count
    FROM tpl.template_mapping
    WHERE mapping_kind IN ('MATRIX_REGION', 'MATRIX_FIELD')
       OR locator_jsonb::text ~* 'MATRIX|CROSS_TAB'
       OR diagnostic_jsonb::text ~* 'MATRIX|CROSS_TAB';

    SELECT count(*) INTO matrix_schema_count
    FROM tpl.template_version
    WHERE schema_jsonb::text ~* 'MATRIX|CROSS_TAB';

    SELECT count(*) INTO matrix_suggestion_count
    FROM tpl.recognition_suggestion
    WHERE suggestion_type IN ('MATRIX', 'MATRIX_REGION', 'MATRIX_FIELD')
       OR payload_jsonb::text ~* 'MATRIX|CROSS_TAB';

    SELECT count(*) INTO matrix_contract_count
    FROM tpl.template_import_contract
    WHERE contract_jsonb::text ~* 'MATRIX|CROSS_TAB';

    SELECT
        (SELECT count(*) FROM tpl.record_collection_item WHERE record_kind = 'MATRIX')
        + (SELECT count(*) FROM mfg.production_ingest_item WHERE item_kind = 'MATRIX')
    INTO matrix_business_count;

    IF matrix_mapping_count <> 0 OR matrix_schema_count <> 0
       OR matrix_suggestion_count <> 0 OR matrix_contract_count <> 0
       OR matrix_business_count <> 0 THEN
        RAISE EXCEPTION USING
            MESSAGE = 'Matrix state still exists; guarded cleanup is required before V50',
            DETAIL = format(
                'mappings=%s schemas=%s suggestions=%s contracts=%s business_rows=%s',
                matrix_mapping_count, matrix_schema_count, matrix_suggestion_count,
                matrix_contract_count, matrix_business_count
            ),
            HINT = 'Do not auto-convert or auto-delete. Run the protected test cleanup for approved test data, or migrate the affected environment explicitly.';
    END IF;
END $$;

ALTER TABLE tpl.template_mapping
    DROP CONSTRAINT IF EXISTS template_mapping_mapping_kind_check;
ALTER TABLE tpl.template_mapping
    ADD CONSTRAINT template_mapping_mapping_kind_check
    CHECK (mapping_kind IN ('SCALAR', 'REPEAT_REGION', 'REPEAT_FIELD'));

ALTER TABLE tpl.record_collection_item
    DROP CONSTRAINT IF EXISTS record_collection_item_record_kind_check;
ALTER TABLE tpl.record_collection_item
    ADD CONSTRAINT record_collection_item_record_kind_check
    CHECK (record_kind = 'DETAIL');

ALTER TABLE mfg.production_ingest_item
    DROP CONSTRAINT IF EXISTS production_ingest_item_item_kind_check;
ALTER TABLE mfg.production_ingest_item
    ADD CONSTRAINT production_ingest_item_item_kind_check
    CHECK (item_kind IN ('SCALAR', 'DETAIL'));
