-- Read-only protected-data inventory. Hashes are deterministic and contain no row values.
WITH inventories(schema_name, table_name, row_count, content_hash, present) AS (
  SELECT 'ops','file_object',count(*),encode(digest(COALESCE(string_agg(to_jsonb(t)::text,'' ORDER BY id::text),''),'sha256'),'hex'),true FROM ops.file_object t
  UNION ALL
  SELECT 'ops','audit_log',count(*),encode(digest(COALESCE(string_agg(to_jsonb(t)::text,'' ORDER BY id::text),''),'sha256'),'hex'),true FROM ops.audit_log t
  UNION ALL
  SELECT 'data','data_record',count(*),encode(digest(COALESCE(string_agg(to_jsonb(t)::text,'' ORDER BY id::text),''),'sha256'),'hex'),true FROM data.data_record t
  UNION ALL
  SELECT 'data','data_value',count(*),encode(digest(COALESCE(string_agg(to_jsonb(t)::text,'' ORDER BY record_id::text,field_code),''),'sha256'),'hex'),true FROM data.data_value t
), optional_names(schema_name,table_name) AS (
  VALUES ('mdm','material'),('mdm','project'),('rnd','experiment'),('rnd','experiment_version')
), optional_xml AS (
  SELECT n.*,
         to_regclass(format('%I.%I',n.schema_name,n.table_name)) IS NOT NULL AS present,
         CASE WHEN to_regclass(format('%I.%I',n.schema_name,n.table_name)) IS NOT NULL THEN
           query_to_xml(format(
             'select count(*) as row_count, encode(digest(coalesce(string_agg(to_jsonb(t)::text, '''' order by id::text), ''''), ''sha256''), ''hex'') as content_hash from %I.%I t',
             n.schema_name,n.table_name),false,true,'')
         END AS inventory_xml
  FROM optional_names n
), optional_inventories AS (
  SELECT schema_name,table_name,
         CASE WHEN present THEN ((xpath('//row/row_count/text()',inventory_xml))[1]::text)::bigint ELSE 0 END AS row_count,
         CASE WHEN present THEN (xpath('//row/content_hash/text()',inventory_xml))[1]::text ELSE NULL END AS content_hash,
         present
  FROM optional_xml
), protected_inventories AS (
  SELECT * FROM inventories
  UNION ALL
  SELECT * FROM optional_inventories
), legacy AS (
  SELECT
    to_regclass('ai.research_experiment_link') IS NOT NULL AS link_table_present,
    CASE WHEN to_regclass('ai.research_experiment_link') IS NULL THEN 0 ELSE
      ((xpath('//row/count/text()', query_to_xml(
        'select count(*) as count from ai.research_experiment_link', false, true, '')))[1]::text)::bigint
    END AS link_count,
    CASE WHEN to_regclass('ai.research_experiment_link') IS NULL THEN 0 ELSE
      ((xpath('//row/count/text()', query_to_xml(
        'select count(*) as count from ai.research_experiment_link l left join ai.research_run r on r.id=l.research_run_id and r.organization_id=l.organization_id left join ai.research_candidate c on c.id=l.research_candidate_id and c.organization_id=l.organization_id where r.id is null or c.id is null',
        false, true, '')))[1]::text)::bigint
    END AS orphan_count
), fingerprint AS (
  SELECT encode(digest(
    COALESCE(string_agg(schema_name || '.' || table_name || ':' || present::text || ':' ||
                        row_count::text || ':' || COALESCE(content_hash,'ABSENT'),
                        '|' ORDER BY schema_name,table_name),'')
      || '|legacyLinks:' || legacy.link_count::text
      || '|orphanLinks:' || legacy.orphan_count::text,
    'sha256'),'hex') AS protected_data_digest
  FROM protected_inventories CROSS JOIN legacy
  GROUP BY legacy.link_count, legacy.orphan_count
)
SELECT jsonb_build_object(
  'generatedAt', now(),
  'protectedDataDigest', fingerprint.protected_data_digest,
  'protectedTables', jsonb_agg(jsonb_build_object('schema',schema_name,'table',table_name,'present',present,'rowCount',row_count,'sha256',content_hash) ORDER BY schema_name,table_name),
  'legacyAiEvidence', jsonb_build_object('linkTablePresent',legacy.link_table_present,'linkCount',legacy.link_count),
  'orphanAiExperimentLinks', legacy.orphan_count
) FROM protected_inventories CROSS JOIN legacy CROSS JOIN fingerprint
GROUP BY legacy.link_table_present, legacy.link_count, legacy.orphan_count, fingerprint.protected_data_digest;
