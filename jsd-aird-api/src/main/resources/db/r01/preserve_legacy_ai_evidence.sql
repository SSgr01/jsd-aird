-- Idempotent pre-R10 evidence preservation. It intentionally retains no FK to legacy AI tables.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM ai.research_experiment_link l
    LEFT JOIN ai.research_run r ON r.id=l.research_run_id AND r.organization_id=l.organization_id
    LEFT JOIN ai.research_candidate c ON c.id=l.research_candidate_id AND c.organization_id=l.organization_id
    WHERE r.id IS NULL OR c.id IS NULL
  ) THEN
    RAISE EXCEPTION USING ERRCODE='23503', MESSAGE='legacy AI evidence contains missing run or candidate references';
  END IF;
END $$;

INSERT INTO rnd.experiment_ai_source_evidence (
  id, organization_id, experiment_id, experiment_version_id, legacy_research_run_id, legacy_candidate_id,
  request_hash, formula_jsonb, predictions_jsonb, model_bindings_jsonb, source_summary_jsonb,
  evidence_hash, captured_at, captured_by
)
SELECT
  gen_random_uuid(), l.organization_id, l.experiment_id, l.experiment_version_id, l.research_run_id, l.research_candidate_id,
  r.request_hash, c.formula_jsonb, c.estimates_jsonb, COALESCE(c.model_context_jsonb,'{}'::jsonb),
  jsonb_build_object('runType',r.run_type,'taskProfileCode',r.task_profile_code,'candidateNo',c.candidate_no,
                     'evidence',c.evidence_jsonb,'ruleCheck',c.rule_check_jsonb),
  encode(digest(r.request_hash || c.content_hash || l.experiment_version_id::text,'sha256'),'hex'),
  l.created_at, l.created_by
FROM ai.research_experiment_link l
JOIN ai.research_run r ON r.id=l.research_run_id AND r.organization_id=l.organization_id
JOIN ai.research_candidate c ON c.id=l.research_candidate_id AND c.organization_id=l.organization_id
ON CONFLICT (organization_id, experiment_version_id, evidence_hash) DO NOTHING;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM ai.research_experiment_link l
    WHERE NOT EXISTS (
      SELECT 1 FROM rnd.experiment_ai_source_evidence e
      WHERE e.organization_id=l.organization_id AND e.experiment_version_id=l.experiment_version_id
        AND e.legacy_research_run_id=l.research_run_id AND e.legacy_candidate_id=l.research_candidate_id
    )
  ) THEN
    RAISE EXCEPTION USING ERRCODE='23503', MESSAGE='legacy AI evidence preservation is incomplete';
  END IF;
END $$;
