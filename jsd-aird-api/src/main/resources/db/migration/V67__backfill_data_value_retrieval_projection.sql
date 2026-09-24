-- The import commit path historically persisted data_record and source_anchor
-- but not data_value. Backfill the value index from committed JSON snapshots.
INSERT INTO data.data_value(
    id, organization_id, record_id, field_code, binding_id, value_path, data_path, value_jsonb,
    value_text, normalized_unit, source_anchor_id, training_eligible)
SELECT gen_random_uuid(), r.organization_id, r.id,
       coalesce(nullif(entry.value->>'fieldCode', ''), entry.key),
       coalesce(nullif(entry.value->>'bindingId', ''),
                coalesce(nullif(entry.value->>'fieldCode', ''), entry.key)),
       coalesce(nullif(entry.value->>'valuePath', ''),
                nullif(entry.value->>'dataPath', ''),
                '/' || coalesce(nullif(entry.value->>'fieldCode', ''), entry.key)),
       coalesce(nullif(entry.value->>'valuePath', ''),
                nullif(entry.value->>'dataPath', ''),
                '/' || coalesce(nullif(entry.value->>'fieldCode', ''), entry.key)),
       coalesce(entry.value->'normalizedValue', entry.value->'rawValue', entry.value),
       coalesce(nullif(entry.value->>'normalizedValue', ''),
                nullif(entry.value->>'displayValue', ''),
                nullif(entry.value->>'rawValue', '')),
       nullif(entry.value->>'normalizedUnit', ''),
       anchor.id,
       coalesce((entry.value->>'trainingEligible')::boolean, true)
FROM data.data_record r
JOIN data.import_job j ON j.id = r.import_job_id
CROSS JOIN LATERAL jsonb_each(
    CASE WHEN jsonb_typeof(r.effective_data_jsonb) = 'object'
         THEN r.effective_data_jsonb ELSE '{}'::jsonb END) entry
LEFT JOIN LATERAL (
    SELECT a.id
    FROM data.source_anchor a
    WHERE a.record_id = r.id
      AND a.field_code = coalesce(nullif(entry.value->>'fieldCode', ''), entry.key)
      AND a.binding_id = coalesce(nullif(entry.value->>'bindingId', ''),
                                   coalesce(nullif(entry.value->>'fieldCode', ''), entry.key))
      AND a.value_path = coalesce(nullif(entry.value->>'valuePath', ''),
                                  nullif(entry.value->>'dataPath', ''),
                                  '/' || coalesce(nullif(entry.value->>'fieldCode', ''), entry.key))
    ORDER BY a.created_at, a.id
    LIMIT 1
) anchor ON true
WHERE j.status = 'COMPLETED'
  AND nullif(btrim(coalesce(entry.value->>'normalizedValue',
                            entry.value->>'displayValue',
                            entry.value->>'rawValue', '')), '') IS NOT NULL
ON CONFLICT(record_id, binding_id, value_path)
    WHERE binding_id IS NOT NULL AND value_path IS NOT NULL DO UPDATE SET
    data_path = excluded.data_path,
    value_jsonb = excluded.value_jsonb,
    value_text = excluded.value_text,
    normalized_unit = excluded.normalized_unit,
    source_anchor_id = excluded.source_anchor_id,
    training_eligible = excluded.training_eligible;
