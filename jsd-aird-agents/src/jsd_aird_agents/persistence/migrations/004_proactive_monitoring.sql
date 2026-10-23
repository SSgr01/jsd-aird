ALTER TABLE agent_runtime.runs ADD COLUMN monitoring jsonb NOT NULL DEFAULT '{}';
ALTER TABLE agent_runtime.schedules ADD COLUMN monitoring jsonb NOT NULL DEFAULT '{}';
ALTER TABLE agent_runtime.schedules ADD COLUMN trigger_spec jsonb;
ALTER TABLE agent_runtime.schedules ALTER COLUMN next_fire_at DROP NOT NULL;

ALTER TABLE mock_domain.projects ADD COLUMN progress_percent numeric CHECK(progress_percent BETWEEN 0 AND 100);
ALTER TABLE mock_domain.projects ADD COLUMN planned_progress_percent numeric CHECK(planned_progress_percent BETWEEN 0 AND 100);
ALTER TABLE mock_domain.stages ADD COLUMN progress_percent numeric CHECK(progress_percent BETWEEN 0 AND 100);
ALTER TABLE mock_domain.stages ADD COLUMN planned_progress_percent numeric CHECK(planned_progress_percent BETWEEN 0 AND 100);
ALTER TABLE mock_domain.tasks ADD COLUMN owner_id uuid;
ALTER TABLE mock_domain.tasks ADD COLUMN progress_percent numeric CHECK(progress_percent BETWEEN 0 AND 100);
ALTER TABLE mock_domain.tasks ADD COLUMN last_progress_at timestamptz;
ALTER TABLE mock_domain.tasks ADD COLUMN completed_at timestamptz;
ALTER TABLE mock_domain.documents ADD COLUMN publication_status text NOT NULL DEFAULT 'DRAFT';
CREATE TABLE mock_domain.milestones (
    id uuid PRIMARY KEY,project_id uuid NOT NULL REFERENCES mock_domain.projects(id),name text NOT NULL,
    due_date date NOT NULL,status text NOT NULL DEFAULT 'PENDING',version bigint NOT NULL DEFAULT 0
);
CREATE TABLE mock_domain.milestone_requirements (
    id uuid PRIMARY KEY,milestone_id uuid NOT NULL REFERENCES mock_domain.milestones(id),
    resource_kind text NOT NULL CHECK(resource_kind IN ('TASK','DOCUMENT')),resource_id uuid NOT NULL
);

CREATE TABLE agent_runtime.monitoring_batches (
    run_id uuid PRIMARY KEY REFERENCES agent_runtime.runs(id),organization_id uuid NOT NULL,actor_id uuid NOT NULL,
    pack_id text NOT NULL,scope_id uuid NOT NULL,workflow_id text NOT NULL,rule_version text NOT NULL,
    config_sha256 text NOT NULL,observed_at timestamptz NOT NULL,snapshot jsonb NOT NULL,report jsonb NOT NULL
);
CREATE INDEX monitoring_history ON agent_runtime.monitoring_batches(organization_id,actor_id,pack_id,scope_id,observed_at);
CREATE TABLE agent_runtime.monitoring_findings (
    id uuid PRIMARY KEY,organization_id uuid NOT NULL,actor_id uuid NOT NULL,pack_id text NOT NULL,scope_id uuid NOT NULL,
    rule_id text NOT NULL,rule_version text NOT NULL,resource_id text NOT NULL,
    status text NOT NULL CHECK(status IN ('OPEN','RESOLVED')),severity text NOT NULL,
    episode integer NOT NULL DEFAULT 1,first_seen_at timestamptz NOT NULL,last_seen_at timestamptz NOT NULL,
    last_run_id uuid NOT NULL REFERENCES agent_runtime.runs(id),payload jsonb NOT NULL
);
CREATE TABLE agent_runtime.notification_outbox (
    id uuid PRIMARY KEY,organization_id uuid NOT NULL,actor_id uuid NOT NULL,recipient_id uuid NOT NULL,
    pack_id text NOT NULL,scope_id uuid NOT NULL,run_id uuid NOT NULL REFERENCES agent_runtime.runs(id),
    finding_id uuid REFERENCES agent_runtime.monitoring_findings(id),dedupe_key text NOT NULL UNIQUE,
    audience text NOT NULL,status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','DELIVERED','SUPPRESSED')),
    available_at timestamptz NOT NULL,expires_at timestamptz,payload jsonb NOT NULL,
    policy jsonb NOT NULL,reason text,created_at timestamptz NOT NULL DEFAULT now(),delivered_at timestamptz
);
CREATE INDEX notification_due ON agent_runtime.notification_outbox(status,available_at);
CREATE TABLE agent_runtime.notifications (
    id uuid PRIMARY KEY REFERENCES agent_runtime.notification_outbox(id),organization_id uuid NOT NULL,recipient_id uuid NOT NULL,
    pack_id text NOT NULL,scope_id uuid NOT NULL,payload jsonb NOT NULL,created_at timestamptz NOT NULL,read_at timestamptz
);

CREATE TABLE mock_domain.task_dependencies (
    task_id uuid NOT NULL REFERENCES mock_domain.tasks(id),depends_on uuid NOT NULL REFERENCES mock_domain.tasks(id),
    PRIMARY KEY(task_id,depends_on),CHECK(task_id<>depends_on)
);
ALTER TABLE agent_runtime.monitoring_batches ADD COLUMN projected_at timestamptz;
