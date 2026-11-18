-- Titles describe documents; UUID identifies them. Content deduplication remains unchanged.
ALTER TABLE kb.document
    DROP CONSTRAINT IF EXISTS document_organization_id_title_current_version_no_key;

CREATE INDEX IF NOT EXISTS idx_kb_document_org_title
    ON kb.document (organization_id, title);
