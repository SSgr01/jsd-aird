-- An identical upload may be used to re-parse the same logical document as a
-- new version.  Cross-document duplicates remain rejected at the database
-- boundary.
CREATE OR REPLACE FUNCTION kb.reject_duplicate_version_sha256() RETURNS trigger AS $$
DECLARE
    target_organization_id uuid;
BEGIN
    SELECT organization_id INTO target_organization_id FROM kb.document WHERE id = NEW.document_id;
    IF target_organization_id IS NULL THEN RETURN NEW; END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(target_organization_id::text || ':' || NEW.sha256, 0));
    IF EXISTS (
        SELECT 1
        FROM kb.document_version existing
        JOIN kb.document d ON d.id = existing.document_id
        WHERE d.organization_id = target_organization_id
          AND existing.sha256 = NEW.sha256
          AND existing.document_id <> NEW.document_id
    ) THEN
        RAISE unique_violation USING
            MESSAGE = 'duplicate knowledge file sha256 in organization',
            CONSTRAINT = 'uq_kb_document_version_org_sha256';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
