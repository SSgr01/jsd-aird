-- R07: versioned inference policies and concurrency-safe multi-target prediction records.
-- Existing modeling policies predate the explicit kind column; they are training policies.

ALTER TABLE ai.modeling_policy_version
    ADD COLUMN kind varchar(24) NOT NULL DEFAULT 'TRAINING',
    ADD COLUMN configuration_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN revision bigint NOT NULL DEFAULT 0,
    ADD COLUMN published_by uuid REFERENCES iam.app_user(id);

DROP TRIGGER immutable_modeling_policy ON ai.modeling_policy_version;

UPDATE ai.modeling_policy_version
SET configuration_jsonb = jsonb_build_object(
        'qualification', qualification_jsonb,
        'validation', validation_jsonb,
        'training', training_jsonb
    )
WHERE kind = 'TRAINING' AND configuration_jsonb = '{}'::jsonb;

ALTER TABLE ai.modeling_policy_version
    DROP CONSTRAINT modeling_policy_version_organization_id_target_id_version_n_key,
    ADD CONSTRAINT modeling_policy_kind_check
        CHECK (kind IN ('ELIGIBILITY','PREPROCESSING','TRAINING','DOMAIN','QUALITY')),
    ADD CONSTRAINT modeling_policy_configuration_object
        CHECK (jsonb_typeof(configuration_jsonb) = 'object'),
    ADD CONSTRAINT uq_modeling_policy_kind_version
        UNIQUE (organization_id, target_id, kind, version_no),
    ADD CONSTRAINT uq_modeling_policy_target_identity
        UNIQUE (organization_id, target_id, id);

CREATE INDEX idx_modeling_policy_kind_history
    ON ai.modeling_policy_version(organization_id, target_id, kind, version_no DESC, id);

CREATE TRIGGER immutable_modeling_policy BEFORE UPDATE OR DELETE ON ai.modeling_policy_version
    FOR EACH ROW WHEN (OLD.status = 'PUBLISHED') EXECUTE FUNCTION ai.reject_immutable_change();

ALTER TABLE ai.prediction_target
    ADD COLUMN current_quality_policy_version_id uuid,
    ADD COLUMN current_domain_policy_version_id uuid,
    ADD CONSTRAINT prediction_target_quality_policy_fk
        FOREIGN KEY (organization_id, id, current_quality_policy_version_id)
        REFERENCES ai.modeling_policy_version(organization_id, target_id, id),
    ADD CONSTRAINT prediction_target_domain_policy_fk
        FOREIGN KEY (organization_id, id, current_domain_policy_version_id)
        REFERENCES ai.modeling_policy_version(organization_id, target_id, id);

CREATE OR REPLACE FUNCTION ai.validate_prediction_target_policy_pointer() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.current_quality_policy_version_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM ai.modeling_policy_version p
        WHERE p.organization_id=NEW.organization_id AND p.target_id=NEW.id
          AND p.id=NEW.current_quality_policy_version_id AND p.kind='QUALITY' AND p.status='PUBLISHED'
    ) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='current quality policy must be a published QUALITY policy for the target';
    END IF;
    IF NEW.current_domain_policy_version_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM ai.modeling_policy_version p
        WHERE p.organization_id=NEW.organization_id AND p.target_id=NEW.id
          AND p.id=NEW.current_domain_policy_version_id AND p.kind='DOMAIN' AND p.status='PUBLISHED'
    ) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='current domain policy must be a published DOMAIN policy for the target';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER validate_prediction_target_policy_pointer
    BEFORE INSERT OR UPDATE OF current_quality_policy_version_id, current_domain_policy_version_id
    ON ai.prediction_target FOR EACH ROW
    EXECUTE FUNCTION ai.validate_prediction_target_policy_pointer();

ALTER TABLE ai.model_version
    ADD COLUMN domain_policy_version_id uuid,
    ADD CONSTRAINT model_version_domain_policy_fk
        FOREIGN KEY (organization_id, target_id, domain_policy_version_id)
        REFERENCES ai.modeling_policy_version(organization_id, target_id, id);

CREATE OR REPLACE FUNCTION ai.validate_model_domain_policy() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.domain_policy_version_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM ai.modeling_policy_version p
        WHERE p.organization_id=NEW.organization_id AND p.target_id=NEW.target_id
          AND p.id=NEW.domain_policy_version_id AND p.kind='DOMAIN' AND p.status='PUBLISHED'
    ) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='model domain policy must be a published DOMAIN policy for the target';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER validate_model_domain_policy
    BEFORE INSERT OR UPDATE OF domain_policy_version_id ON ai.model_version
    FOR EACH ROW EXECUTE FUNCTION ai.validate_model_domain_policy();

DROP TRIGGER protect_prediction_record ON ai.prediction_record;

ALTER TABLE ai.prediction_record
    DROP CONSTRAINT prediction_record_status_check,
    DROP CONSTRAINT prediction_record_organization_id_idempotency_key_key,
    ADD COLUMN request_id varchar(160),
    ADD COLUMN contract_version varchar(40) NOT NULL DEFAULT 'ai-rnd.v1',
    ADD COLUMN execution_status varchar(24),
    ADD COLUMN outcome_status varchar(24),
    ADD COLUMN target_results_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN terminal_http_status integer,
    ADD COLUMN terminal_response_jsonb jsonb,
    ADD COLUMN duration_ms bigint,
    ADD COLUMN heartbeat_at timestamptz,
    ADD COLUMN revision bigint NOT NULL DEFAULT 0,
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

UPDATE ai.prediction_record
SET request_id = id::text,
    execution_status = CASE WHEN status='RUNNING' THEN 'RUNNING' WHEN status='FAILED' THEN 'FAILED' ELSE 'SUCCEEDED' END,
    outcome_status = CASE WHEN status IN ('SUCCEEDED','PARTIAL','BLOCKED') THEN status ELSE NULL END,
    target_results_jsonb = CASE
        WHEN jsonb_typeof(result_jsonb->'results')='array' THEN result_jsonb->'results'
        WHEN jsonb_typeof(result_jsonb)='array' THEN result_jsonb
        ELSE '[]'::jsonb END,
    terminal_http_status = CASE
        WHEN status='FAILED' THEN 503
        WHEN status='BLOCKED' AND jsonb_array_length(CASE WHEN jsonb_typeof(result_jsonb->'results')='array' THEN result_jsonb->'results' ELSE '[]'::jsonb END)=1 THEN 422
        WHEN status='RUNNING' THEN NULL ELSE 200 END,
    terminal_response_jsonb = CASE WHEN status='RUNNING' THEN NULL ELSE coalesce(result_jsonb,error_jsonb,'{}'::jsonb) END,
    heartbeat_at = created_at;

ALTER TABLE ai.prediction_record
    ALTER COLUMN request_id SET NOT NULL,
    ALTER COLUMN execution_status SET NOT NULL,
    DROP COLUMN status,
    ADD CONSTRAINT uq_prediction_record_idempotency_scope
        UNIQUE (organization_id, created_by, prediction_type, idempotency_key),
    ADD CONSTRAINT prediction_record_execution_status_check
        CHECK (execution_status IN ('RUNNING','SUCCEEDED','FAILED')),
    ADD CONSTRAINT prediction_record_outcome_status_check
        CHECK (outcome_status IS NULL OR outcome_status IN ('SUCCEEDED','PARTIAL','BLOCKED')),
    ADD CONSTRAINT prediction_record_status_consistency_check CHECK (
        (execution_status='RUNNING' AND outcome_status IS NULL AND terminal_http_status IS NULL AND terminal_response_jsonb IS NULL AND completed_at IS NULL)
        OR
        (execution_status='SUCCEEDED' AND outcome_status IS NOT NULL AND terminal_http_status IN (200,422) AND terminal_response_jsonb IS NOT NULL AND completed_at IS NOT NULL)
        OR
        (execution_status='FAILED' AND outcome_status IS NULL AND terminal_http_status=503 AND terminal_response_jsonb IS NOT NULL AND completed_at IS NOT NULL)
    ),
    ADD CONSTRAINT prediction_record_target_results_array
        CHECK (jsonb_typeof(target_results_jsonb)='array'),
    ADD CONSTRAINT prediction_record_terminal_response_object
        CHECK (terminal_response_jsonb IS NULL OR jsonb_typeof(terminal_response_jsonb)='object'),
    ADD CONSTRAINT prediction_record_duration_check
        CHECK (duration_ms IS NULL OR duration_ms >= 0);

CREATE INDEX idx_prediction_record_running_reconcile
    ON ai.prediction_record(heartbeat_at, id) WHERE execution_status='RUNNING';
CREATE INDEX idx_prediction_record_owner_history
    ON ai.prediction_record(organization_id, created_by, created_at DESC, id);

CREATE OR REPLACE FUNCTION ai.protect_prediction_record_v2() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='ai.prediction_record is immutable';
    END IF;
    IF OLD.execution_status IN ('SUCCEEDED','FAILED') THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='ai.prediction_record completed record is immutable';
    END IF;
    IF NEW.organization_id IS DISTINCT FROM OLD.organization_id
       OR NEW.prediction_type IS DISTINCT FROM OLD.prediction_type
       OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
       OR NEW.request_hash IS DISTINCT FROM OLD.request_hash
       OR NEW.request_jsonb IS DISTINCT FROM OLD.request_jsonb
       OR NEW.target_bindings_jsonb IS DISTINCT FROM OLD.target_bindings_jsonb
       OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.request_id IS DISTINCT FROM OLD.request_id
       OR NEW.contract_version IS DISTINCT FROM OLD.contract_version THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='ai.prediction_record request payload is immutable';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER protect_prediction_record
    BEFORE UPDATE OR DELETE ON ai.prediction_record
    FOR EACH ROW EXECUTE FUNCTION ai.protect_prediction_record_v2();
