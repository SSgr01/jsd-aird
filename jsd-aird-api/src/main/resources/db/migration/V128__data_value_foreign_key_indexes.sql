-- Foreign-key maintenance needs indexes independent of the partial locator
-- uniqueness constraint. Without them, deleting records/anchors repeatedly
-- scans all values (including ON DELETE SET NULL for each source anchor).
CREATE INDEX IF NOT EXISTS idx_data_value_record_id
    ON data.data_value (record_id);

CREATE INDEX IF NOT EXISTS idx_data_value_source_anchor_id
    ON data.data_value (source_anchor_id)
    WHERE source_anchor_id IS NOT NULL;
