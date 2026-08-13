-- Duplicate business identities are quality issues, not storage-key conflicts.
-- A source row is unique only inside its contract component.
DROP INDEX IF EXISTS data.uq_staging_row_logical_record_v7;

CREATE UNIQUE INDEX uq_staging_row_component_record_v7
    ON data.staging_row (
        import_job_id,
        import_sheet_id,
        coalesce(source_metadata_jsonb->>'componentId', ''),
        source_row_number
    );

-- Historical failures may already contain JDBC/SQL implementation details. Keep
-- the technical exception in server logs while returning a stable customer message.
UPDATE data.import_job
SET error_message = '导入数据结构存在冲突，请检查模板区域后重新导入'
WHERE error_message IS NOT NULL
  AND error_message ~* '(duplicate key|unique constraint|constraint .* violated|sqlstate|bad sql|jdbc|uuid|\u91cd\u590d\u952e|\u552f\u4e00\u7ea6\u675f)';
