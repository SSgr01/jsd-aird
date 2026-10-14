CREATE TABLE ai.research_run (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    run_type varchar(40) NOT NULL,
    mode varchar(32) NOT NULL,
    status varchar(24) NOT NULL,
    task_profile_code varchar(100) NOT NULL,
    analysis_profile_version varchar(100),
    idempotency_key varchar(200) NOT NULL,
    request_hash char(64) NOT NULL,
    request_jsonb jsonb NOT NULL,
    result_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    error_code varchar(80),
    error_message text,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_by_name varchar(160),
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    finished_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT research_run_type_check CHECK (run_type IN ('FORMULA_PREDICTION', 'EXPERIMENT_OPTIMIZATION')),
    CONSTRAINT research_run_mode_check CHECK (mode IN ('CASE_STAT_RULE', 'MODEL', 'HYBRID')),
    CONSTRAINT research_run_status_check CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED')),
    UNIQUE (organization_id, idempotency_key)
);

CREATE INDEX idx_research_run_org_created
    ON ai.research_run(organization_id, created_at DESC);

CREATE TABLE ai.research_candidate (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    research_run_id uuid NOT NULL REFERENCES ai.research_run(id) ON DELETE CASCADE,
    candidate_no integer NOT NULL,
    strategy varchar(32) NOT NULL,
    title varchar(200) NOT NULL,
    formula_jsonb jsonb NOT NULL,
    process_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    estimates_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    rule_check_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    evidence_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    confidence varchar(16) NOT NULL,
    score numeric(18,8),
    content_hash char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT research_candidate_strategy_check CHECK (strategy IN ('CONTROL', 'REFERENCE', 'CONSERVATIVE', 'BALANCED', 'EXPLORATORY')),
    CONSTRAINT research_candidate_confidence_check CHECK (confidence IN ('NONE', 'LOW', 'MEDIUM')),
    UNIQUE (research_run_id, candidate_no),
    UNIQUE (research_run_id, content_hash)
);

CREATE INDEX idx_research_candidate_run
    ON ai.research_candidate(organization_id, research_run_id, candidate_no);

CREATE TABLE ai.research_experiment_link (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    research_run_id uuid NOT NULL REFERENCES ai.research_run(id) ON DELETE CASCADE,
    research_candidate_id uuid NOT NULL REFERENCES ai.research_candidate(id) ON DELETE CASCADE,
    experiment_id uuid NOT NULL,
    experiment_version_id uuid NOT NULL,
    experiment_no varchar(80) NOT NULL,
    idempotency_key varchar(200) NOT NULL,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, idempotency_key),
    UNIQUE (organization_id, research_candidate_id)
);

CREATE INDEX idx_research_experiment_link_experiment
    ON ai.research_experiment_link(organization_id, experiment_id);

COMMENT ON TABLE ai.research_run IS
    'Immutable AI formulation prediction or experiment optimization request and result snapshot';
COMMENT ON TABLE ai.research_candidate IS
    'Rule-checked candidate formulation or next experiment produced by one research run';
COMMENT ON TABLE ai.research_experiment_link IS
    'Auditable link from an AI research candidate to the user-created ELN draft and later feedback';
