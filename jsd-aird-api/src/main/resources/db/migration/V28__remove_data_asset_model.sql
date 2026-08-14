-- Data-center records are owned by the import batch. The former asset and
-- revision tables were an unrelated second identity model and are removed.

ALTER TABLE data.source_anchor
    ADD COLUMN IF NOT EXISTS record_id uuid;

-- Existing projections created one data_record for each former revision.
-- Preserve those source anchors before removing the revision foreign key.
UPDATE data.source_anchor s
SET record_id = r.id
FROM data.data_record r
WHERE s.record_id IS NULL
  AND r.import_job_id = s.import_job_id
  AND r.revision_id = s.asset_revision_id;

UPDATE data.data_record r
SET record_index = ranked.row_no
FROM (
    SELECT id, row_number() OVER (PARTITION BY import_job_id ORDER BY record_index, id) AS row_no
    FROM data.data_record
) ranked
WHERE ranked.id = r.id;

ALTER TABLE data.source_anchor
    DROP CONSTRAINT IF EXISTS source_anchor_asset_revision_id_fkey,
    ALTER COLUMN record_id SET NOT NULL,
    ADD CONSTRAINT fk_data_source_anchor_record
        FOREIGN KEY (record_id) REFERENCES data.data_record(id) ON DELETE CASCADE;

DROP INDEX IF EXISTS data.idx_data_source_anchor_revision;
DROP INDEX IF EXISTS data.idx_data_source_anchor_binding;
ALTER TABLE data.source_anchor DROP COLUMN IF EXISTS asset_revision_id;
CREATE INDEX idx_data_source_anchor_record ON data.source_anchor(record_id, field_code);
CREATE INDEX idx_data_source_anchor_binding ON data.source_anchor(record_id, binding_id);

ALTER TABLE data.data_record
    DROP CONSTRAINT IF EXISTS data_record_revision_id_record_index_key,
    DROP CONSTRAINT IF EXISTS data_record_revision_id_fkey,
    DROP CONSTRAINT IF EXISTS data_record_asset_id_fkey;
DROP INDEX IF EXISTS data.idx_data_record_asset;
ALTER TABLE data.data_record
    DROP COLUMN IF EXISTS asset_id,
    DROP COLUMN IF EXISTS revision_id;
CREATE UNIQUE INDEX uq_data_record_job_index ON data.data_record(import_job_id, record_index);

-- Training datasets retain record IDs, not former revision IDs.
ALTER TABLE ai.training_dataset
    RENAME COLUMN source_revision_ids_jsonb TO source_record_ids_jsonb;
UPDATE ai.training_dataset d
SET source_record_ids_jsonb = coalesce((
    SELECT jsonb_agg(r.id ORDER BY r.record_index)
    FROM data.data_record r
    WHERE r.organization_id = d.organization_id
      AND r.import_job_id = d.import_job_id
), '[]'::jsonb);

DROP TABLE IF EXISTS ai.data_asset_index_entry CASCADE;

DELETE FROM ai.ai_scope_resource
WHERE resource_type IN ('DATA_ASSET', 'DATA_ASSET_REVISION');
DELETE FROM ai.ai_scope
WHERE scope_type = 'DATA_ASSET';
ALTER TABLE ai.ai_scope DROP CONSTRAINT IF EXISTS ai_scope_scope_type_check;
ALTER TABLE ai.ai_scope
    ADD CONSTRAINT ai_scope_scope_type_check
    CHECK (scope_type IN ('PROJECT', 'PRODUCT', 'KNOWLEDGE_BASE'));
ALTER TABLE ai.ai_scope_resource DROP CONSTRAINT IF EXISTS ai_scope_resource_resource_type_check;
ALTER TABLE ai.ai_scope_resource
    ADD CONSTRAINT ai_scope_resource_resource_type_check
    CHECK (resource_type IN ('KNOWLEDGE_DOCUMENT', 'KNOWLEDGE_VERSION'));

DROP TABLE IF EXISTS data.data_asset_revision CASCADE;
DROP TABLE IF EXISTS data.data_asset CASCADE;
