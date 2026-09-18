-- Read-only R10 cutover preflight. It is safe against a partial database:
-- optional and legacy relations are inspected through regclass and dynamic
-- SQL so the check reports a classification before V63 writes anything.
WITH legacy AS (
  SELECT * FROM (VALUES
    ('ai','training_dataset_record'),('ai','training_dataset'),
    ('ai','research_experiment_link'),('ai','research_candidate'),('ai','research_run'),
    ('ai','formula_model_activation'),('ai','formula_model_target'),
    ('ai','formula_model_version'),('ai','formula_model_snapshot'),
    ('ai','formulation_task_profile')
  ) AS t(schema_name, table_name)
), presence AS (
  SELECT l.schema_name,l.table_name,
         to_regclass(format('%I.%I',l.schema_name,l.table_name)) IS NOT NULL AS present
  FROM legacy l
), foundation AS (
  SELECT * FROM (VALUES
    ('ops','file_object'),('ops','audit_log'),('ops','async_job'),
    ('data','import_job'),('data','data_record'),('data','data_value'),
    ('mdm','material'),('mdm','project'),
    ('rnd','experiment'),('rnd','experiment_version'),
    ('rnd','experiment_ai_source_evidence'),
    ('ai','training_sample'),('ai','sample_source'),('ai','sample_revision')
  ) AS t(schema_name, table_name)
), foundation_state AS (
  SELECT count(*) FILTER (WHERE to_regclass(format('%I.%I',schema_name,table_name)) IS NULL) AS missing_count,
         COALESCE(array_agg(format('%I.%I',schema_name,table_name)
             ORDER BY schema_name,table_name)
             FILTER (WHERE to_regclass(format('%I.%I',schema_name,table_name)) IS NULL), ARRAY[]::text[]) AS missing
  FROM foundation
), flyway_state AS (
  SELECT CASE WHEN to_regclass('public.flyway_schema_history') IS NULL THEN 0
              ELSE ((xpath('//row/max_version/text()', query_to_xml(
                    'select coalesce(max(version::int),0) as max_version
                       from flyway_schema_history where success', false, true, '')))[1]::text)::int END AS current_version
), links AS (
  SELECT CASE WHEN to_regclass('ai.research_experiment_link') IS NULL THEN 0
              ELSE ((xpath('//row/count/text()', query_to_xml(
                    'select count(*) from ai.research_experiment_link', false, true, '')))[1]::text)::bigint END AS total,
         CASE WHEN to_regclass('ai.research_experiment_link') IS NULL THEN 0
              WHEN to_regclass('rnd.experiment_ai_source_evidence') IS NULL THEN
                ((xpath('//row/count/text()', query_to_xml(
                    'select count(*) from ai.research_experiment_link', false, true, '')))[1]::text)::bigint
              ELSE ((xpath('//row/count/text()', query_to_xml(
                    'select count(*) from ai.research_experiment_link l
                       where not exists (select 1 from rnd.experiment_ai_source_evidence e
                                         where e.organization_id=l.organization_id
                                           and e.experiment_version_id=l.experiment_version_id
                                           and e.legacy_research_run_id=l.research_run_id
                                           and e.legacy_candidate_id=l.research_candidate_id)',
                    false, true, '')))[1]::text)::bigint END AS unpreserved
), protected AS (
  SELECT jsonb_build_object(
    'fileObjects', CASE WHEN to_regclass('ops.file_object') IS NULL THEN 0 ELSE
      ((xpath('//row/count/text()', query_to_xml('select count(*) from ops.file_object',false,true,'')))[1]::text)::bigint END,
    'publicAudits', CASE WHEN to_regclass('ops.audit_log') IS NULL THEN 0 ELSE
      ((xpath('//row/count/text()', query_to_xml('select count(*) from ops.audit_log',false,true,'')))[1]::text)::bigint END,
    'dataRecords', CASE WHEN to_regclass('data.data_record') IS NULL THEN 0 ELSE
      ((xpath('//row/count/text()', query_to_xml('select count(*) from data.data_record',false,true,'')))[1]::text)::bigint END,
    'materials', CASE WHEN to_regclass('mdm.material') IS NULL THEN 0 ELSE
      ((xpath('//row/count/text()', query_to_xml('select count(*) from mdm.material',false,true,'')))[1]::text)::bigint END,
    'experiments', CASE WHEN to_regclass('rnd.experiment') IS NULL THEN 0 ELSE
      ((xpath('//row/count/text()', query_to_xml('select count(*) from rnd.experiment',false,true,'')))[1]::text)::bigint END,
    'experimentEvidence', CASE WHEN to_regclass('rnd.experiment_ai_source_evidence') IS NULL THEN 0 ELSE
      ((xpath('//row/count/text()', query_to_xml('select count(*) from rnd.experiment_ai_source_evidence',false,true,'')))[1]::text)::bigint END
  ) AS counts
), classification AS (
  SELECT CASE
    WHEN fs.current_version=52 AND f.missing_count >= 8 THEN 'EMPTY_FOUNDATION'
    WHEN f.missing_count=0 THEN 'COMPLETE_FOUNDATION'
    ELSE 'INCOMPATIBLE_FOUNDATION'
  END AS foundation_status,
  fs.current_version, f.missing_count, f.missing, l.total, l.unpreserved
  FROM flyway_state fs CROSS JOIN foundation_state f CROSS JOIN links l
)
SELECT jsonb_build_object(
  'readOnly',true,
  'foundationStatus',c.foundation_status,
  'currentFlywayVersion',c.current_version,
  'missingFoundationObjects',to_jsonb(c.missing),
  'legacyTables',(SELECT COALESCE(jsonb_agg(jsonb_build_object('schema',schema_name,'table',table_name,'present',present)
                                   ORDER BY schema_name,table_name),'[]'::jsonb) FROM presence),
  'legacyLinks',jsonb_build_object('total',c.total,'unpreserved',c.unpreserved),
  'protectedCounts',p.counts,
  -- V63 is a one-time cutover.  A post-cutover database is complete but is
  -- never reported as ready to execute the destructive migration again.
  'readyForV63',c.current_version = 62 AND c.missing_count=0 AND c.unpreserved=0
) FROM classification c CROSS JOIN protected p;
