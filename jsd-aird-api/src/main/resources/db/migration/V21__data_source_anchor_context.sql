ALTER TABLE data.source_anchor
    ADD COLUMN IF NOT EXISTS binding_id varchar(160),
    ADD COLUMN IF NOT EXISTS value_path text,
    ADD COLUMN IF NOT EXISTS label_path text,
    ADD COLUMN IF NOT EXISTS value_source varchar(40),
    ADD COLUMN IF NOT EXISTS value_status varchar(40);

CREATE INDEX IF NOT EXISTS idx_data_source_anchor_binding
    ON data.source_anchor (asset_revision_id, binding_id);
