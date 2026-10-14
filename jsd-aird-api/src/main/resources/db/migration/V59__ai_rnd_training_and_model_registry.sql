-- R05/R06: immutable single-target snapshots, recoverable training and model registry.

ALTER TABLE ops.async_job
    ADD COLUMN lease_token uuid,
    ADD COLUMN lease_generation bigint NOT NULL DEFAULT 0;

ALTER TABLE ops.outbox_event
    ADD COLUMN organization_id uuid REFERENCES iam.organization(id),
    ADD COLUMN idempotency_key varchar(200);

CREATE UNIQUE INDEX uq_outbox_event_idempotency
    ON ops.outbox_event(organization_id, idempotency_key)
    WHERE organization_id IS NOT NULL AND idempotency_key IS NOT NULL;

CREATE TABLE ai.training_scheduler_setting (
    organization_id uuid PRIMARY KEY REFERENCES iam.organization(id),
    auto_learning_enabled boolean NOT NULL DEFAULT false,
    last_evaluated_at timestamptz,
    revision bigint NOT NULL DEFAULT 0,
    updated_by uuid REFERENCES iam.app_user(id),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ai.training_schedule_intent (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    target_id uuid NOT NULL,
    target_version_id uuid NOT NULL,
    input_scheme_id uuid NOT NULL,
    eligibility_run_id uuid NOT NULL,
    configuration_fingerprint char(64) NOT NULL,
    fact_high_watermark bigint NOT NULL DEFAULT 0,
    status varchar(24) NOT NULL DEFAULT 'PENDING',
    block_code varchar(100),
    block_detail_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, target_id),
    FOREIGN KEY (organization_id, target_id) REFERENCES ai.prediction_target(organization_id, id),
    FOREIGN KEY (organization_id, target_id, target_version_id)
        REFERENCES ai.target_version(organization_id, target_id, id),
    FOREIGN KEY (organization_id, target_id, input_scheme_id)
        REFERENCES ai.input_scheme(organization_id, target_id, id),
    FOREIGN KEY (organization_id, eligibility_run_id)
        REFERENCES ai.eligibility_evaluation_run(organization_id, id),
    CONSTRAINT training_intent_status_check
        CHECK (status IN ('PENDING','CONSUMED','BLOCKED')),
    CONSTRAINT training_intent_hash_check
        CHECK (configuration_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT training_intent_detail_object
        CHECK (jsonb_typeof(block_detail_jsonb) = 'object')
);

CREATE INDEX idx_training_intent_pending
    ON ai.training_schedule_intent(organization_id, updated_at, target_id)
    WHERE status = 'PENDING';

ALTER TABLE ai.training_snapshot
    ADD COLUMN target_id uuid,
    ADD COLUMN eligibility_run_id uuid,
    ADD COLUMN source_mapping_versions_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN target_definition_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN input_scheme_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN material_dictionary_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN preprocessing_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN training_policy_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN authorization_scope_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN fact_high_watermark bigint NOT NULL DEFAULT 0,
    ADD COLUMN data_nature varchar(24) NOT NULL DEFAULT 'REAL',
    ADD COLUMN business_fingerprint char(64);

ALTER TABLE ai.training_snapshot
    ADD CONSTRAINT training_snapshot_target_fk
        FOREIGN KEY (organization_id, target_id) REFERENCES ai.prediction_target(organization_id, id),
    ADD CONSTRAINT training_snapshot_eligibility_run_fk
        FOREIGN KEY (organization_id, eligibility_run_id) REFERENCES ai.eligibility_evaluation_run(organization_id, id),
    ADD CONSTRAINT training_snapshot_mapping_array CHECK (jsonb_typeof(source_mapping_versions_jsonb) = 'array'),
    ADD CONSTRAINT training_snapshot_target_object CHECK (jsonb_typeof(target_definition_jsonb) = 'object'),
    ADD CONSTRAINT training_snapshot_scheme_object CHECK (jsonb_typeof(input_scheme_jsonb) = 'object'),
    ADD CONSTRAINT training_snapshot_dictionary_object CHECK (jsonb_typeof(material_dictionary_jsonb) = 'object'),
    ADD CONSTRAINT training_snapshot_preprocessing_object CHECK (jsonb_typeof(preprocessing_jsonb) = 'object'),
    ADD CONSTRAINT training_snapshot_policy_object CHECK (jsonb_typeof(training_policy_jsonb) = 'object'),
    ADD CONSTRAINT training_snapshot_scope_object CHECK (jsonb_typeof(authorization_scope_jsonb) = 'object'),
    ADD CONSTRAINT training_snapshot_data_nature_check CHECK (data_nature IN ('REAL','SYNTHETIC')),
    ADD CONSTRAINT training_snapshot_business_hash_check CHECK (
        business_fingerprint IS NULL OR business_fingerprint ~ '^[0-9a-f]{64}$'
    );

CREATE UNIQUE INDEX uq_training_snapshot_business_fingerprint
    ON ai.training_snapshot(organization_id, business_fingerprint)
    WHERE business_fingerprint IS NOT NULL;

ALTER TABLE ai.training_snapshot_item
    ADD COLUMN training_sample_id uuid,
    ADD COLUMN observation_ids_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN replicate_group_keys_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN source_refs_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN validation_groups_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN value_hash char(64);

ALTER TABLE ai.training_snapshot_item
    ADD CONSTRAINT training_snapshot_item_sample_fk
        FOREIGN KEY (organization_id, training_sample_id) REFERENCES ai.training_sample(organization_id, id),
    ADD CONSTRAINT training_snapshot_item_observations_array CHECK (jsonb_typeof(observation_ids_jsonb) = 'array'),
    ADD CONSTRAINT training_snapshot_item_replicates_array CHECK (jsonb_typeof(replicate_group_keys_jsonb) = 'array'),
    ADD CONSTRAINT training_snapshot_item_source_object CHECK (jsonb_typeof(source_refs_jsonb) = 'object'),
    ADD CONSTRAINT training_snapshot_item_groups_object CHECK (jsonb_typeof(validation_groups_jsonb) = 'object'),
    ADD CONSTRAINT training_snapshot_item_value_hash_check CHECK (
        value_hash IS NULL OR value_hash ~ '^[0-9a-f]{64}$'
    );

ALTER TABLE ai.training_job
    ADD COLUMN target_id uuid,
    ADD COLUMN target_version_id uuid,
    ADD COLUMN modeling_policy_version_id uuid,
    ADD COLUMN seed bigint,
    ADD COLUMN business_key char(64),
    ADD COLUMN ops_job_id uuid REFERENCES ops.async_job(id),
    ADD COLUMN current_stage varchar(40) NOT NULL DEFAULT 'QUEUED',
    ADD COLUMN progress smallint NOT NULL DEFAULT 0,
    ADD COLUMN active_attempt_id uuid,
    ADD COLUMN fencing_generation bigint NOT NULL DEFAULT 0,
    ADD COLUMN cancellation_requested_at timestamptz,
    ADD COLUMN error_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE ai.training_job DROP CONSTRAINT training_job_status_check;
ALTER TABLE ai.training_job
    ADD CONSTRAINT training_job_status_check CHECK (
        status IN ('QUEUED','MATERIALIZING','SNAPSHOT_VALIDATING','FOLDING','TRAINING','VALIDATING','SUCCEEDED','FAILED','CANCELLED')
    ),
    ADD CONSTRAINT training_job_target_fk
        FOREIGN KEY (organization_id, target_id) REFERENCES ai.prediction_target(organization_id, id),
    ADD CONSTRAINT training_job_target_version_fk
        FOREIGN KEY (organization_id, target_id, target_version_id) REFERENCES ai.target_version(organization_id, target_id, id),
    ADD CONSTRAINT training_job_policy_fk
        FOREIGN KEY (organization_id, modeling_policy_version_id) REFERENCES ai.modeling_policy_version(organization_id, id),
    ADD CONSTRAINT training_job_progress_check CHECK (progress BETWEEN 0 AND 100),
    ADD CONSTRAINT training_job_business_hash_check CHECK (business_key IS NULL OR business_key ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT training_job_error_object CHECK (jsonb_typeof(error_jsonb) = 'object');

CREATE UNIQUE INDEX uq_training_job_business_key
    ON ai.training_job(organization_id, business_key)
    WHERE business_key IS NOT NULL;

CREATE UNIQUE INDEX uq_training_job_active_target
    ON ai.training_job(organization_id, target_id)
    WHERE target_id IS NOT NULL
      AND status IN ('QUEUED','MATERIALIZING','SNAPSHOT_VALIDATING','FOLDING','TRAINING','VALIDATING');

CREATE UNIQUE INDEX uq_training_job_running_organization
    ON ai.training_job(organization_id)
    WHERE status IN ('MATERIALIZING','SNAPSHOT_VALIDATING','FOLDING','TRAINING','VALIDATING');

ALTER TABLE ai.training_job_attempt
    ADD COLUMN lease_token uuid,
    ADD COLUMN fencing_generation bigint NOT NULL DEFAULT 0,
    ADD COLUMN heartbeat_at timestamptz,
    ADD COLUMN current_stage varchar(40) NOT NULL DEFAULT 'QUEUED',
    ADD COLUMN progress smallint NOT NULL DEFAULT 0,
    ADD COLUMN log_reference varchar(700),
    ADD COLUMN artifact_refs_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE ai.training_job_attempt DROP CONSTRAINT training_attempt_status_check;
ALTER TABLE ai.training_job_attempt
    ADD CONSTRAINT training_attempt_status_check
        CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','INTERRUPTED','CANCELLED','STALE')),
    ADD CONSTRAINT training_attempt_progress_check CHECK (progress BETWEEN 0 AND 100),
    ADD CONSTRAINT training_attempt_artifacts_object CHECK (jsonb_typeof(artifact_refs_jsonb) = 'object');

ALTER TABLE ai.training_job
    ADD CONSTRAINT training_job_active_attempt_fk
        FOREIGN KEY (organization_id, active_attempt_id)
        REFERENCES ai.training_job_attempt(organization_id, id);

ALTER TABLE ai.artifact
    ADD COLUMN media_type varchar(160),
    ADD COLUMN content_addressed boolean NOT NULL DEFAULT true;

CREATE UNIQUE INDEX uq_artifact_content_kind
    ON ai.artifact(organization_id, sha256, artifact_type);

ALTER TABLE ai.model_version
    ADD COLUMN version_no integer,
    ADD COLUMN data_nature varchar(24) NOT NULL DEFAULT 'REAL',
    ADD COLUMN production_eligible boolean NOT NULL DEFAULT false,
    ADD COLUMN comparison_status varchar(32) NOT NULL DEFAULT 'NOT_COMPARABLE',
    ADD COLUMN rejection_reasons_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE ai.model_version ALTER COLUMN model_artifact_id DROP NOT NULL;

ALTER TABLE ai.model_version DROP CONSTRAINT model_version_status_check;
ALTER TABLE ai.model_version
    ADD CONSTRAINT model_version_status_check
        CHECK (status IN ('CANDIDATE','ACTIVE','PAUSED','RETIRED','REJECTED')),
    ADD CONSTRAINT model_version_data_nature_check CHECK (data_nature IN ('REAL','SYNTHETIC')),
    ADD CONSTRAINT model_version_comparison_check CHECK (comparison_status IN ('BETTER','WORSE','EQUIVALENT','NOT_COMPARABLE')),
    ADD CONSTRAINT model_version_reasons_array CHECK (jsonb_typeof(rejection_reasons_jsonb) = 'array');

ALTER TABLE ai.model_version
    ADD CONSTRAINT model_version_artifact_required CHECK (
        status = 'REJECTED' OR model_artifact_id IS NOT NULL
    );

CREATE UNIQUE INDEX uq_model_version_number
    ON ai.model_version(organization_id, target_id, version_no)
    WHERE version_no IS NOT NULL;

CREATE OR REPLACE FUNCTION ai.protect_terminal_training_attempt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IN ('SUCCEEDED','FAILED','INTERRUPTED','CANCELLED','STALE') THEN
        RAISE EXCEPTION USING ERRCODE = '55000', MESSAGE = 'terminal training attempt is immutable';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER protect_terminal_training_attempt
    BEFORE UPDATE OR DELETE ON ai.training_job_attempt
    FOR EACH ROW EXECUTE FUNCTION ai.protect_terminal_training_attempt();
