-- R08: model driven formula design.  The legacy research_run tables remain
-- available for R09 migration; this migration adds the frozen V2 boundary.
ALTER TABLE ai.research_run_v2
    ADD COLUMN IF NOT EXISTS request_id varchar(160),
    ADD COLUMN IF NOT EXISTS contract_version varchar(40) NOT NULL DEFAULT 'ai-rnd.v1',
    ADD COLUMN IF NOT EXISTS ops_job_id uuid,
    ADD COLUMN IF NOT EXISTS seed bigint,
    ADD COLUMN IF NOT EXISTS search_engine_version varchar(80) NOT NULL DEFAULT 'formula-search.v2',
    ADD COLUMN IF NOT EXISTS search_config_jsonb jsonb NOT NULL DEFAULT '{"maxEvaluations":512,"initialDesign":64,"batchSize":64,"diversityWeight":0.15}'::jsonb,
    ADD COLUMN IF NOT EXISTS progress smallint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS current_stage varchar(80),
    ADD COLUMN IF NOT EXISTS heartbeat_at timestamptz,
    ADD COLUMN IF NOT EXISTS fencing_generation bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS execution_status varchar(24),
    ADD COLUMN IF NOT EXISTS outcome_status varchar(24);

UPDATE ai.research_run_v2
SET request_id=coalesce(request_id,id::text),
    execution_status=coalesce(execution_status, CASE
        WHEN status='QUEUED' THEN 'QUEUED' WHEN status='RUNNING' THEN 'RUNNING'
        WHEN status='CANCELLED' THEN 'CANCELLED' WHEN status='FAILED' THEN 'FAILED'
        ELSE 'SUCCEEDED' END),
    outcome_status=coalesce(outcome_status, CASE
        WHEN status IN ('SUCCEEDED','PARTIAL','BLOCKED') THEN status ELSE NULL END),
    heartbeat_at=coalesce(heartbeat_at,updated_at);

ALTER TABLE ai.research_run_v2
    ALTER COLUMN request_id SET NOT NULL,
    ADD CONSTRAINT research_run_v2_execution_status_check
        CHECK (execution_status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    ADD CONSTRAINT research_run_v2_outcome_status_check
        CHECK (outcome_status IS NULL OR outcome_status IN ('SUCCEEDED','PARTIAL','BLOCKED')),
    ADD CONSTRAINT research_run_v2_status_consistency_check CHECK (
        (execution_status IN ('QUEUED','RUNNING','FAILED','CANCELLED') AND outcome_status IS NULL)
        OR (execution_status='SUCCEEDED' AND outcome_status IS NOT NULL)
    ),
    ADD CONSTRAINT research_run_v2_search_config_object CHECK (jsonb_typeof(search_config_jsonb)='object'),
    ADD CONSTRAINT research_run_v2_progress_check CHECK (progress BETWEEN 0 AND 100);

CREATE INDEX IF NOT EXISTS idx_research_run_v2_owner_history
    ON ai.research_run_v2(organization_id, created_by, created_at DESC, id);
CREATE INDEX IF NOT EXISTS idx_research_run_v2_active
    ON ai.research_run_v2(organization_id, run_type, execution_status, created_at DESC)
    WHERE execution_status IN ('QUEUED','RUNNING');
CREATE UNIQUE INDEX IF NOT EXISTS uq_research_run_v2_ops_job
    ON ai.research_run_v2(organization_id, ops_job_id) WHERE ops_job_id IS NOT NULL;

ALTER TABLE ai.research_candidate_v2
    ADD COLUMN IF NOT EXISTS target_total numeric(18,8),
    ADD COLUMN IF NOT EXISTS fixed_inputs_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS search_space_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS target_gate_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS quality_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS preference_score numeric(24,12),
    ADD COLUMN IF NOT EXISTS diversity_score numeric(24,12),
    ADD COLUMN IF NOT EXISTS search_strategy varchar(80) NOT NULL DEFAULT 'CONTROLLED_POOL';

ALTER TABLE ai.research_candidate_v2
    ADD CONSTRAINT research_candidate_v2_fixed_inputs_object CHECK (jsonb_typeof(fixed_inputs_jsonb)='object'),
    ADD CONSTRAINT research_candidate_v2_search_space_object CHECK (jsonb_typeof(search_space_jsonb)='object'),
    ADD CONSTRAINT research_candidate_v2_gate_object CHECK (jsonb_typeof(target_gate_jsonb)='object'),
    ADD CONSTRAINT research_candidate_v2_quality_object CHECK (jsonb_typeof(quality_jsonb)='object');

CREATE INDEX IF NOT EXISTS idx_research_candidate_v2_score
    ON ai.research_candidate_v2(organization_id, research_run_id, score DESC NULLS LAST, candidate_no);

CREATE OR REPLACE FUNCTION ai.protect_research_run_v2_r08() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='ai.research_run_v2 is immutable';
    END IF;
    IF OLD.execution_status IN ('SUCCEEDED','FAILED','CANCELLED') OR OLD.status IN ('SUCCEEDED','PARTIAL','BLOCKED','FAILED','CANCELLED') THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='ai.research_run_v2 terminal record is immutable';
    END IF;
    IF NEW.organization_id IS DISTINCT FROM OLD.organization_id
       OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
       OR NEW.request_hash IS DISTINCT FROM OLD.request_hash
       OR NEW.request_jsonb IS DISTINCT FROM OLD.request_jsonb
       OR NEW.model_bindings_jsonb IS DISTINCT FROM OLD.model_bindings_jsonb
       OR NEW.request_id IS DISTINCT FROM OLD.request_id
       OR NEW.contract_version IS DISTINCT FROM OLD.contract_version
       OR NEW.seed IS DISTINCT FROM OLD.seed
       OR NEW.search_config_jsonb IS DISTINCT FROM OLD.search_config_jsonb
    THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='ai.research_run_v2 frozen request cannot change';
    END IF;
    RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS protect_research_run_v2_r08 ON ai.research_run_v2;
CREATE TRIGGER protect_research_run_v2_r08 BEFORE UPDATE OR DELETE ON ai.research_run_v2
    FOR EACH ROW EXECUTE FUNCTION ai.protect_research_run_v2_r08();

COMMENT ON COLUMN ai.research_run_v2.execution_status IS 'Request execution lifecycle; independent from business outcome';
COMMENT ON COLUMN ai.research_run_v2.outcome_status IS 'Candidate outcome, populated only after successful execution';
