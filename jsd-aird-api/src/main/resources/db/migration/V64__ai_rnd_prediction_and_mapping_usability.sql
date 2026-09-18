-- R10 usability correction: a published X must have a stable business-data binding.
-- Existing rows are not rewritten.  An incompatible foundation is reported
-- before installing the forward guard so operators can repair the explicit X
-- binding rather than having the migration silently demote or guess it.
DO $$
DECLARE
    invalid_fields text;
BEGIN
    SELECT string_agg(f.field_code || ' (' || v.id::text || ')', ', ' ORDER BY f.field_code)
      INTO invalid_fields
      FROM ai.input_field_version v
      JOIN ai.input_field f ON f.id = v.input_field_id AND f.organization_id = v.organization_id
                           AND f.current_version_id = v.id
     WHERE v.status = 'PUBLISHED'
       AND (
          (v.value_type <> 'COMPOSITION' AND (
             v.standard_field_dictionary_id IS NULL OR NOT EXISTS (
                SELECT 1 FROM tpl.standard_field_dictionary d
                 WHERE d.id = v.standard_field_dictionary_id AND d.status = 'ACTIVE'
             )
          ))
          OR
          (v.value_type = 'COMPOSITION' AND (
             jsonb_typeof(v.definition_jsonb -> 'standardFieldCodes') <> 'array'
             OR NOT (v.definition_jsonb -> 'standardFieldCodes' ? 'FORMULA.ITEM.MATERIAL_CODE')
             OR NOT (v.definition_jsonb -> 'standardFieldCodes' ? 'FORMULA.ITEM.RATIO')
          ))
       );
    IF invalid_fields IS NOT NULL THEN
        RAISE EXCEPTION 'V64 blocked: published X has no valid data-field binding: %', invalid_fields
            USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION ai.guard_published_input_field_binding()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    missing_code text;
BEGIN
    IF NEW.status <> 'PUBLISHED' THEN
        RETURN NEW;
    END IF;

    IF NEW.value_type = 'COMPOSITION' THEN
        IF jsonb_typeof(NEW.definition_jsonb -> 'standardFieldCodes') <> 'array'
           OR NOT (NEW.definition_jsonb -> 'standardFieldCodes' ? 'FORMULA.ITEM.MATERIAL_CODE')
           OR NOT (NEW.definition_jsonb -> 'standardFieldCodes' ? 'FORMULA.ITEM.RATIO') THEN
            RAISE EXCEPTION 'published composition X must bind material code and ratio fields'
                USING ERRCODE = '23514', CONSTRAINT = 'published_composition_data_fields_required';
        END IF;

        SELECT code INTO missing_code
        FROM unnest(ARRAY['FORMULA.ITEM.MATERIAL_CODE','FORMULA.ITEM.RATIO']) AS code
        WHERE NOT EXISTS (
            SELECT 1 FROM tpl.standard_field_dictionary d
            WHERE d.dictionary_code = code AND d.status = 'ACTIVE'
        )
        LIMIT 1;
        IF missing_code IS NOT NULL THEN
            RAISE EXCEPTION 'published composition X references inactive data field: %', missing_code
                USING ERRCODE = '23503', CONSTRAINT = 'published_composition_data_field_active';
        END IF;
    ELSE
        IF NEW.standard_field_dictionary_id IS NULL OR NOT EXISTS (
            SELECT 1 FROM tpl.standard_field_dictionary d
            WHERE d.id = NEW.standard_field_dictionary_id AND d.status = 'ACTIVE'
        ) THEN
            RAISE EXCEPTION 'published X must bind an active data field'
                USING ERRCODE = '23503', CONSTRAINT = 'published_input_data_field_required';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_input_field_version_published_binding ON ai.input_field_version;
CREATE TRIGGER trg_input_field_version_published_binding
BEFORE INSERT OR UPDATE OF status, value_type, standard_field_dictionary_id, definition_jsonb
ON ai.input_field_version
FOR EACH ROW EXECUTE FUNCTION ai.guard_published_input_field_binding();

COMMENT ON FUNCTION ai.guard_published_input_field_binding() IS
    'Prevents publishing ordinary X without an active data field and composition X without material-code/ratio bindings.';
