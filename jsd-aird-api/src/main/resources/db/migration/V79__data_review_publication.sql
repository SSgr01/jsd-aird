-- Parsing completion, human confirmation and publication are independent states.
CREATE TABLE data.publication_snapshot (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    import_job_id uuid NOT NULL REFERENCES data.import_job(id),
    version_no integer NOT NULL,
    content_hash varchar(64) NOT NULL,
    payload_jsonb jsonb NOT NULL,
    confirmed_submission_id uuid REFERENCES data.confirmed_submission(id),
    confirmed_by uuid NOT NULL REFERENCES iam.app_user(id),
    confirmed_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, import_job_id, version_no)
);
CREATE TABLE data.publication (
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    import_job_id uuid NOT NULL REFERENCES data.import_job(id),
    status varchar(24) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT','CONFIRMED','PENDING_REVIEW','RETURNED','APPROVED','PUBLISHED','DISABLED')),
    confirmed_snapshot_id uuid REFERENCES data.publication_snapshot(id),
    published_snapshot_id uuid REFERENCES data.publication_snapshot(id),
    legacy_published boolean NOT NULL DEFAULT false,
    published_enabled boolean NOT NULL DEFAULT true,
    reviewer_id uuid REFERENCES iam.app_user(id),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, import_job_id)
);
CREATE TABLE data.publication_event (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    import_job_id uuid NOT NULL REFERENCES data.import_job(id),
    snapshot_id uuid REFERENCES data.publication_snapshot(id),
    action varchar(32) NOT NULL,
    actor_id uuid NOT NULL REFERENCES iam.app_user(id),
    actor_name varchar(200) NOT NULL,
    comment text,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_data_publication_event_job ON data.publication_event(organization_id,import_job_id,created_at);
CREATE TABLE data.publication_release (
    snapshot_id uuid PRIMARY KEY REFERENCES data.publication_snapshot(id),
    confirmed_submission_id uuid NOT NULL REFERENCES data.confirmed_submission(id),
    published_by uuid NOT NULL REFERENCES iam.app_user(id),
    published_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE data.record_governance (
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    import_job_id uuid NOT NULL REFERENCES data.import_job(id),
    record_id uuid NOT NULL,
    display_name varchar(200),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
    reason text,
    updated_by uuid NOT NULL REFERENCES iam.app_user(id),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id,record_id)
);
CREATE TABLE data.published_mapping (LIKE data.import_mapping INCLUDING DEFAULTS);
CREATE INDEX idx_data_published_mapping_job ON data.published_mapping(import_job_id);
CREATE FUNCTION data.protect_publication_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Publication snapshots are immutable';
END;
$$;
CREATE TRIGGER immutable_data_publication_snapshot BEFORE UPDATE OR DELETE ON data.publication_snapshot
    FOR EACH ROW EXECUTE FUNCTION data.protect_publication_snapshot();
CREATE TRIGGER immutable_data_publication_release BEFORE UPDATE OR DELETE ON data.publication_release
    FOR EACH ROW EXECUTE FUNCTION data.protect_publication_snapshot();

-- Existing published data retains its current behaviour. New managed revisions
-- expose only their approved projection, including while a new draft is parsed.

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
       meta.target_path,
       e.title AS experiment_title,
       e.category_name AS experiment_category_name,
       nullif(btrim(ev.edit_model_jsonb->>'purpose'), '') AS experiment_purpose,
       project_context.name AS project_name,
       stage_context.name AS experiment_stage_name,
       task_context.name AS experiment_task_name
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
    FROM (
        SELECT * FROM data.published_mapping
        UNION ALL
        SELECT live.* FROM data.import_mapping live
        WHERE NOT EXISTS(SELECT 1 FROM data.publication pub
            WHERE pub.import_job_id=live.import_job_id
              AND (pub.published_snapshot_id IS NOT NULL OR pub.legacy_published))
    ) m
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
LEFT JOIN rnd.experiment_version ev
  ON ev.organization_id = e.organization_id
 AND ev.experiment_id = e.id
 AND ev.id = coalesce(link.experiment_version_id, e.current_version_id)
LEFT JOIN mdm.project project_context
  ON project_context.id = e.project_id
 AND project_context.deleted = false
LEFT JOIN mdm.project_stage stage_context
  ON stage_context.id = e.stage_id
 AND stage_context.deleted = false
LEFT JOIN mdm.project_task task_context
  ON task_context.id = e.task_id
 AND task_context.deleted = false
WHERE ((j.source_owner='DATA_CENTER' AND EXISTS(SELECT 1 FROM data.publication pub
            WHERE pub.organization_id=j.organization_id AND pub.import_job_id=j.id
              AND (pub.published_snapshot_id IS NOT NULL OR pub.legacy_published)))
        OR (j.status='COMPLETED' AND NOT EXISTS(SELECT 1 FROM data.publication pub
            WHERE pub.organization_id=j.organization_id AND pub.import_job_id=j.id)))
  AND NOT EXISTS(SELECT 1 FROM data.record_governance g
        WHERE g.organization_id=r.organization_id AND g.record_id=r.id AND g.status<>'ACTIVE')
  AND NOT EXISTS(SELECT 1 FROM data.publication pub
        WHERE pub.organization_id=j.organization_id AND pub.import_job_id=j.id AND NOT pub.published_enabled)
  AND r.quality_status <> 'BLOCKED'
  AND v.rag_eligible = true
  AND (v.value_source <> 'FORMULA' OR v.calculation_status = 'VALID')
  AND nullif(btrim(v.value_text), '') IS NOT NULL
  AND (j.source_owner = 'DATA_CENTER'
       OR (j.source_owner = 'EXPERIMENT'
           AND j.recognition_mode = 'TEMPLATE_GUIDED'
           AND link.experiment_id IS NOT NULL
           AND e.id IS NOT NULL));
