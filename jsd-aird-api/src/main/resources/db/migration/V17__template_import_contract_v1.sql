CREATE TABLE tpl.template_import_contract (
    template_version_id uuid PRIMARY KEY REFERENCES tpl.template_version(id) ON DELETE CASCADE,
    import_contract_version integer NOT NULL CHECK (import_contract_version > 0),
    layout_structure_version integer NOT NULL CHECK (layout_structure_version > 0),
    contract_hash char(64) NOT NULL,
    contract_jsonb jsonb NOT NULL,
    created_by uuid REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE tpl.template_import_contract IS
    '已发布模板的不可变导入契约；业务数据类型属于数据中心任务，不属于模板契约';

CREATE UNIQUE INDEX uq_template_import_contract_hash
    ON tpl.template_import_contract(template_version_id, contract_hash);

ALTER TABLE data.import_job
    ADD COLUMN import_contract_version integer,
    ADD COLUMN contract_hash char(64),
    ADD COLUMN source_file_hash char(64),
    ADD COLUMN compatibility_status varchar(24) NOT NULL DEFAULT 'LEGACY'
        CHECK (compatibility_status IN ('LEGACY', 'EXACT', 'COMPATIBLE', 'REVIEW_REQUIRED', 'INCOMPATIBLE'));

UPDATE data.import_job
SET source_file_hash = source_sha256
WHERE source_file_hash IS NULL;

ALTER TABLE ai.training_dataset
    ADD COLUMN import_contract_version integer,
    ADD COLUMN contract_hash char(64),
    ADD COLUMN approval_policy varchar(24) NOT NULL DEFAULT 'LEGACY'
        CHECK (approval_policy IN ('LEGACY', 'DISABLED', 'REQUIRED'));

ALTER TABLE data.data_value
    ADD COLUMN binding_id varchar(120),
    ADD COLUMN value_path varchar(500),
    ADD COLUMN label_path varchar(800),
    ADD COLUMN rag_eligible boolean NOT NULL DEFAULT true,
    ADD COLUMN value_source varchar(24) NOT NULL DEFAULT 'INPUT'
        CHECK (value_source IN ('INPUT', 'FORMULA', 'DERIVED', 'STATIC')),
    ADD COLUMN calculation_source varchar(24)
        CHECK (calculation_source IS NULL OR calculation_source IN ('RECALCULATED', 'CACHED', 'MISSING')),
    ADD COLUMN calculation_status varchar(24)
        CHECK (calculation_status IS NULL OR calculation_status IN ('VALID', 'STALE_POSSIBLE', 'FAILED'));

ALTER TABLE data.data_value
    DROP CONSTRAINT IF EXISTS data_value_record_id_field_code_data_path_key;

CREATE UNIQUE INDEX uq_data_value_locator_v2
    ON data.data_value(record_id, binding_id, value_path)
    WHERE binding_id IS NOT NULL AND value_path IS NOT NULL;

ALTER TABLE data.staging_row
    ADD COLUMN excluded boolean NOT NULL DEFAULT false,
    ADD COLUMN exclusion_reason varchar(500),
    ADD COLUMN excluded_by uuid REFERENCES iam.app_user(id),
    ADD COLUMN excluded_at timestamptz;

ALTER TABLE data.import_issue
    ADD COLUMN resolution_reason varchar(500);

ALTER TABLE data.import_mapping
    DROP CONSTRAINT IF EXISTS import_mapping_import_job_id_import_sheet_id_source_column_key;

CREATE UNIQUE INDEX uq_data_import_mapping_component_value
    ON data.import_mapping(
        import_job_id,
        import_sheet_id,
        coalesce(mapping_jsonb->>'componentId', ''),
        coalesce(mapping_jsonb->>'bindingId', source_column),
        source_column
    );
