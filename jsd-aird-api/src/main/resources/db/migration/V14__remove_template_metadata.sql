DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM tpl.template_version
        WHERE (template_scope = 'DATA_CENTER' AND target_data_type IS NULL)
           OR (template_scope = 'TEMPLATE_CENTER' AND target_data_type IS NOT NULL)
    ) THEN
        RAISE EXCEPTION 'template_scope and target_data_type contain inconsistent records';
    END IF;
END $$;

DROP INDEX IF EXISTS idx_template_version_data_scope;

ALTER TABLE tpl.template_version
    DROP CONSTRAINT IF EXISTS ck_template_version_scope_target,
    DROP COLUMN IF EXISTS template_scope;

ALTER TABLE tpl.template
    DROP COLUMN IF EXISTS purpose;
