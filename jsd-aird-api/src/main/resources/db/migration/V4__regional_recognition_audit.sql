CREATE TABLE tpl.recognition_run (
    id uuid PRIMARY KEY,
    import_job_id uuid NOT NULL REFERENCES tpl.template_import_job(id) ON DELETE CASCADE,
    scope varchar(24) NOT NULL DEFAULT 'WORKBOOK',
    status varchar(24) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED')),
    structure_version integer NOT NULL,
    snapshot_format_version integer NOT NULL,
    region_count integer NOT NULL DEFAULT 0,
    call_count integer NOT NULL DEFAULT 0,
    succeeded_call_count integer NOT NULL DEFAULT 0,
    failed_call_count integer NOT NULL DEFAULT 0,
    prompt_tokens bigint NOT NULL DEFAULT 0,
    completion_tokens bigint NOT NULL DEFAULT 0,
    total_tokens bigint NOT NULL DEFAULT 0,
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_recognition_run_job
    ON tpl.recognition_run (import_job_id, created_at DESC);

CREATE TABLE tpl.recognition_call (
    id uuid PRIMARY KEY,
    recognition_run_id uuid NOT NULL REFERENCES tpl.recognition_run(id) ON DELETE CASCADE,
    region_id varchar(96) NOT NULL,
    attempt integer NOT NULL DEFAULT 1,
    provider varchar(100) NOT NULL,
    model varchar(100) NOT NULL,
    prompt_version varchar(80) NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED')),
    http_status integer,
    started_at timestamptz NOT NULL,
    finished_at timestamptz NOT NULL,
    duration_ms bigint NOT NULL CHECK (duration_ms >= 0),
    prompt_tokens integer NOT NULL DEFAULT 0,
    completion_tokens integer NOT NULL DEFAULT 0,
    total_tokens integer NOT NULL DEFAULT 0,
    request_payload_gzip bytea,
    response_payload_gzip bytea,
    request_hash char(64) NOT NULL,
    response_hash char(64),
    error_type varchar(160),
    error_message text,
    payload_expires_at timestamptz NOT NULL DEFAULT (now() + interval '90 days'),
    payload_purged_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (recognition_run_id, region_id, attempt)
);

CREATE INDEX idx_recognition_call_expiry
    ON tpl.recognition_call (payload_expires_at)
    WHERE payload_purged_at IS NULL;

ALTER TABLE tpl.recognition_suggestion
    ADD COLUMN recognition_run_id uuid REFERENCES tpl.recognition_run(id) ON DELETE SET NULL,
    ADD COLUMN recognition_call_id uuid REFERENCES tpl.recognition_call(id) ON DELETE SET NULL,
    ADD COLUMN region_id varchar(96);

CREATE INDEX idx_recognition_suggestion_region
    ON tpl.recognition_suggestion (recognition_run_id, region_id, confidence DESC);
