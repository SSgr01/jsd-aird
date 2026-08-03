ALTER TABLE tpl.recognition_call
    ADD COLUMN IF NOT EXISTS phase varchar(40) NOT NULL DEFAULT 'REGION_INFERENCE',
    ADD COLUMN IF NOT EXISTS parent_call_id uuid REFERENCES tpl.recognition_call(id) ON DELETE SET NULL;

ALTER TABLE tpl.recognition_call
    DROP CONSTRAINT IF EXISTS recognition_call_recognition_run_id_region_id_attempt_key;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uq_recognition_call_phase_attempt'
          AND conrelid = 'tpl.recognition_call'::regclass
    ) THEN
        ALTER TABLE tpl.recognition_call
            ADD CONSTRAINT uq_recognition_call_phase_attempt
                UNIQUE (recognition_run_id, region_id, phase, attempt);
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS tpl.template_quality_issue (
    id uuid PRIMARY KEY,
    import_job_id uuid NOT NULL REFERENCES tpl.template_import_job(id) ON DELETE CASCADE,
    recognition_run_id uuid NOT NULL REFERENCES tpl.recognition_run(id) ON DELETE CASCADE,
    recognition_call_id uuid REFERENCES tpl.recognition_call(id) ON DELETE SET NULL,
    region_id varchar(96),
    issue_type varchar(80) NOT NULL,
    severity varchar(16) NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'BLOCKER')),
    confidence numeric(5,4) NOT NULL,
    sheet_id varchar(160),
    sheet_name varchar(200),
    address varchar(80) NOT NULL,
    title varchar(240) NOT NULL,
    description text NOT NULL,
    business_impact text NOT NULL,
    evidence_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    suggested_patch_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    inverse_patch_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    auto_fixable boolean NOT NULL DEFAULT false,
    status varchar(24) NOT NULL CHECK (
        status IN ('DETECTED', 'AUTO_APPLIED', 'CONFIRMED', 'IGNORED', 'ROLLED_BACK', 'FAILED')
    ),
    before_snapshot_hash char(64),
    after_snapshot_hash char(64),
    decided_by uuid REFERENCES iam.app_user(id),
    decided_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_template_quality_issue_run
    ON tpl.template_quality_issue (recognition_run_id, status, severity, created_at);

CREATE INDEX IF NOT EXISTS idx_template_quality_issue_location
    ON tpl.template_quality_issue (import_job_id, sheet_id, address);
