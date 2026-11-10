CREATE SCHEMA IF NOT EXISTS agent_runtime;
CREATE SEQUENCE IF NOT EXISTS agent_runtime.harness_admission_order;
CREATE TABLE IF NOT EXISTS agent_runtime.harness_admission (
    run_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL, actor_id uuid NOT NULL,
    principal jsonb NOT NULL, origin text NOT NULL CHECK(origin IN ('api','native')),
    state text NOT NULL CHECK(state IN ('QUEUED','GRANTED','RELEASING','RELEASED','CANCELLED')),
    epoch bigint NOT NULL DEFAULT 0, enqueued_at timestamptz NOT NULL DEFAULT now(),
    granted_at timestamptz, lease_until timestamptz,
    last_grant_order bigint NOT NULL DEFAULT 0,
    dispatch_owner uuid, dispatch_until timestamptz,
    dispatch_done boolean NOT NULL DEFAULT false,
    recovery_owner uuid, recovery_until timestamptz,
    retry_at timestamptz NOT NULL DEFAULT now(), attempts integer NOT NULL DEFAULT 0,
    diagnostic jsonb NOT NULL DEFAULT '{}'
);
ALTER TABLE agent_runtime.harness_admission ADD COLUMN IF NOT EXISTS dispatch_done boolean NOT NULL DEFAULT false;
ALTER TABLE agent_runtime.harness_admission ADD COLUMN IF NOT EXISTS recovery_owner uuid;
ALTER TABLE agent_runtime.harness_admission ADD COLUMN IF NOT EXISTS recovery_until timestamptz;
CREATE INDEX IF NOT EXISTS harness_admission_queue ON agent_runtime.harness_admission(state,enqueued_at);
CREATE INDEX IF NOT EXISTS harness_admission_owner ON agent_runtime.harness_admission(organization_id,actor_id,state);
CREATE TABLE IF NOT EXISTS agent_runtime.harness_records (
    kind text NOT NULL, id uuid NOT NULL, organization_id uuid NOT NULL, actor_id uuid NOT NULL,
    revision bigint NOT NULL DEFAULT 1, payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(kind,id)
);
CREATE INDEX IF NOT EXISTS harness_record_owner ON agent_runtime.harness_records(organization_id,actor_id,kind);
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
    run_id uuid NOT NULL, call_id text NOT NULL, request_hash text NOT NULL, tool_name text NOT NULL,
    arguments jsonb NOT NULL DEFAULT '{}', execution_epoch bigint NOT NULL DEFAULT 0,
    status text NOT NULL CHECK(status IN ('RUNNING','SUCCEEDED','FAILED','UNKNOWN')),
    result jsonb, updated_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(run_id,call_id)
);
CREATE INDEX IF NOT EXISTS harness_call_request ON agent_runtime.harness_calls(run_id,request_hash);
CREATE TABLE IF NOT EXISTS agent_runtime.harness_receipts (
    receipt_id uuid PRIMARY KEY, run_id uuid NOT NULL, call_id text NOT NULL,
    organization_id uuid NOT NULL, actor_id uuid NOT NULL,
    authority text NOT NULL, payload jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(run_id, call_id)
);
CREATE TABLE IF NOT EXISTS agent_runtime.harness_reconciliation_audit (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, run_id uuid NOT NULL, call_id text NOT NULL,
    organization_id uuid NOT NULL, actor_id uuid NOT NULL, result text NOT NULL,
    receipt_id uuid, created_at timestamptz NOT NULL DEFAULT now()
);
