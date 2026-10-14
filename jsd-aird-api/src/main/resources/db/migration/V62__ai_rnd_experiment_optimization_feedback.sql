-- R09: experiment optimization, repeatable draft creation and immutable feedback.
-- Existing R08 runs/candidates remain the single research aggregate.

ALTER TABLE ai.research_run_v2
    ADD COLUMN baseline_type varchar(40),
    ADD COLUMN baseline_entity_id uuid,
    ADD COLUMN baseline_version_id uuid,
    ADD COLUMN baseline_content_hash char(64),
    ADD COLUMN baseline_snapshot_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN optimization_config_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE ai.research_run_v2
    ADD CONSTRAINT research_run_v2_baseline_type_check CHECK (
        baseline_type IS NULL OR baseline_type IN ('EXPERIMENT_VERSION','DATA_SAMPLE_REVISION','RESEARCH_CANDIDATE')
    ),
    ADD CONSTRAINT research_run_v2_baseline_hash_check CHECK (
        baseline_content_hash IS NULL OR baseline_content_hash ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT research_run_v2_baseline_object CHECK (jsonb_typeof(baseline_snapshot_jsonb)='object'),
    ADD CONSTRAINT research_run_v2_optimization_object CHECK (jsonb_typeof(optimization_config_jsonb)='object'),
    ADD CONSTRAINT research_run_v2_optimization_baseline_check CHECK (
        run_type <> 'EXPERIMENT_OPTIMIZATION' OR (
            baseline_type IS NOT NULL AND baseline_entity_id IS NOT NULL AND baseline_version_id IS NOT NULL
            AND baseline_content_hash IS NOT NULL AND baseline_snapshot_jsonb <> '{}'::jsonb
        )
    );

CREATE INDEX idx_research_run_v2_baseline_history
    ON ai.research_run_v2(organization_id, baseline_type, baseline_entity_id, created_at DESC, id)
    WHERE baseline_type IS NOT NULL;
CREATE UNIQUE INDEX uq_research_run_v2_active_optimization_org
    ON ai.research_run_v2(organization_id)
    WHERE run_type='EXPERIMENT_OPTIMIZATION' AND execution_status IN ('QUEUED','RUNNING');

ALTER TABLE ai.research_candidate_v2
    ADD COLUMN baseline_distance numeric(24,12),
    ADD COLUMN change_summary_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN strategy_evidence_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN risk_flags_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE ai.research_candidate_v2
    ADD CONSTRAINT research_candidate_v2_strategy_check CHECK (
        search_strategy IN ('CONTROLLED_POOL','CONTROL','CONSERVATIVE','BALANCED','EXPLORATORY')
    ),
    ADD CONSTRAINT research_candidate_v2_change_object CHECK (jsonb_typeof(change_summary_jsonb)='object'),
    ADD CONSTRAINT research_candidate_v2_strategy_evidence_object CHECK (jsonb_typeof(strategy_evidence_jsonb)='object'),
    ADD CONSTRAINT research_candidate_v2_risk_flags_array CHECK (jsonb_typeof(risk_flags_jsonb)='array'),
    ADD CONSTRAINT research_candidate_v2_distance_check CHECK (baseline_distance IS NULL OR baseline_distance >= 0);

ALTER TABLE ai.research_experiment_link_v2
    DROP CONSTRAINT IF EXISTS research_experiment_link_v2_organization_id_research_candid_key,
    ADD COLUMN creation_intent_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN creation_intent_hash char(64),
    ADD COLUMN source_evidence_id uuid;

ALTER TABLE ai.research_experiment_link_v2
    ADD CONSTRAINT research_experiment_link_v2_intent_object CHECK (jsonb_typeof(creation_intent_jsonb)='object'),
    ADD CONSTRAINT research_experiment_link_v2_intent_hash_check CHECK (
        creation_intent_hash IS NULL OR creation_intent_hash ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT research_experiment_link_v2_evidence_fk
        FOREIGN KEY (organization_id, source_evidence_id)
        REFERENCES rnd.experiment_ai_source_evidence(organization_id, id);

CREATE INDEX idx_research_experiment_link_v2_candidate
    ON ai.research_experiment_link_v2(organization_id, research_candidate_id, created_at DESC, id);

ALTER TABLE rnd.experiment_ai_source_evidence
    ADD COLUMN research_run_id uuid,
    ADD COLUMN research_candidate_id uuid,
    ADD COLUMN baseline_snapshot_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN target_evidence_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN request_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE rnd.experiment_ai_source_evidence
    ADD CONSTRAINT experiment_ai_baseline_object CHECK (jsonb_typeof(baseline_snapshot_jsonb)='object'),
    ADD CONSTRAINT experiment_ai_target_evidence_object CHECK (jsonb_typeof(target_evidence_jsonb)='object'),
    ADD CONSTRAINT experiment_ai_request_object CHECK (jsonb_typeof(request_jsonb)='object');

CREATE INDEX idx_experiment_ai_evidence_candidate
    ON rnd.experiment_ai_source_evidence(organization_id, research_candidate_id, captured_at DESC, id)
    WHERE research_candidate_id IS NOT NULL;

ALTER TABLE ai.model_feedback
    ADD COLUMN target_version_id uuid,
    ADD COLUMN source_mapping_version_id uuid,
    ADD COLUMN sample_revision_id uuid,
    ADD COLUMN comparison_status varchar(24),
    ADD COLUMN reason_code varchar(100),
    ADD COLUMN observation_refs_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN metrics_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN prediction_evidence_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN projection_event_id uuid;

UPDATE ai.model_feedback f
SET target_version_id=m.target_version_id,
    source_mapping_version_id=CASE
        WHEN jsonb_array_length(s.source_mapping_versions_jsonb)>0
             AND (s.source_mapping_versions_jsonb->0->>'id') ~* '^[0-9a-f-]{36}$'
        THEN (s.source_mapping_versions_jsonb->0->>'id')::uuid ELSE NULL END,
    comparison_status=CASE WHEN f.status='EXCLUDED' THEN 'EXCLUDED' ELSE 'COMPARABLE' END
FROM ai.model_version m
JOIN ai.training_snapshot s ON s.organization_id=m.organization_id AND s.id=m.training_snapshot_id
WHERE f.organization_id=m.organization_id AND f.model_version_id=m.id;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM ai.model_feedback WHERE target_version_id IS NULL) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V62 blocked: legacy model feedback cannot resolve target version';
    END IF;
    IF EXISTS (SELECT 1 FROM ai.model_feedback WHERE source_mapping_version_id IS NULL) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V62 blocked: legacy model feedback cannot resolve source mapping version';
    END IF;
END $$;

ALTER TABLE ai.model_feedback
    ADD CONSTRAINT model_feedback_target_version_fk
        FOREIGN KEY (organization_id, target_id, target_version_id)
        REFERENCES ai.target_version(organization_id, target_id, id),
    ADD CONSTRAINT model_feedback_source_mapping_fk
        FOREIGN KEY (organization_id, source_mapping_version_id)
        REFERENCES ai.source_mapping_version(organization_id, id),
    ADD CONSTRAINT model_feedback_sample_revision_fk
        FOREIGN KEY (organization_id, sample_revision_id)
        REFERENCES ai.sample_revision(organization_id, id),
    ADD CONSTRAINT model_feedback_comparison_check CHECK (
        comparison_status IS NULL OR comparison_status IN ('COMPARABLE','EXCLUDED','NOT_CURRENT')
    ),
    ADD CONSTRAINT model_feedback_observation_refs_array CHECK (jsonb_typeof(observation_refs_jsonb)='array'),
    ADD CONSTRAINT model_feedback_metrics_object CHECK (jsonb_typeof(metrics_jsonb)='object'),
    ADD CONSTRAINT model_feedback_prediction_evidence_object CHECK (jsonb_typeof(prediction_evidence_jsonb)='object');

CREATE TABLE ai.model_feedback_projection_receipt (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    experiment_event_id uuid NOT NULL,
    experiment_id uuid NOT NULL,
    experiment_version_id uuid NOT NULL,
    event_type varchar(100) NOT NULL,
    payload_hash char(64) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PROCESSING',
    attempt_count integer NOT NULL DEFAULT 1,
    feedback_count integer NOT NULL DEFAULT 0,
    error_message text,
    processed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, experiment_event_id),
    FOREIGN KEY (organization_id, experiment_id) REFERENCES rnd.experiment(organization_id, id),
    FOREIGN KEY (organization_id, experiment_version_id) REFERENCES rnd.experiment_version(organization_id, id),
    CONSTRAINT model_feedback_receipt_hash_check CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT model_feedback_receipt_status_check CHECK (status IN ('PROCESSING','COMPLETED','FAILED','STALE'))
);

CREATE INDEX idx_model_feedback_projection_recovery
    ON ai.model_feedback_projection_receipt(organization_id, status, updated_at, id)
    WHERE status IN ('PROCESSING','FAILED');
CREATE INDEX idx_model_feedback_experiment_history
    ON ai.model_feedback(organization_id, experiment_id, experiment_version_id, created_at, id);

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
       OR NEW.baseline_type IS DISTINCT FROM OLD.baseline_type
       OR NEW.baseline_entity_id IS DISTINCT FROM OLD.baseline_entity_id
       OR NEW.baseline_version_id IS DISTINCT FROM OLD.baseline_version_id
       OR NEW.baseline_content_hash IS DISTINCT FROM OLD.baseline_content_hash
       OR NEW.baseline_snapshot_jsonb IS DISTINCT FROM OLD.baseline_snapshot_jsonb
       OR NEW.optimization_config_jsonb IS DISTINCT FROM OLD.optimization_config_jsonb
    THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='ai.research_run_v2 frozen request cannot change';
    END IF;
    RETURN NEW;
END $$;

COMMENT ON COLUMN ai.research_run_v2.baseline_snapshot_jsonb IS 'Immutable baseline facts used by an R09 optimization run';
COMMENT ON COLUMN ai.model_feedback.prediction_evidence_jsonb IS 'Immutable prediction and model evidence compared with completed experiment facts';
