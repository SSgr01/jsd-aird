CREATE TABLE spc.analysis_event (
    id bigserial PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    analysis_run_id uuid NOT NULL REFERENCES spc.analysis_run(id) ON DELETE CASCADE,
    event_type varchar(40) NOT NULL,
    payload_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_spc_analysis_event_stream
    ON spc.analysis_event (organization_id, analysis_run_id, id);
