ALTER TABLE tpl.recognition_suggestion
    ADD CONSTRAINT ck_recognition_suggestion_decision
    CHECK (decision IN ('PENDING', 'ACCEPTED', 'REJECTED'));

CREATE INDEX idx_recognition_suggestion_review
    ON tpl.recognition_suggestion (import_job_id, decision, confidence DESC);
