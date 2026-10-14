-- Read-only R01 foundation classification. psql prints exactly one JSON value.
WITH required_tables(schema_name, table_name) AS (
    VALUES
      ('mdm','business_partner'),('mdm','partner_contact'),('mdm','partner_contact_project'),
      ('mdm','communication_record'),('mdm','customer_requirement'),('mdm','material'),
      ('mdm','project'),('mdm','project_stage'),('mdm','project_task'),('mdm','project_material'),
      ('mdm','meeting_minutes'),('mdm','project_document'),('mdm','project_document_version'),
      ('mdm','project_document_review'),('mdm','project_document_audit'),
      ('rnd','experiment'),('rnd','experiment_version'),('rnd','experiment_review'),
      ('rnd','experiment_audit'),('rnd','experiment_attachment'),('rnd','experiment_outbox'),
      ('rnd','experiment_category'),('rnd','experiment_import_job'),('rnd','research_test_record'),
      ('rnd','research_test_version'),('rnd','research_test_review'),('rnd','research_test_audit'),
      ('rnd','research_test_upload')
), signatures(schema_name, table_name, column_name, udt_name) AS (
    VALUES
      ('mdm','business_partner','id','uuid'),('mdm','business_partner','version','int8'),('mdm','business_partner','custom_fields','jsonb'),
      ('mdm','partner_contact','partner_id','uuid'),('mdm','partner_contact','assigned_project_ids','jsonb'),
      ('mdm','partner_contact_project','contact_id','uuid'),('mdm','partner_contact_project','project_id','uuid'),
      ('mdm','communication_record','communicated_at','timestamptz'),('mdm','communication_record','deleted','bool'),
      ('mdm','customer_requirement','raw_requirement','text'),('mdm','customer_requirement','assigned_project_ids','jsonb'),
      ('mdm','material','code','varchar'),('mdm','material','version','int8'),
      ('mdm','project','project_code','varchar'),('mdm','project','team_members','jsonb'),('mdm','project','deleted','bool'),
      ('mdm','project_stage','stage_code','varchar'),('mdm','project_stage','order_no','int4'),('mdm','project_stage','deleted','bool'),
      ('mdm','project_task','task_code','varchar'),('mdm','project_task','stage_id','uuid'),('mdm','project_task','deleted','bool'),
      ('mdm','project_material','material_id','uuid'),('mdm','meeting_minutes','attendees','jsonb'),
      ('mdm','project_document','current_version_id','uuid'),('mdm','project_document','content_data','jsonb'),('mdm','project_document','content_structure','jsonb'),
      ('mdm','project_document_version','content_jsonb','jsonb'),('mdm','project_document_version','version_no','int4'),('mdm','project_document_version','template_snapshot_hash','varchar'),
      ('mdm','project_document_review','document_version_id','uuid'),('mdm','project_document_audit','document_version_id','uuid'),
      ('rnd','experiment','organization_id','uuid'),('rnd','experiment','current_version_id','uuid'),('rnd','experiment','revision','int8'),
      ('rnd','experiment_version','edit_model_jsonb','jsonb'),('rnd','experiment_version','version_no','int4'),
      ('rnd','experiment_review','experiment_version_id','uuid'),('rnd','experiment_audit','before_jsonb','jsonb'),
      ('rnd','experiment_attachment','file_id','uuid'),('rnd','experiment_outbox','payload_jsonb','jsonb'),
      ('rnd','experiment_outbox','attempts','int4'),('rnd','experiment_outbox','available_at','timestamptz'),
      ('rnd','experiment_category','revision','int8'),('rnd','experiment_import_job','source_sha256','bpchar'),
      ('rnd','research_test_record','record_type','varchar'),('rnd','research_test_record','lock_version','int8'),
      ('rnd','research_test_version','member_snapshot_jsonb','jsonb'),('rnd','research_test_review','version_id','uuid'),
      ('rnd','research_test_audit','version_id','uuid'),('rnd','research_test_upload','sha256','varchar')
), required_constraints(schema_name,table_name,constraint_name) AS (
    VALUES
      ('mdm','business_partner','business_partner_status_check'),
      ('mdm','partner_contact','partner_contact_status_check'),
      ('mdm','communication_record','communication_status_check'),
      ('mdm','customer_requirement','requirement_status_check'),
      ('mdm','project','project_priority_check'),
      ('mdm','project','project_status_check'),
      ('mdm','project','project_team_size_check'),
      ('rnd','experiment','experiment_source_type_check'),
      ('rnd','experiment','experiment_status_check'),
      ('rnd','experiment_version','experiment_version_status_check'),
      ('rnd','research_test_record','research_test_record_type_check'),
      ('rnd','research_test_record','research_test_record_document_format_check'),
      ('rnd','research_test_record','research_test_record_source_type_check'),
      ('rnd','research_test_record','research_test_record_status_check'),
      ('rnd','research_test_record','research_test_record_visibility_check'),
      ('rnd','research_test_version','research_test_version_status_check')
), required_indexes(schema_name,index_name) AS (
    VALUES
      ('mdm','idx_project_updated'),('mdm','idx_project_document_project'),
      ('mdm','idx_project_stage_project_status_order'),('mdm','idx_project_task_stage'),
      ('rnd','idx_eln_list'),('rnd','idx_eln_project'),('rnd','idx_eln_version_history'),
      ('rnd','idx_experiment_outbox_pending'),('rnd','idx_experiment_import_job_status'),
      ('rnd','idx_research_test_list'),('rnd','idx_research_test_uploads')
), present AS (
    SELECT r.*, (t.table_name IS NOT NULL) AS exists
    FROM required_tables r LEFT JOIN information_schema.tables t
      ON t.table_schema=r.schema_name AND t.table_name=r.table_name AND t.table_type='BASE TABLE'
), signature_diff AS (
    SELECT s.*, c.udt_name AS actual_type,
           CASE WHEN c.column_name IS NULL THEN 'MISSING_COLUMN'
                WHEN c.udt_name <> s.udt_name THEN 'WRONG_TYPE' END AS problem
    FROM signatures s LEFT JOIN information_schema.columns c
      ON c.table_schema=s.schema_name AND c.table_name=s.table_name AND c.column_name=s.column_name
    WHERE c.column_name IS NULL OR c.udt_name <> s.udt_name
), missing_pk AS (
    SELECT r.schema_name, r.table_name
    FROM required_tables r
    WHERE NOT EXISTS (
      SELECT 1 FROM information_schema.table_constraints c
      WHERE c.table_schema=r.schema_name AND c.table_name=r.table_name AND c.constraint_type='PRIMARY KEY'
    )
), missing_constraints AS (
    SELECT r.*
    FROM required_constraints r
    WHERE NOT EXISTS (
      SELECT 1 FROM information_schema.table_constraints c
      WHERE c.table_schema=r.schema_name AND c.table_name=r.table_name
        AND c.constraint_name=r.constraint_name
    )
), missing_indexes AS (
    SELECT r.*
    FROM required_indexes r
    WHERE NOT EXISTS (
      SELECT 1 FROM pg_indexes i
      WHERE i.schemaname=r.schema_name AND i.indexname=r.index_name
    )
), repository_conflicts AS (
    SELECT c.table_schema AS schema_name,c.table_name,c.column_name,
           'REQUIRED_COLUMN_NOT_WRITTEN_BY_ACTIVE_REPOSITORY'::text AS problem
    FROM information_schema.columns c
    WHERE c.table_schema='mdm' AND c.table_name='project' AND c.column_name='organization_id'
      AND c.is_nullable='NO' AND c.column_default IS NULL
), flyway_state AS (
    SELECT CASE WHEN to_regclass('public.flyway_schema_history') IS NULL THEN 0 ELSE
      ((xpath('//row/version/text()', query_to_xml(
        'select max(version::integer) as version from public.flyway_schema_history where success',
        false, true, '')))[1]::text)::integer
    END AS flyway_version
), state AS (
    SELECT
      COALESCE(flyway_state.flyway_version,0) AS flyway_version,
      (SELECT count(*) FROM present WHERE exists) AS present_count,
      (SELECT count(*) FROM required_tables) AS required_count,
      COALESCE((SELECT jsonb_agg(format('%I.%I',schema_name,table_name) ORDER BY schema_name,table_name) FROM present WHERE NOT exists),'[]') AS missing_tables,
      COALESCE((SELECT jsonb_agg(jsonb_build_object('table',format('%I.%I',schema_name,table_name),'column',column_name,'expectedType',udt_name,'actualType',actual_type,'problem',problem) ORDER BY schema_name,table_name,column_name) FROM signature_diff),'[]') AS signature_differences,
      COALESCE((SELECT jsonb_agg(format('%I.%I',schema_name,table_name) ORDER BY schema_name,table_name) FROM missing_pk),'[]') AS missing_primary_keys,
      COALESCE((SELECT jsonb_agg(format('%I.%I:%I',schema_name,table_name,constraint_name) ORDER BY schema_name,table_name,constraint_name) FROM missing_constraints),'[]') AS missing_constraints,
      COALESCE((SELECT jsonb_agg(format('%I.%I',schema_name,index_name) ORDER BY schema_name,index_name) FROM missing_indexes),'[]') AS missing_indexes,
      COALESCE((SELECT jsonb_agg(jsonb_build_object('table',format('%I.%I',schema_name,table_name),'column',column_name,'problem',problem)) FROM repository_conflicts),'[]') AS repository_conflicts,
      (to_regclass('rnd.experiment_no_seq') IS NOT NULL AND to_regclass('rnd.comprehensive_report_no_seq') IS NOT NULL) AS sequences_complete
    FROM flyway_state
)
SELECT jsonb_build_object(
  'classification', CASE
    WHEN flyway_version=52 AND present_count=0 THEN 'EMPTY_FOUNDATION'
    WHEN present_count=required_count AND jsonb_array_length(signature_differences)=0
         AND jsonb_array_length(missing_primary_keys)=0
         AND jsonb_array_length(missing_constraints)=0
         AND jsonb_array_length(missing_indexes)=0
         AND jsonb_array_length(repository_conflicts)=0 AND sequences_complete THEN 'COMPLETE_FOUNDATION'
    ELSE 'INCOMPATIBLE_FOUNDATION' END,
  'serverVersion', current_setting('server_version'), 'flywayVersion', flyway_version,
  'requiredTableCount', required_count, 'presentTableCount', present_count,
  'missingTables', missing_tables, 'signatureDifferences', signature_differences,
  'missingPrimaryKeys', missing_primary_keys, 'missingConstraints', missing_constraints,
  'missingIndexes', missing_indexes, 'repositoryConflicts', repository_conflicts,
  'sequencesComplete', sequences_complete,
  'readOnly', true
) FROM state;
