CREATE SCHEMA IF NOT EXISTS agent_runtime;
CREATE TABLE IF NOT EXISTS agent_runtime.harness_records (
    kind text NOT NULL, id uuid NOT NULL, organization_id uuid NOT NULL, actor_id uuid NOT NULL,
    revision bigint NOT NULL DEFAULT 1, payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(kind,id)
);
CREATE INDEX IF NOT EXISTS harness_record_owner ON agent_runtime.harness_records(organization_id,actor_id,kind);
CREATE TABLE IF NOT EXISTS agent_runtime.harness_checkpoints (
    organization_id uuid NOT NULL, actor_id uuid NOT NULL, sha256 text NOT NULL, content text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(organization_id,actor_id,sha256)
);
CREATE TABLE IF NOT EXISTS agent_runtime.harness_events (
    seq bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, run_id uuid NOT NULL,
    organization_id uuid NOT NULL, actor_id uuid NOT NULL, event_key text NOT NULL,
    kind text NOT NULL, payload jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(run_id,event_key)
);
CREATE INDEX IF NOT EXISTS harness_run_events ON agent_runtime.harness_events(run_id,seq);
CREATE TABLE IF NOT EXISTS agent_runtime.harness_idempotency (
    organization_id uuid NOT NULL, actor_id uuid NOT NULL, operation text NOT NULL, key text NOT NULL,
    request_hash text NOT NULL, response jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(organization_id,actor_id,operation,key)
);
CREATE TABLE IF NOT EXISTS agent_runtime.harness_calls (
    run_id uuid NOT NULL, call_id text NOT NULL, request_hash text NOT NULL,
    status text NOT NULL CHECK(status IN ('RUNNING','SUCCEEDED','FAILED','UNKNOWN')),
    result jsonb, updated_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(run_id,call_id)
);
CREATE INDEX IF NOT EXISTS harness_call_request ON agent_runtime.harness_calls(run_id,request_hash);
ALTER TABLE agent_runtime.harness_calls ADD COLUMN IF NOT EXISTS tool_name text NOT NULL DEFAULT 'legacy-unknown';
