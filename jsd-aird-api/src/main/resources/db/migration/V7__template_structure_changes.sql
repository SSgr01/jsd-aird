CREATE TABLE IF NOT EXISTS tpl.template_structure_change (
    id uuid PRIMARY KEY,
    template_version_id uuid NOT NULL REFERENCES tpl.template_version(id) ON DELETE CASCADE,
    operation_order integer NOT NULL CHECK (operation_order >= 0),
    operation_type varchar(32) NOT NULL
        CHECK (operation_type IN ('INSERT_ROWS', 'DELETE_ROWS', 'INSERT_COLUMNS', 'DELETE_COLUMNS', 'RENAME_SHEET')),
    sheet_id varchar(160) NOT NULL,
    operation_jsonb jsonb NOT NULL,
    source varchar(16) NOT NULL CHECK (source IN ('CUSTOMER', 'AI')),
    before_mapping_hash char(64) NOT NULL,
    after_mapping_hash char(64) NOT NULL,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_template_structure_change_version
    ON tpl.template_structure_change (template_version_id, created_at, operation_order);
