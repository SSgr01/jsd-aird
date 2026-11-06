-- Allow review approval before publication in existing research-test tables.
ALTER TABLE rnd.research_test_record DROP CONSTRAINT IF EXISTS research_test_record_status_check;
ALTER TABLE rnd.research_test_record ADD CONSTRAINT research_test_record_status_check
    CHECK (status IN ('DRAFT','PENDING_REVIEW','RETURNED','APPROVED','PUBLISHED','ARCHIVED'));

ALTER TABLE rnd.research_test_version DROP CONSTRAINT IF EXISTS research_test_version_status_check;
ALTER TABLE rnd.research_test_version ADD CONSTRAINT research_test_version_status_check
    CHECK (status IN ('DRAFT','PENDING_REVIEW','RETURNED','APPROVED','PUBLISHED','ARCHIVED'));
