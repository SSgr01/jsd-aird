-- R01 isolated rehearsal fixture. This is synthetic test data only.
-- Apply after V56 and the mdm/rnd foundation, never to a shared environment.

BEGIN;

INSERT INTO iam.organization (id, name)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'R01 rehearsal organization'),
    ('00000000-0000-0000-0000-000000000002', 'R01 foreign organization')
ON CONFLICT (id) DO NOTHING;

INSERT INTO iam.app_user (id, organization_id, username, display_name)
VALUES
    ('00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000001', 'r01-user', 'R01 rehearsal user'),
    ('00000000-0000-0000-0000-000000000102', '00000000-0000-0000-0000-000000000002', 'r01-foreign-user', 'R01 foreign user')
ON CONFLICT (id) DO NOTHING;

INSERT INTO ops.file_object (
    id, organization_id, bucket, object_key, original_name, content_type,
    size_bytes, sha256, status, created_by
)
VALUES (
    '00000000-0000-0000-0000-000000000201',
    '00000000-0000-0000-0000-000000000001',
    'r01-rehearsal',
    'fixtures/source.xlsx',
    'R01-synthetic-source.xlsx',
    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    988,
    repeat('1', 64),
    'ACTIVE',
    '00000000-0000-0000-0000-000000000101'
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO ops.audit_log (
    id, organization_id, actor_id, action, aggregate_type, aggregate_id, detail_jsonb
)
VALUES (
    '00000000-0000-0000-0000-000000000202',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000101',
    'R01_REHEARSAL_CREATED',
    'R01_FIXTURE',
    '00000000-0000-0000-0000-000000000201',
    '{"synthetic":true,"purpose":"R01 protected-data rehearsal"}'::jsonb
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO data.import_job (
    id, organization_id, source_file_id, source_sha256, source_file_name,
    source_format, template_version_id, status, progress, duplicate_override,
    created_by, compatibility_status, compatibility_report_jsonb, import_purpose
)
VALUES (
    '00000000-0000-0000-0000-000000000301',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000201',
    repeat('2', 64),
    'R01-synthetic-source.xlsx',
    'XLSX',
    '00000000-0000-0000-0000-000000000302',
    'COMPLETED',
    100,
    false,
    '00000000-0000-0000-0000-000000000101',
    'EXACT',
    '{"synthetic":true}'::jsonb,
    'DATA_ONLY'
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO data.data_record (
    id, organization_id, import_job_id, record_key, record_index,
    raw_data_jsonb, normalized_data_jsonb, corrected_data_jsonb,
    effective_data_jsonb, quality_status, synthetic_key
)
VALUES (
    '00000000-0000-0000-0000-000000000303',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000301',
    'R01-SAMPLE-001',
    1,
    '{"solidPercent":98.8,"gloss60":86.4}'::jsonb,
    '{"solidPercent":98.8,"gloss60":86.4}'::jsonb,
    '{}'::jsonb,
    '{"solidPercent":98.8,"gloss60":86.4}'::jsonb,
    'VALID',
    false
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO data.data_value (
    id, organization_id, record_id, field_code, value_jsonb,
    training_eligible, rag_eligible, value_source
)
VALUES (
    '00000000-0000-0000-0000-000000000304',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000303',
    'solid_percent',
    '98.8'::jsonb,
    true,
    true,
    'INPUT'
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO mdm.material (
    id, code, name, category, source_category, source_module,
    status, created_by, updated_by
)
VALUES (
    '00000000-0000-0000-0000-000000000401',
    'R01-MAT-001',
    'R01 synthetic material',
    'RESIN',
    'R01_FIXTURE',
    'R01',
    'ACTIVE',
    'r01-rehearsal',
    'r01-rehearsal'
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO mdm.project (
    id, project_code, name, priority, status, team_size,
    custom_fields, team_members, created_by, updated_by
)
VALUES (
    '00000000-0000-0000-0000-000000000402',
    'R01-PROJECT-001',
    'R01 synthetic project',
    'MEDIUM',
    'IN_PROGRESS',
    1,
    '{"synthetic":true}'::jsonb,
    '["r01-user"]'::jsonb,
    'r01-rehearsal',
    'r01-rehearsal'
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO rnd.experiment (
    id, organization_id, experiment_no, title, source_type, status,
    classification_status, project_id, source_file_id, current_version_id,
    created_by, updated_by
)
VALUES (
    '00000000-0000-0000-0000-000000000501',
    '00000000-0000-0000-0000-000000000001',
    'R01-EXP-001',
    'R01 synthetic completed experiment',
    'EXCEL_IMPORT',
    'COMPLETED',
    'CLASSIFIED',
    '00000000-0000-0000-0000-000000000402',
    '00000000-0000-0000-0000-000000000201',
    NULL,
    '00000000-0000-0000-0000-000000000101',
    '00000000-0000-0000-0000-000000000101'
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO rnd.experiment_version (
    id, organization_id, experiment_id, version_no, status,
    template_snapshot_jsonb, edit_model_jsonb, created_by
)
VALUES (
    '00000000-0000-0000-0000-000000000502',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000501',
    1,
    'COMPLETED',
    '{"synthetic":true,"template":"R01"}'::jsonb,
    '{"formula":{"totalPercent":98.8},"results":{"gloss60":86.4}}'::jsonb,
    '00000000-0000-0000-0000-000000000101'
)
ON CONFLICT (id) DO NOTHING;

UPDATE rnd.experiment
SET current_version_id = '00000000-0000-0000-0000-000000000502'
WHERE id = '00000000-0000-0000-0000-000000000501'
  AND current_version_id IS DISTINCT FROM '00000000-0000-0000-0000-000000000502';

INSERT INTO ai.research_run (
    id, organization_id, run_type, mode, status, task_profile_code,
    idempotency_key, request_hash, request_jsonb, result_jsonb, created_by
)
VALUES (
    '00000000-0000-0000-0000-000000000601',
    '00000000-0000-0000-0000-000000000001',
    'FORMULA_PREDICTION',
    'MODEL',
    'SUCCEEDED',
    'R01-LEGACY-PROFILE',
    'r01-legacy-run-001',
    repeat('3', 64),
    '{"targets":{"gloss60":86},"synthetic":true}'::jsonb,
    '{"candidateCount":1,"synthetic":true}'::jsonb,
    '00000000-0000-0000-0000-000000000101'
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO ai.research_candidate (
    id, organization_id, research_run_id, candidate_no, strategy, title,
    formula_jsonb, process_jsonb, estimates_jsonb, rule_check_jsonb,
    evidence_jsonb, confidence, content_hash, model_context_jsonb
)
VALUES (
    '00000000-0000-0000-0000-000000000602',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000601',
    1,
    'BALANCED',
    'R01 synthetic candidate',
    '{"components":[{"materialCode":"R01-MAT-001","percent":98.8}]}'::jsonb,
    '{"temperatureC":25}'::jsonb,
    '{"gloss60":{"value":86.4,"unit":"GU"}}'::jsonb,
    '{"passed":true}'::jsonb,
    '{"synthetic":true}'::jsonb,
    'MEDIUM',
    repeat('4', 64),
    '{"modelVersionId":"00000000-0000-0000-0000-000000000699","modelCode":"R01-SYNTHETIC"}'::jsonb
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO ai.research_experiment_link (
    id, organization_id, research_run_id, research_candidate_id,
    experiment_id, experiment_version_id, experiment_no,
    idempotency_key, created_by
)
VALUES (
    '00000000-0000-0000-0000-000000000603',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000601',
    '00000000-0000-0000-0000-000000000602',
    '00000000-0000-0000-0000-000000000501',
    '00000000-0000-0000-0000-000000000502',
    'R01-EXP-001',
    'r01-legacy-link-001',
    '00000000-0000-0000-0000-000000000101'
)
ON CONFLICT (id) DO NOTHING;

COMMIT;
