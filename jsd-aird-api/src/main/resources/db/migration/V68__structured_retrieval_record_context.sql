-- Keep the value projection searchable while preserving the mapping semantics
-- needed to explain a value.  In particular, labelPath is the source label
-- for repeated fields and must not be replaced by the shared field_name.
CREATE OR REPLACE VIEW ai.structured_retrieval_projection AS
SELECT j.organization_id,
       j.id AS import_job_id,
       j.source_file_id AS file_object_id,
       j.source_file_name AS original_name,
       j.source_format,
       j.source_owner,
       j.recognition_mode,
       j.created_by AS import_created_by,
       j.category_id AS data_category_id,
       coalesce(e.category_id, j.target_experiment_category_id) AS experiment_category_id,
       coalesce(j.completed_at, j.updated_at) AS updated_at,
       r.id AS record_id,
       r.record_key,
       r.record_index,
       r.synthetic_key,
       r.sheet_id,
       r.sheet_name,
       r.source_row_number,
       v.id AS value_id,
       v.binding_id,
       v.value_path,
       v.field_code,
       coalesce(meta.field_name, meta.source_header, v.field_code) AS field_name,
       meta.value_type,
       v.value_text AS field_value,
       v.normalized_unit,
       a.column_name,
       a.cell_address,
       a.column_number,
       a.row_number AS anchor_row_number,
       link.experiment_id,
       link.experiment_version_id,
       e.experiment_no,
       e.owner_id AS experiment_owner_id,
       e.created_by AS experiment_created_by,
       e.project_id AS experiment_project_id,
       e.stage_id AS experiment_stage_id,
       e.task_id AS experiment_task_id,
       e.status AS experiment_status,
       meta.source_label,
       meta.semantic_domain,
       meta.semantic_field,
       meta.declared_condition,
       meta.source_unit,
       coalesce(meta.standard_unit, v.normalized_unit) AS standard_unit,
       meta.mapping_kind,
       meta.component_id,
       meta.parent_binding_id,
       meta.target_path
FROM data.import_job j
JOIN data.data_record r
  ON r.organization_id = j.organization_id
 AND r.import_job_id = j.id
JOIN data.data_value v
  ON v.organization_id = r.organization_id
 AND v.record_id = r.id
LEFT JOIN LATERAL (
    SELECT m.field_name,
           m.source_header,
           nullif(btrim(m.mapping_jsonb->>'labelPath'), '') AS source_label,
           nullif(m.mapping_jsonb->'experimentField'->>'domain', '') AS semantic_domain,
           nullif(m.mapping_jsonb->'experimentField'->>'field', '') AS semantic_field,
           nullif(btrim(m.mapping_jsonb->>'condition'), '') AS declared_condition,
           coalesce(nullif(btrim(m.source_unit), ''), nullif(btrim(m.mapping_jsonb->>'sourceUnit'), '')) AS source_unit,
           coalesce(nullif(btrim(m.standard_unit), ''), nullif(btrim(m.mapping_jsonb->>'standardUnit'), '')) AS standard_unit,
           nullif(m.mapping_jsonb->>'mappingKind', '') AS mapping_kind,
           nullif(m.mapping_jsonb->>'componentId', '') AS component_id,
           nullif(m.mapping_jsonb->>'parentBindingId', '') AS parent_binding_id,
           nullif(m.mapping_jsonb->>'dataPath', '') AS target_path,
           m.value_type,
           m.mapping_jsonb
    FROM data.import_mapping m
    WHERE m.import_job_id = j.id
      AND (m.mapping_jsonb->>'dataPath' = v.value_path
           OR coalesce(m.mapping_jsonb->>'bindingId', m.field_code) = v.binding_id
           OR m.field_code = v.field_code)
    ORDER BY CASE WHEN m.mapping_jsonb->>'dataPath' = v.value_path THEN 0
                  WHEN coalesce(m.mapping_jsonb->>'bindingId', m.field_code) = v.binding_id THEN 1
                  ELSE 2 END,
             CASE WHEN m.status IN ('CONFIRMED', 'MATCHED') THEN 0 ELSE 1 END,
             m.updated_at DESC
    LIMIT 1
) meta ON true
LEFT JOIN LATERAL (
    SELECT candidate.sheet_name, candidate.row_number, candidate.column_number,
           candidate.column_name, candidate.cell_address
    FROM data.source_anchor candidate
    WHERE candidate.record_id = r.id
      AND (candidate.value_path = v.value_path
           OR (candidate.binding_id = v.binding_id AND candidate.field_code = v.field_code)
           OR candidate.id = v.source_anchor_id)
    ORDER BY CASE WHEN candidate.value_path = v.value_path THEN 0
                  WHEN candidate.binding_id = v.binding_id
                       AND candidate.field_code = v.field_code THEN 1
                  ELSE 2 END,
             candidate.created_at DESC
    LIMIT 1
) a ON true
LEFT JOIN LATERAL (
    SELECT l.experiment_id, l.experiment_version_id
    FROM data.import_experiment_link l
    WHERE l.organization_id = j.organization_id
      AND l.import_job_id = j.id
      AND l.experiment_id IS NOT NULL
      AND l.status NOT IN ('BLOCKED', 'FAILED', 'SKIPPED')
    ORDER BY l.updated_at DESC, l.id DESC
    LIMIT 1
) link ON true
LEFT JOIN rnd.experiment e
  ON e.organization_id = j.organization_id
 AND e.id = link.experiment_id
 AND e.deleted = false
WHERE j.status = 'COMPLETED'
  AND r.quality_status <> 'BLOCKED'
  AND v.rag_eligible = true
  AND (v.value_source <> 'FORMULA' OR v.calculation_status = 'VALID')
  AND nullif(btrim(v.value_text), '') IS NOT NULL
  AND (j.source_owner = 'DATA_CENTER'
       OR (j.source_owner = 'EXPERIMENT'
           AND j.recognition_mode = 'TEMPLATE_GUIDED'
           AND link.experiment_id IS NOT NULL
           AND e.id IS NOT NULL));
