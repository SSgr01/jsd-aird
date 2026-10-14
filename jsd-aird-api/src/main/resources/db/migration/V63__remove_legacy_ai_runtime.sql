-- R10: one-time removal of the legacy AI runtime.
-- This migration is intentionally destructive. It is only applied after the
-- R10 preservation/preflight tools have been run on an isolated database.

DO $$
DECLARE
    missing text[] := ARRAY[]::text[];
    unexpected_fk text;
    orphan_count bigint;
BEGIN
    -- V11/V54/V55 are part of every supported V62 history.  Failing here is
    -- safer than interpreting a partially migrated database as empty.
    FOREACH unexpected_fk IN ARRAY ARRAY[
        'ai.training_dataset_record', 'ai.training_dataset',
        'ai.research_experiment_link', 'ai.research_candidate', 'ai.research_run',
        'ai.formula_model_activation', 'ai.formula_model_target',
        'ai.formula_model_version', 'ai.formula_model_snapshot',
        'ai.formulation_task_profile'
    ] LOOP
        IF to_regclass(unexpected_fk) IS NULL THEN
            missing := array_append(missing, unexpected_fk);
        END IF;
    END LOOP;
    IF cardinality(missing) > 0 THEN
        RAISE EXCEPTION USING ERRCODE='55000',
            MESSAGE='V63 blocked: legacy foundation is incomplete',
            DETAIL=array_to_string(missing, ', ');
    END IF;

    IF to_regclass('rnd.experiment_ai_source_evidence') IS NULL THEN
        RAISE EXCEPTION USING ERRCODE='55000',
            MESSAGE='V63 blocked: experiment AI evidence table is missing';
    END IF;

    -- Every old link must have an immutable experiment-side copy before the
    -- source rows disappear.  No FK to an old AI table is retained.
    IF EXISTS (
        SELECT 1
        FROM ai.research_experiment_link l
        LEFT JOIN rnd.experiment_ai_source_evidence e
          ON e.organization_id=l.organization_id
         AND e.experiment_version_id=l.experiment_version_id
         AND e.legacy_research_run_id=l.research_run_id
         AND e.legacy_candidate_id=l.research_candidate_id
        WHERE e.id IS NULL
    ) THEN
        RAISE EXCEPTION USING ERRCODE='23503',
            MESSAGE='V63 blocked: legacy experiment evidence has not been preserved';
    END IF;

    -- A dataset row with no source record would be an untraceable fact.  It
    -- must be exported/reconciled explicitly instead of being silently lost.
    SELECT count(*) INTO orphan_count
    FROM ai.training_dataset_record
    WHERE source_record_id IS NULL;
    IF orphan_count > 0 THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='V63 blocked: legacy training records have no source coordinate',
            DETAIL=orphan_count::text;
    END IF;

    -- Do not let an unknown application table be removed by an implicit
    -- CASCADE.  Known legacy children are removed below in dependency order.
    SELECT format('%I.%I -> %I.%I (%I)', n.nspname, c.relname,
                  tn.nspname, tc.relname, con.conname)
      INTO unexpected_fk
    FROM pg_constraint con
    JOIN pg_class c ON c.oid=con.conrelid
    JOIN pg_namespace n ON n.oid=c.relnamespace
    JOIN pg_class tc ON tc.oid=con.confrelid
    JOIN pg_namespace tn ON tn.oid=tc.relnamespace
    WHERE con.contype='f'
      AND (tn.nspname || '.' || tc.relname) IN (
          'ai.training_dataset_record','ai.training_dataset',
          'ai.research_experiment_link','ai.research_candidate','ai.research_run',
          'ai.formula_model_activation','ai.formula_model_target',
          'ai.formula_model_version','ai.formula_model_snapshot',
          'ai.formulation_task_profile'
      )
      AND NOT (
          (n.nspname || '.' || c.relname) IN (
              'ai.training_dataset_record','ai.training_dataset',
              'ai.research_experiment_link','ai.research_candidate','ai.research_run',
              'ai.formula_model_activation','ai.formula_model_target',
              'ai.formula_model_version','ai.formula_model_snapshot',
              'ai.formulation_task_profile','data.import_job'
          )
      )
    LIMIT 1;
    IF unexpected_fk IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE='55000',
            MESSAGE='V63 blocked: unexpected foreign key references a legacy AI table',
            DETAIL=unexpected_fk;
    END IF;
END $$;

-- The import job remains a first-class source job; only its obsolete derived
-- training-dataset pointer is removed.
ALTER TABLE data.import_job
    DROP CONSTRAINT IF EXISTS import_job_latest_training_dataset_id_fkey;
ALTER TABLE data.import_job
    DROP COLUMN IF EXISTS latest_training_dataset_id;

-- Remove old runtime tables without CASCADE.  The preflight above makes any
-- unexpected dependency an explicit migration failure.
DROP TABLE ai.training_dataset_record;
DROP TABLE ai.training_dataset;
DROP TABLE ai.research_experiment_link;
DROP TABLE ai.research_candidate;
DROP TABLE ai.research_run;
DROP TABLE ai.formula_model_activation;
DROP TABLE ai.formula_model_target;
DROP TABLE ai.formula_model_version;
DROP TABLE ai.formula_model_snapshot;
DROP TABLE ai.formulation_task_profile;

COMMENT ON TABLE rnd.experiment_ai_source_evidence IS
    'Immutable experiment-side evidence retained across R10 legacy AI runtime removal';

-- Retire the pre-R01 catch-all model permission after the old routes and
-- registry are removed.  It is deliberately disabled rather than deleted so
-- historical role bindings remain auditable and cannot be re-used by a new
-- route by accident.
UPDATE iam.permission_definition
SET enabled = FALSE,
    updated_at = now(),
    definition_version = definition_version + 1
WHERE code = 'ai.model.manage';

UPDATE iam.role
SET policy_version = policy_version + 1;

UPDATE iam.app_user
SET auth_version = auth_version + 1;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM ops.async_job
        WHERE job_type IN ('RESEARCH_RUN', 'FORMULA_MODEL_BUILD', 'AI_FORMULA_MODEL_BUILD')
    ) THEN
        RAISE EXCEPTION USING ERRCODE='55000',
            MESSAGE='V63 blocked: legacy AI jobs are still queued or running';
    END IF;
END $$;

ALTER TABLE ops.async_job
    ADD CONSTRAINT async_job_no_legacy_ai_types
    CHECK (job_type NOT IN ('RESEARCH_RUN', 'FORMULA_MODEL_BUILD', 'AI_FORMULA_MODEL_BUILD'));
