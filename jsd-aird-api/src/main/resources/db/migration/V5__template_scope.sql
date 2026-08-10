ALTER TABLE tpl.template_version
    ADD COLUMN template_scope varchar(32) NOT NULL DEFAULT 'TEMPLATE_CENTER'
        CHECK (template_scope IN ('TEMPLATE_CENTER', 'DATA_CENTER')),
    ADD COLUMN target_data_type varchar(64)
        CHECK (target_data_type IS NULL OR target_data_type IN (
            'MATERIAL', 'FORMULA', 'PROCESS', 'EQUIPMENT', 'TEST_STANDARD'
        ));

ALTER TABLE tpl.template_version
    ADD CONSTRAINT ck_template_version_scope_target
        CHECK (
            (template_scope = 'TEMPLATE_CENTER' AND target_data_type IS NULL)
            OR (template_scope = 'DATA_CENTER' AND target_data_type IS NOT NULL)
        );

CREATE INDEX idx_template_version_data_scope
    ON tpl.template_version (template_scope, target_data_type, status, updated_at DESC);
