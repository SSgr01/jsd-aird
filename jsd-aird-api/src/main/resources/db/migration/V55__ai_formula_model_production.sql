INSERT INTO iam.permission_definition (code, module, name, risk, default_scope)
VALUES ('ai.model.manage', 'ai', '配方模型管理', 'CRITICAL', 'ALL')
ON CONFLICT (code) DO UPDATE SET module = EXCLUDED.module,
    name = EXCLUDED.name, risk = EXCLUDED.risk, default_scope = EXCLUDED.default_scope,
    enabled = TRUE, updated_at = now(),
    definition_version = iam.permission_definition.definition_version + 1;

CREATE TABLE ai.formulation_task_profile (
    id uuid PRIMARY KEY,
    organization_id uuid REFERENCES iam.organization(id),
    profile_code varchar(100) NOT NULL,
    profile_version varchar(40) NOT NULL,
    status varchar(24) NOT NULL,
    contract_version varchar(60) NOT NULL,
    schema_hash char(64) NOT NULL,
    content_hash char(64) NOT NULL,
    profile_jsonb jsonb NOT NULL,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    retired_at timestamptz,
    CONSTRAINT formulation_task_profile_status_check
        CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT formulation_task_profile_hash_check
        CHECK (schema_hash ~ '^[0-9a-f]{64}$' AND content_hash ~ '^[0-9a-f]{64}$')
);

ALTER TABLE ai.research_candidate
    ADD COLUMN model_context_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb;

CREATE UNIQUE INDEX uq_formulation_task_profile_scope_version
    ON ai.formulation_task_profile (COALESCE(organization_id, '00000000-0000-0000-0000-000000000000'::uuid),
                                        profile_code, profile_version);

CREATE TABLE ai.formula_model_snapshot (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    task_profile_id uuid NOT NULL REFERENCES ai.formulation_task_profile(id),
    status varchar(24) NOT NULL,
    schema_version varchar(20) NOT NULL,
    data_nature varchar(24) NOT NULL,
    snapshot_purpose varchar(24) NOT NULL,
    snapshot_hash char(64),
    validation_folds_hash char(64),
    t06_baseline_hash char(64),
    object_prefix varchar(500) NOT NULL,
    artifacts_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    target_summary_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    row_count integer NOT NULL DEFAULT 0,
    error_code varchar(100),
    error_message text,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CONSTRAINT formula_model_snapshot_status_check
        CHECK (status IN ('BUILDING', 'READY', 'FAILED')),
    CONSTRAINT formula_model_snapshot_schema_check CHECK (schema_version IN ('1.0', '1.1')),
    CONSTRAINT formula_model_snapshot_nature_check CHECK (data_nature IN ('REAL', 'SYNTHETIC')),
    CONSTRAINT formula_model_snapshot_purpose_check CHECK (snapshot_purpose IN ('TRAINING', 'DEVELOPMENT')),
    CONSTRAINT formula_model_snapshot_row_count_check CHECK (row_count >= 0)
);

CREATE INDEX idx_formula_model_snapshot_org_created
    ON ai.formula_model_snapshot (organization_id, created_at DESC);

CREATE TABLE ai.formula_model_version (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    task_profile_id uuid NOT NULL REFERENCES ai.formulation_task_profile(id),
    snapshot_id uuid NOT NULL REFERENCES ai.formula_model_snapshot(id),
    status varchar(24) NOT NULL,
    model_bundle_hash char(64),
    model_bundle_key varchar(500),
    model_card_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    training_result_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    error_code varchar(100),
    error_message text,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CONSTRAINT formula_model_version_status_check
        CHECK (status IN ('BUILDING', 'CANDIDATE', 'FAILED', 'RETIRED'))
);

CREATE INDEX idx_formula_model_version_org_profile_created
    ON ai.formula_model_version (organization_id, task_profile_id, created_at DESC);

CREATE TABLE ai.formula_model_target (
    model_version_id uuid NOT NULL REFERENCES ai.formula_model_version(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    target_key varchar(300) NOT NULL,
    target_code varchar(160) NOT NULL,
    value_type varchar(32) NOT NULL,
    status varchar(32) NOT NULL,
    production_eligible boolean NOT NULL DEFAULT false,
    scorer_type varchar(60),
    primary_metric_name varchar(80),
    primary_metric_value numeric(24,12),
    baseline_metric_value numeric(24,12),
    baseline_improvement numeric(24,12),
    reasons_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    result_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (model_version_id, target_key),
    CONSTRAINT formula_model_target_value_type_check
        CHECK (value_type IN ('CONTINUOUS', 'ORDINAL', 'BINARY', 'CATEGORICAL', 'CENSORED_COUNT')),
    CONSTRAINT formula_model_target_status_check
        CHECK (status IN ('QUALIFIED', 'NOT_QUALIFIED', 'FAILED', 'UNSUPPORTED'))
);

CREATE INDEX idx_formula_model_target_org_key
    ON ai.formula_model_target (organization_id, target_key, production_eligible);

CREATE TABLE ai.formula_model_activation (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    task_profile_id uuid NOT NULL REFERENCES ai.formulation_task_profile(id),
    target_key varchar(300) NOT NULL,
    model_version_id uuid NOT NULL REFERENCES ai.formula_model_version(id),
    previous_model_version_id uuid REFERENCES ai.formula_model_version(id),
    status varchar(24) NOT NULL,
    activation_reason text,
    activated_by uuid NOT NULL REFERENCES iam.app_user(id),
    activated_at timestamptz NOT NULL DEFAULT now(),
    ended_at timestamptz,
    consecutive_failure_count integer NOT NULL DEFAULT 0,
    last_failure_at timestamptz,
    CONSTRAINT formula_model_activation_status_check CHECK (status IN ('ACTIVE', 'ROLLED_BACK', 'REPLACED')),
    CONSTRAINT formula_model_activation_failure_count_check CHECK (consecutive_failure_count >= 0)
);

CREATE UNIQUE INDEX uq_formula_model_activation_active_target
    ON ai.formula_model_activation (organization_id, task_profile_id, target_key)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_formula_model_activation_history
    ON ai.formula_model_activation (organization_id, target_key, activated_at DESC);

COMMENT ON TABLE ai.formulation_task_profile IS
    'Versioned formulation model task profile; platform profiles may have a null organization_id';
COMMENT ON TABLE ai.formula_model_snapshot IS
    'Immutable REAL or SYNTHETIC snapshot and validation artifact manifest';
COMMENT ON TABLE ai.formula_model_version IS
    'Immutable trained model bundle and model card produced from one snapshot';
COMMENT ON TABLE ai.formula_model_target IS
    'Per-target training result and production eligibility for one model version';
COMMENT ON TABLE ai.formula_model_activation IS
    'Append-only per-target activation and rollback history';

UPDATE iam.role SET policy_version = policy_version + 1;
UPDATE iam.app_user SET auth_version = auth_version + 1;
