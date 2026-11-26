ALTER TABLE rnd.research_test_upload ADD COLUMN record_type varchar(24);

UPDATE rnd.research_test_upload u
SET record_type = r.record_type
FROM rnd.research_test_record r
WHERE r.id = u.record_id AND r.organization_id = u.organization_id;

ALTER TABLE rnd.research_test_upload
    ALTER COLUMN record_type SET NOT NULL,
    ADD CONSTRAINT research_test_upload_record_type_check CHECK (record_type IN ('REPORT', 'STANDARD'));

DROP INDEX rnd.uq_research_test_upload_content;
CREATE UNIQUE INDEX uq_research_test_upload_content
    ON rnd.research_test_upload(organization_id, record_type, sha256)
    WHERE sha256 IS NOT NULL AND deleted = false;
