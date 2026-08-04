ALTER TABLE tpl.recognition_run
    ADD COLUMN IF NOT EXISTS recognition_protocol_version integer NOT NULL DEFAULT 1;

ALTER TABLE tpl.recognition_suggestion
    ADD COLUMN IF NOT EXISTS relation_id varchar(64),
    ADD COLUMN IF NOT EXISTS block_id varchar(64);

ALTER TABLE tpl.template_quality_issue
    ADD COLUMN IF NOT EXISTS root_block_id varchar(64),
    ADD COLUMN IF NOT EXISTS customer_issue_category varchar(80);

UPDATE tpl.template_quality_issue
SET root_block_id = COALESCE(NULLIF(root_block_id, ''), NULLIF(region_id, ''), 'sheet-root'),
    customer_issue_category = COALESCE(NULLIF(customer_issue_category, ''), issue_type)
WHERE root_block_id IS NULL OR customer_issue_category IS NULL;

CREATE INDEX IF NOT EXISTS idx_recognition_suggestion_relation
    ON tpl.recognition_suggestion (recognition_run_id, relation_id)
    WHERE relation_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_template_quality_issue_customer_root
    ON tpl.template_quality_issue (
        recognition_run_id, sheet_id, root_block_id, customer_issue_category, status
    );
