-- Align business records with their creator's current department.
CREATE OR REPLACE FUNCTION iam.initialize_upload_task_creator_scope() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    creator_org uuid;
    creator_department uuid;
BEGIN
    SELECT organization_id, department_id INTO creator_org, creator_department
    FROM iam.app_user WHERE id = NEW.actor_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Unknown actor % for %.%', NEW.actor_id, TG_TABLE_SCHEMA, TG_TABLE_NAME;
    END IF;
    IF NEW.organization_id IS NOT NULL AND NEW.organization_id <> creator_org THEN
        RAISE EXCEPTION 'Actor % belongs to organization %, not %',
            NEW.actor_id, creator_org, NEW.organization_id;
    END IF;
    NEW.organization_id := creator_org;
    NEW.department_id := creator_department;
    RETURN NEW;
END $$;

DO $$
DECLARE
    target record;
    relation regclass;
    missing_creator bigint;
    organization_mismatch bigint;
    changed_count bigint;
    department_attnum smallint;
    organization_attnum smallint;
BEGIN
    -- Validate every table before changing any existing business record.
    FOR target IN
        SELECT * FROM (VALUES
            ('tpl', 'template_import_job', 'created_by'),
            ('tpl', 'template', 'created_by'),
            ('rnd', 'experiment_import_job', 'created_by'),
            ('rnd', 'experiment', 'created_by'),
            ('rnd', 'research_test_upload', 'created_by'),
            ('rnd', 'research_test_record', 'created_by'),
            ('quality', 'upload_file', 'created_by'),
            ('quality', 'data_record', 'created_by'),
            ('spc', 'chart_upload_attempt', 'created_by'),
            ('spc', 'chart_asset', 'created_by'),
            ('mfg', 'production_upload', 'created_by'),
            ('mfg', 'production_order', 'created_by'),
            ('kb', 'upload_task', 'actor_id'),
            ('kb', 'document', 'created_by'),
            ('data', 'import_job', 'created_by'),
            ('mfg', 'inventory_product', 'created_by'),
            ('mdm', 'project', 'created_by'),
            ('mdm', 'business_partner', 'created_by')
        ) AS t(schema_name, table_name, creator_column)
    LOOP
        relation := to_regclass(format('%I.%I', target.schema_name, target.table_name));
        IF relation IS NULL THEN
            RAISE EXCEPTION 'Required table %.% does not exist', target.schema_name, target.table_name;
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM pg_attribute
            WHERE attrelid = relation AND attname = target.creator_column
              AND atttypid = 'uuid'::regtype AND NOT attisdropped
        ) OR NOT EXISTS (
            SELECT 1 FROM pg_attribute
            WHERE attrelid = relation AND attname = 'organization_id'
              AND atttypid = 'uuid'::regtype AND NOT attisdropped
        ) THEN
            RAISE EXCEPTION '%.% requires uuid creator and organization columns',
                target.schema_name, target.table_name;
        END IF;

        EXECUTE format(
            'SELECT count(*) FILTER (WHERE u.id IS NULL), '
            || 'count(*) FILTER (WHERE u.id IS NOT NULL AND t.organization_id IS DISTINCT FROM u.organization_id) '
            || 'FROM %s t LEFT JOIN iam.app_user u ON u.id = t.%I',
            relation, target.creator_column)
        INTO missing_creator, organization_mismatch;
        RAISE NOTICE '%.%: missing_creator=%, organization_mismatch=%',
            target.schema_name, target.table_name, missing_creator, organization_mismatch;
        IF missing_creator > 0 OR organization_mismatch > 0 THEN
            RAISE EXCEPTION 'Invalid creator or organization in %.%',
                target.schema_name, target.table_name;
        END IF;
    END LOOP;

    FOR target IN
        SELECT * FROM (VALUES
            ('tpl', 'template_import_job', 'created_by'),
            ('tpl', 'template', 'created_by'),
            ('rnd', 'experiment_import_job', 'created_by'),
            ('rnd', 'experiment', 'created_by'),
            ('rnd', 'research_test_upload', 'created_by'),
            ('rnd', 'research_test_record', 'created_by'),
            ('quality', 'upload_file', 'created_by'),
            ('quality', 'data_record', 'created_by'),
            ('spc', 'chart_upload_attempt', 'created_by'),
            ('spc', 'chart_asset', 'created_by'),
            ('mfg', 'production_upload', 'created_by'),
            ('mfg', 'production_order', 'created_by'),
            ('kb', 'upload_task', 'actor_id'),
            ('kb', 'document', 'created_by'),
            ('data', 'import_job', 'created_by'),
            ('mfg', 'inventory_product', 'created_by'),
            ('mdm', 'project', 'created_by'),
            ('mdm', 'business_partner', 'created_by')
        ) AS t(schema_name, table_name, creator_column)
    LOOP
        relation := to_regclass(format('%I.%I', target.schema_name, target.table_name));
        SELECT attnum INTO department_attnum FROM pg_attribute
        WHERE attrelid = relation AND attname = 'department_id' AND NOT attisdropped;
        IF department_attnum IS NULL THEN
            EXECUTE format('ALTER TABLE %s ADD COLUMN department_id uuid', relation);
            SELECT attnum INTO department_attnum FROM pg_attribute
            WHERE attrelid = relation AND attname = 'department_id' AND NOT attisdropped;
        ELSIF (SELECT atttypid FROM pg_attribute
               WHERE attrelid = relation AND attnum = department_attnum) <> 'uuid'::regtype THEN
            RAISE EXCEPTION '%.%.department_id must be uuid', target.schema_name, target.table_name;
        END IF;
        SELECT attnum INTO organization_attnum FROM pg_attribute
        WHERE attrelid = relation AND attname = 'organization_id' AND NOT attisdropped;

        EXECUTE format(
            'UPDATE %s t SET department_id = u.department_id FROM iam.app_user u '
            || 'WHERE t.%I = u.id AND t.department_id IS DISTINCT FROM u.department_id',
            relation, target.creator_column);
        GET DIAGNOSTICS changed_count = ROW_COUNT;

        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint c
            WHERE c.conrelid = relation AND c.contype = 'f'
              AND c.confrelid = 'iam.department'::regclass
              AND c.conkey = ARRAY[department_attnum, organization_attnum]::smallint[]
        ) THEN
            EXECUTE format(
                'ALTER TABLE %s ADD CONSTRAINT %I FOREIGN KEY (department_id, organization_id) '
                || 'REFERENCES iam.department(id, organization_id)',
                relation, target.table_name || '_department_scope_fk');
        END IF;

        IF NOT EXISTS (
            SELECT 1 FROM pg_trigger
            WHERE tgrelid = relation AND tgname = 'business_creator_scope_insert' AND NOT tgisinternal
        ) THEN
            EXECUTE format(
                'CREATE TRIGGER business_creator_scope_insert BEFORE INSERT ON %s '
                || 'FOR EACH ROW EXECUTE FUNCTION %s()', relation,
                CASE WHEN target.creator_column = 'actor_id'
                     THEN 'iam.initialize_upload_task_creator_scope'
                     ELSE 'iam.initialize_business_creator_scope' END);
        END IF;
        RAISE NOTICE '%.%: department_changed=%',
            target.schema_name, target.table_name, changed_count;
    END LOOP;
END $$;
