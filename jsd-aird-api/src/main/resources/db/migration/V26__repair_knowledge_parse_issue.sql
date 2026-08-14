-- V22 created this table, but some environments already recorded V22/V25 in
-- flyway_schema_history while the physical table was absent.  Do not edit V22:
-- a forward, idempotent repair is required for those databases.

CREATE TABLE IF NOT EXISTS kb.document_parse_issue (
    id uuid PRIMARY KEY,
    parse_run_id uuid NOT NULL REFERENCES kb.document_parse_run(id) ON DELETE CASCADE,
    parse_block_id uuid REFERENCES kb.document_parse_block(id) ON DELETE CASCADE,
    extract_field_id uuid REFERENCES kb.document_extract_field(id) ON DELETE CASCADE,
    issue_code varchar(80) NOT NULL,
    severity varchar(16) NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'BLOCKER')),
    message varchar(1000) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED', 'IGNORED')),
    resolution varchar(1000),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Keep the repair safe if an operator created a partial shell of the table
-- before deploying this migration.  Existing rows are not rewritten here.
ALTER TABLE kb.document_parse_issue
    ADD COLUMN IF NOT EXISTS id uuid,
    ADD COLUMN IF NOT EXISTS parse_run_id uuid,
    ADD COLUMN IF NOT EXISTS parse_block_id uuid,
    ADD COLUMN IF NOT EXISTS extract_field_id uuid,
    ADD COLUMN IF NOT EXISTS issue_code varchar(80),
    ADD COLUMN IF NOT EXISTS severity varchar(16),
    ADD COLUMN IF NOT EXISTS message varchar(1000),
    ADD COLUMN IF NOT EXISTS status varchar(24) DEFAULT 'OPEN',
    ADD COLUMN IF NOT EXISTS resolution varchar(1000),
    ADD COLUMN IF NOT EXISTS updated_at timestamptz DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_kb_parse_issue_run
    ON kb.document_parse_issue(parse_run_id, status, severity);
