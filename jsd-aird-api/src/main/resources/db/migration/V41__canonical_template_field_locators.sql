-- Canonical template locator contract.
-- The migration is intentionally idempotent: it adds the canonical
-- label/value objects without inventing a label when the old data did not
-- contain an unambiguous one.
CREATE OR REPLACE FUNCTION tpl.normalize_template_locator(raw jsonb)
RETURNS jsonb
LANGUAGE plpgsql
AS $$
DECLARE
    result jsonb := coalesce(raw, '{}'::jsonb);
    label_range text;
    label_address text;
    value_range text;
    value_address text;
    source text;
BEGIN
    label_range := coalesce(
        nullif(result #>> '{label,range}', ''),
        nullif(result #>> '{label,address}', ''),
        nullif(result->>'labelRange', ''),
        nullif(result->>'labelAddress', '')
    );
    label_address := split_part(replace(coalesce(label_range, ''), '$', ''), ':', 1);

    value_range := coalesce(
        nullif(result #>> '{value,range}', ''),
        nullif(result #>> '{value,address}', ''),
        nullif(result->>'valueRange', ''),
        nullif(result->>'logicalInputRange', ''),
        nullif(result->>'address', ''),
        nullif(result->>'range', '')
    );
    value_address := coalesce(nullif(result #>> '{value,address}', ''), value_range);

    IF label_range IS NULL OR label_range = '' THEN
        result := result - 'label';
    ELSE
        result := jsonb_set(
            result,
            '{label}',
            jsonb_build_object('address', label_address, 'range', label_range),
            true
        );
    END IF;

    IF value_range IS NULL OR value_range = '' THEN
        result := result - 'value';
    ELSE
        result := jsonb_set(
            result,
            '{value}',
            jsonb_build_object('address', value_address, 'range', value_range),
            true
        );
    END IF;

    source := upper(coalesce(result->>'source', ''));
    IF source NOT IN ('RECOGNIZED', 'INFERRED', 'MANUAL', 'UNRESOLVED') THEN
        source := CASE WHEN label_range IS NULL OR label_range = '' THEN 'UNRESOLVED' ELSE 'RECOGNIZED' END;
    END IF;

    result := result
        || jsonb_build_object(
            'locatorVersion', 1,
            'source', source,
            'relation', coalesce(nullif(result->>'relation', ''), CASE WHEN label_range IS NULL OR label_range = '' THEN 'UNRESOLVED' ELSE 'ADJACENT' END)
        );
    RETURN result;
END;
$$;

UPDATE tpl.template_mapping
SET locator_jsonb = tpl.normalize_template_locator(locator_jsonb);

UPDATE tpl.template_version version_row
SET schema_jsonb = jsonb_set(
    version_row.schema_jsonb,
    '{x-jsd-field-model,fields}',
    (
        SELECT coalesce(jsonb_agg(
            field || jsonb_build_object(
                'locator', tpl.normalize_template_locator(field->'locator'),
                'fieldType', CASE
                    WHEN coalesce(field->>'displayRole', '') = 'REGION'
                        OR field->>'kind' IN ('FORM_REGION', 'ROW_TABLE', 'COLUMN_TABLE', 'MATRIX', 'TABLE_REGION')
                        OR field->>'mappingKind' = 'REPEAT_REGION' THEN 'REGION'
                    WHEN field->>'mappingKind' = 'REPEAT_FIELD' OR field ? 'parentFieldId' THEN 'TABLE_COLUMN'
                    ELSE 'FIELD'
                END,
                'displayRole', CASE
                    WHEN coalesce(field->>'displayRole', '') = 'REGION'
                        OR field->>'kind' IN ('FORM_REGION', 'ROW_TABLE', 'COLUMN_TABLE', 'MATRIX', 'TABLE_REGION')
                        OR field->>'mappingKind' = 'REPEAT_REGION' THEN 'REGION'
                    ELSE 'FIELD'
                END,
                'labelStatus', CASE
                    WHEN coalesce(field->>'displayRole', '') = 'REGION'
                        OR field->>'kind' IN ('FORM_REGION', 'ROW_TABLE', 'COLUMN_TABLE', 'MATRIX', 'TABLE_REGION')
                        OR field->>'mappingKind' = 'REPEAT_REGION' THEN 'NOT_APPLICABLE'
                    WHEN coalesce(tpl.normalize_template_locator(field->'locator') #>> '{label,range}', '') = '' THEN 'UNRESOLVED'
                    ELSE 'RESOLVED'
                END
            )
        ), '[]'::jsonb)
        FROM jsonb_array_elements(version_row.schema_jsonb #> '{x-jsd-field-model,fields}') field
    ),
    true
)
WHERE jsonb_typeof(version_row.schema_jsonb #> '{x-jsd-field-model,fields}') = 'array';

DROP FUNCTION tpl.normalize_template_locator(jsonb);
