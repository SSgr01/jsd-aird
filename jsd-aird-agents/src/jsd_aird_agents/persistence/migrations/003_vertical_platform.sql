ALTER TABLE agent_runtime.runs ADD COLUMN pack_ids jsonb NOT NULL DEFAULT '["project"]';
ALTER TABLE agent_runtime.runs ADD COLUMN pack_versions jsonb NOT NULL DEFAULT '{}';
ALTER TABLE agent_runtime.runs ADD COLUMN resource_scopes jsonb NOT NULL DEFAULT '{}';
ALTER TABLE agent_runtime.runs ADD COLUMN workflow_id text;
ALTER TABLE agent_runtime.runs ADD COLUMN budget_limits jsonb NOT NULL DEFAULT '{}';
UPDATE agent_runtime.runs SET resource_scopes=jsonb_build_object('project',project_id::text) WHERE project_id IS NOT NULL;

CREATE TABLE mock_domain.quality_records (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    dataset_id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    title text NOT NULL,
    summary text NOT NULL
);
CREATE INDEX quality_scope ON mock_domain.quality_records(organization_id,dataset_id);

CREATE TABLE agent_runtime.schedules (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    actor_id uuid NOT NULL,
    pack_id text NOT NULL,
    job_id text NOT NULL,
    workflow_id text NOT NULL,
    pack_versions jsonb NOT NULL,
    scope_id uuid NOT NULL,
    request text NOT NULL,
    interval_seconds integer NOT NULL CHECK(interval_seconds > 0),
    event_type text,
    status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','PAUSED','CANCELLED','EXHAUSTED','FAILED')),
    next_fire_at timestamptz NOT NULL,
    max_runs integer NOT NULL CHECK(max_runs > 0),
    model_call_budget integer NOT NULL CHECK(model_call_budget > 0),
    tool_call_budget integer NOT NULL CHECK(tool_call_budget > 0),
    max_attempts integer NOT NULL CHECK(max_attempts > 0),
    idempotency_key text NOT NULL,
    request_hash text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(organization_id,actor_id,idempotency_key)
);
CREATE TABLE agent_runtime.schedule_occurrences (
    id uuid PRIMARY KEY,
    schedule_id uuid NOT NULL REFERENCES agent_runtime.schedules(id),
    trigger_key text NOT NULL,
    due_at timestamptz NOT NULL,
    status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','DISPATCHED','SUCCEEDED','FAILED','CANCELLED')),
    run_id uuid REFERENCES agent_runtime.runs(id),
    attempts integer NOT NULL DEFAULT 0,
    error_code text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(schedule_id,trigger_key)
);
CREATE TABLE agent_runtime.schedule_audit (
    seq bigserial PRIMARY KEY,
    schedule_id uuid NOT NULL REFERENCES agent_runtime.schedules(id),
    actor_id uuid NOT NULL,
    kind text NOT NULL,
    detail jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE agent_runtime.schedulers (
    id text PRIMARY KEY,
    heartbeat_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE agent_runtime.artifacts (
    id text PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES agent_runtime.runs(id),
    organization_id uuid NOT NULL,
    actor_id uuid NOT NULL,
    pack_id text NOT NULL,
    media_type text NOT NULL,
    content text NOT NULL,
    sha256 text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
