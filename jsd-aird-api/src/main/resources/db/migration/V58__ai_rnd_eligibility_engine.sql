-- R04: qualification runs, versioned eligibility results and review decisions.
-- V57 remains immutable; this migration only extends the R04 execution model.

CREATE TABLE ai.eligibility_evaluation_run (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    target_id uuid NOT NULL,
    target_version_id uuid NOT NULL,
    input_scheme_id uuid NOT NULL,
    source_mapping_versions_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    material_dictionary_version_id uuid,
    modeling_policy_version_id uuid NOT NULL,
    rule_fingerprint char(64) NOT NULL,
    async_job_id uuid,
    status varchar(24) NOT NULL DEFAULT 'QUEUED',
    total_samples bigint,
    trainable_count bigint,
    excluded_count bigint,
    review_required_count bigint,
    funnel_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    error_code varchar(100),
    error_message text,
    requested_by uuid REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    finished_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, target_id) REFERENCES ai.prediction_target(organization_id, id),
    FOREIGN KEY (organization_id, target_id, target_version_id)
        REFERENCES ai.target_version(organization_id, target_id, id),
    FOREIGN KEY (organization_id, target_id, input_scheme_id)
        REFERENCES ai.input_scheme(organization_id, target_id, id),
    FOREIGN KEY (organization_id, modeling_policy_version_id)
        REFERENCES ai.modeling_policy_version(organization_id, id),
    FOREIGN KEY (organization_id, material_dictionary_version_id)
        REFERENCES ai.material_dictionary_version(organization_id, id),
    CONSTRAINT eligibility_run_mapping_array CHECK (jsonb_typeof(source_mapping_versions_jsonb) = 'array'),
    CONSTRAINT eligibility_run_funnel_object CHECK (jsonb_typeof(funnel_jsonb) = 'object'),
    CONSTRAINT eligibility_run_hash_check CHECK (rule_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT eligibility_run_status_check CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED'))
);

CREATE INDEX idx_eligibility_run_target
    ON ai.eligibility_evaluation_run(organization_id, target_id, created_at DESC, id);
CREATE INDEX idx_eligibility_run_status
    ON ai.eligibility_evaluation_run(organization_id, status, updated_at DESC, id);

ALTER TABLE ai.training_eligibility
    ADD COLUMN evaluation_run_id uuid,
    ADD COLUMN source_mapping_version_id uuid,
    ADD COLUMN material_dictionary_version_id uuid,
    ADD COLUMN modeling_policy_version_id uuid;

DO $$
DECLARE constraint_name text;
BEGIN
    SELECT conname INTO constraint_name
    FROM pg_constraint
    WHERE conrelid = 'ai.training_eligibility'::regclass
      AND contype = 'u'
      AND pg_get_constraintdef(oid) LIKE 'UNIQUE (organization_id, sample_revision_id, target_version_id, input_scheme_id)%';
    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE ai.training_eligibility DROP CONSTRAINT %I', constraint_name);
    END IF;
END $$;

ALTER TABLE ai.training_eligibility
    ADD CONSTRAINT training_eligibility_run_fk
        FOREIGN KEY (organization_id, evaluation_run_id)
        REFERENCES ai.eligibility_evaluation_run(organization_id, id),
    ADD CONSTRAINT training_eligibility_mapping_fk
        FOREIGN KEY (organization_id, source_mapping_version_id)
        REFERENCES ai.source_mapping_version(organization_id, id),
    ADD CONSTRAINT training_eligibility_dictionary_fk
        FOREIGN KEY (organization_id, material_dictionary_version_id)
        REFERENCES ai.material_dictionary_version(organization_id, id),
    ADD CONSTRAINT training_eligibility_policy_fk
        FOREIGN KEY (organization_id, modeling_policy_version_id)
        REFERENCES ai.modeling_policy_version(organization_id, id);

CREATE UNIQUE INDEX uq_training_eligibility_config_sample
    ON ai.training_eligibility(organization_id, sample_revision_id, target_version_id, input_scheme_id,
        coalesce(source_mapping_version_id, '00000000-0000-0000-0000-000000000000'::uuid),
        coalesce(material_dictionary_version_id, '00000000-0000-0000-0000-000000000000'::uuid),
        coalesce(modeling_policy_version_id, '00000000-0000-0000-0000-000000000000'::uuid));
CREATE INDEX idx_training_eligibility_reason
    ON ai.training_eligibility(organization_id, target_version_id, input_scheme_id, state,
                               evaluated_at DESC, id);

ALTER TABLE ai.data_review
    ADD COLUMN reason_code varchar(80),
    ADD COLUMN evidence_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE ai.data_review
    ADD CONSTRAINT data_review_evidence_object CHECK (jsonb_typeof(evidence_jsonb) = 'object');

CREATE UNIQUE INDEX uq_data_review_open_reason
    ON ai.data_review(
        organization_id,
        coalesce(eligibility_id, identity_issue_id),
        review_type,
        coalesce(reason_code, '')
    ) WHERE status IN ('OPEN','IN_REVIEW');

DO $$
DECLARE constraint_name text;
BEGIN
    SELECT conname INTO constraint_name
    FROM pg_constraint
    WHERE conrelid = 'ai.data_review_decision'::regclass
      AND contype = 'u'
      AND pg_get_constraintdef(oid) LIKE 'UNIQUE (organization_id, data_review_id)%';
    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE ai.data_review_decision DROP CONSTRAINT %I', constraint_name);
    END IF;
END $$;

ALTER TABLE ai.data_review_decision
    ADD COLUMN decision_no integer,
    ADD COLUMN request_id varchar(160),
    ADD COLUMN idempotency_key varchar(200),
    ADD COLUMN before_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN after_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb;

CREATE UNIQUE INDEX uq_data_review_decision_no
    ON ai.data_review_decision(organization_id, data_review_id, decision_no)
    WHERE decision_no IS NOT NULL;
CREATE UNIQUE INDEX uq_data_review_decision_idempotency
    ON ai.data_review_decision(organization_id, data_review_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
ALTER TABLE ai.data_review_decision
    ADD CONSTRAINT data_review_decision_before_object CHECK (jsonb_typeof(before_jsonb) = 'object'),
    ADD CONSTRAINT data_review_decision_after_object CHECK (jsonb_typeof(after_jsonb) = 'object');

ALTER TABLE ai.data_review_decision DROP CONSTRAINT IF EXISTS data_review_decision_check;
ALTER TABLE ai.data_review_decision
    ADD CONSTRAINT data_review_decision_check
    CHECK (decision IN ('KEEP','EXCLUDE','INCLUDE','MERGE','KEEP_SEPARATE','REMAP','DISMISS'));
