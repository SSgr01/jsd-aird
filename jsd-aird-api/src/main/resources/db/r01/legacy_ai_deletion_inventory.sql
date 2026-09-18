-- Read-only R10 deletion inventory. This file never drops data.
WITH legacy_tables(drop_order, schema_name, table_name, prerequisite) AS (
  VALUES
    (10,'ai','training_dataset_record','Stop legacy dataset writers and retain source coordinates in the new sample revision projection.'),
    (20,'data','import_job','Drop only latest_training_dataset_id and its FK; keep the import job.'),
    (30,'ai','training_dataset','Verify every retained training fact has a source version and coordinate.'),
    (40,'ai','research_experiment_link','Preserve every link in rnd.experiment_ai_source_evidence first.'),
    (50,'ai','research_candidate','Verify preserved formula, estimates, model context and rule evidence.'),
    (60,'ai','research_run','Verify preserved request hash and run summary.'),
    (70,'ai','formula_model_activation','Stop V1 scoring and record the final active-model inventory.'),
    (80,'ai','formula_model_target','Retain required validation evidence in the new model card.'),
    (90,'ai','formula_model_version','Inventory model_bundle_key before object-store cleanup.'),
    (100,'ai','formula_model_snapshot','Inventory object_prefix and artifacts_jsonb before object-store cleanup.'),
    (110,'ai','formulation_task_profile','Switch all callers to versioned Y/X and formula-model.v2 first.')
), relation_inventory AS (
  SELECT l.*,
         to_regclass(format('%I.%I', l.schema_name, l.table_name)) IS NOT NULL AS present,
         CASE
           WHEN l.schema_name='data' AND l.table_name='import_job' THEN
             EXISTS (
               SELECT 1 FROM information_schema.columns c
               WHERE c.table_schema='data' AND c.table_name='import_job'
                 AND c.column_name='latest_training_dataset_id'
             )
           ELSE true
         END AS relevant
  FROM legacy_tables l
), referencing_foreign_keys AS (
  SELECT
    ns.nspname AS source_schema,
    rel.relname AS source_table,
    con.conname AS constraint_name,
    tns.nspname AS target_schema,
    target.relname AS target_table
  FROM pg_constraint con
  JOIN pg_class rel ON rel.oid=con.conrelid
  JOIN pg_namespace ns ON ns.oid=rel.relnamespace
  JOIN pg_class target ON target.oid=con.confrelid
  JOIN pg_namespace tns ON tns.oid=target.relnamespace
  WHERE con.contype='f'
    AND EXISTS (
      SELECT 1 FROM legacy_tables l
      WHERE l.schema_name=tns.nspname AND l.table_name=target.relname
    )
), protection AS (
  SELECT
    (SELECT count(*) FROM ai.research_experiment_link) AS legacy_link_count,
    (SELECT count(*) FROM rnd.experiment_ai_source_evidence
      WHERE legacy_research_run_id IS NOT NULL AND legacy_candidate_id IS NOT NULL) AS preserved_evidence_count,
    (SELECT count(*)
       FROM ai.research_experiment_link l
       WHERE NOT EXISTS (
         SELECT 1 FROM rnd.experiment_ai_source_evidence e
         WHERE e.organization_id=l.organization_id
           AND e.experiment_version_id=l.experiment_version_id
           AND e.legacy_research_run_id=l.research_run_id
           AND e.legacy_candidate_id=l.research_candidate_id
       )) AS unpreserved_link_count,
    (SELECT count(*) FROM data.import_job WHERE latest_training_dataset_id IS NOT NULL) AS dataset_head_pointer_count,
    (SELECT count(*) FROM ai.formula_model_version
      WHERE model_bundle_key IS NOT NULL AND btrim(model_bundle_key)<>'') AS model_bundle_object_count,
    (SELECT count(*) FROM ai.formula_model_snapshot
      WHERE btrim(object_prefix)<>'' OR artifacts_jsonb<>'{}'::jsonb) AS snapshot_object_manifest_count
)
SELECT jsonb_build_object(
  'readOnly', true,
  'dropPlan', (
    SELECT jsonb_agg(jsonb_build_object(
      'order',drop_order,
      'object',format('%I.%I',schema_name,table_name),
      'present',present,
      'relevant',relevant,
      'prerequisite',prerequisite
    ) ORDER BY drop_order)
    FROM relation_inventory
  ),
  'foreignKeys', COALESCE((
    SELECT jsonb_agg(jsonb_build_object(
      'source',format('%I.%I',source_schema,source_table),
      'constraint',constraint_name,
      'target',format('%I.%I',target_schema,target_table)
    ) ORDER BY source_schema,source_table,constraint_name)
    FROM referencing_foreign_keys
  ),'[]'::jsonb),
  'evidence', jsonb_build_object(
    'legacyLinkCount',protection.legacy_link_count,
    'preservedEvidenceCount',protection.preserved_evidence_count,
    'unpreservedLinkCount',protection.unpreserved_link_count
  ),
  'objectStorage', jsonb_build_object(
    'modelBundleReferenceCount',protection.model_bundle_object_count,
    'snapshotManifestCount',protection.snapshot_object_manifest_count
  ),
  'datasetHeadPointerCount', protection.dataset_head_pointer_count,
  'deletionBlocked',
    protection.unpreserved_link_count>0
) FROM protection;
