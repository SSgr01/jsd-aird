CREATE SCHEMA IF NOT EXISTS agent_runtime;
CREATE SCHEMA IF NOT EXISTS agent_checkpoint;
CREATE SCHEMA IF NOT EXISTS mock_domain;
CREATE TABLE IF NOT EXISTS agent_runtime.conversations (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL, actor_id uuid NOT NULL,
    thread_id text NOT NULL UNIQUE, last_run_id uuid,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS agent_runtime.runs (
    id uuid PRIMARY KEY, conversation_id uuid NOT NULL REFERENCES agent_runtime.conversations(id),
    organization_id uuid NOT NULL, actor_id uuid NOT NULL, project_id uuid,
    request text NOT NULL, request_hash text NOT NULL, idempotency_key text NOT NULL,
    status text NOT NULL CHECK(status IN ('QUEUED','RUNNING','WAITING_CLARIFICATION','WAITING_APPROVAL','SUCCEEDED','FAILED','CANCELLED')),
    result jsonb, error jsonb, interrupt jsonb, resume jsonb, plan jsonb,
    worker_id text, lease_epoch bigint NOT NULL DEFAULT 0, lease_until timestamptz,
    cancel_requested boolean NOT NULL DEFAULT false,
    active_seconds double precision NOT NULL DEFAULT 0,
    model_calls integer NOT NULL DEFAULT 0, tool_calls integer NOT NULL DEFAULT 0,
    graph_version text NOT NULL DEFAULT 'project-assistant-v1',
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(organization_id,actor_id,idempotency_key)
);
CREATE UNIQUE INDEX IF NOT EXISTS one_active_run_per_conversation ON agent_runtime.runs(conversation_id)
 WHERE status IN ('QUEUED','RUNNING','WAITING_CLARIFICATION','WAITING_APPROVAL');
CREATE INDEX IF NOT EXISTS run_queue ON agent_runtime.runs(status,lease_until,created_at);
CREATE TABLE IF NOT EXISTS agent_runtime.steps (
    run_id uuid NOT NULL REFERENCES agent_runtime.runs(id), step_id text NOT NULL,
    status text NOT NULL, result jsonb, error jsonb, updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(run_id,step_id)
);
CREATE TABLE IF NOT EXISTS agent_runtime.evidence (
    id text PRIMARY KEY, run_id uuid NOT NULL REFERENCES agent_runtime.runs(id), payload jsonb NOT NULL
);
CREATE TABLE IF NOT EXISTS agent_runtime.events (
    seq bigserial PRIMARY KEY, run_id uuid NOT NULL REFERENCES agent_runtime.runs(id),
    event_key text NOT NULL, kind text NOT NULL, payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(run_id,event_key)
);
CREATE INDEX IF NOT EXISTS event_stream ON agent_runtime.events(run_id,seq);
CREATE TABLE IF NOT EXISTS agent_runtime.calls (
    id bigserial PRIMARY KEY, run_id uuid NOT NULL REFERENCES agent_runtime.runs(id),
    step_id text NOT NULL, call_kind text NOT NULL, request_hash text NOT NULL,
    status text NOT NULL, result jsonb, usage jsonb NOT NULL DEFAULT '{}', error_code text,
    attempts integer NOT NULL DEFAULT 0, updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(run_id,step_id,call_kind,request_hash)
);
CREATE TABLE IF NOT EXISTS agent_runtime.workers (
    id text PRIMARY KEY, heartbeat_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS mock_domain.projects (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL, name text NOT NULL, version bigint NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS mock_domain.stages (
    id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES mock_domain.projects(id), name text NOT NULL
);
CREATE TABLE IF NOT EXISTS mock_domain.tasks (
    id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES mock_domain.projects(id),
    stage_id uuid NOT NULL REFERENCES mock_domain.stages(id), name text NOT NULL, owner_name text NOT NULL,
    planned_date date NOT NULL, status text NOT NULL, version bigint NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS mock_domain.documents (
    id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES mock_domain.projects(id),
    title text NOT NULL, content text NOT NULL, version bigint NOT NULL DEFAULT 1
);
CREATE TABLE IF NOT EXISTS mock_domain.actions (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL, project_id uuid NOT NULL,
    run_id uuid NOT NULL REFERENCES agent_runtime.runs(id), conversation_id uuid NOT NULL,
    actor_id uuid NOT NULL, approver_id uuid, step_id text NOT NULL, tool text NOT NULL,
    target_id uuid NOT NULL REFERENCES mock_domain.tasks(id), arguments jsonb NOT NULL,
    request_hash text NOT NULL, expected_version bigint NOT NULL, risk text NOT NULL,
    before_state jsonb NOT NULL, idempotency_key text NOT NULL UNIQUE,
    approval_status text NOT NULL DEFAULT 'PENDING', execution_status text NOT NULL DEFAULT 'PENDING',
    created_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL,
    decided_at timestamptz, consumed_at timestamptz, receipt jsonb,
    UNIQUE(run_id,step_id)
);
