-- Root business entities own their creator's organization and initial department.
CREATE OR REPLACE FUNCTION iam.initialize_business_creator_scope() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    creator_org uuid;
    creator_department uuid;
BEGIN
    SELECT organization_id, department_id INTO creator_org, creator_department
    FROM iam.app_user WHERE id = NEW.created_by;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Unknown creator % for %.%', NEW.created_by, TG_TABLE_SCHEMA, TG_TABLE_NAME;
    END IF;
    IF NEW.organization_id IS NOT NULL AND NEW.organization_id <> creator_org THEN
        RAISE EXCEPTION 'Creator % belongs to organization %, not %',
            NEW.created_by, creator_org, NEW.organization_id;
    END IF;
    NEW.organization_id := creator_org;
    NEW.department_id := creator_department;
    RETURN NEW;
END $$;

DO $$
DECLARE
    table_name text;
    schema_name text;
    relation_name text;
    has_department boolean;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'mdm.business_partner', 'mdm.project', 'mdm.material',
        'mdm.customer_requirement', 'mdm.communication_record',
        'rnd.experiment', 'rnd.research_test_record', 'rnd.quotation_record',
        'mfg.production_order', 'mfg.inventory_product',
        'quality.data_record', 'kb.document', 'tpl.template',
        'spc.chart_asset', 'ai.prediction_target', 'ai.assistant_conversation'
    ] LOOP
        schema_name := split_part(table_name, '.', 1);
        relation_name := split_part(table_name, '.', 2);
        IF to_regclass(table_name) IS NULL THEN
            RAISE EXCEPTION 'Missing business root table %', table_name;
        END IF;

        SELECT EXISTS (SELECT 1 FROM information_schema.columns col
                       WHERE col.table_schema = schema_name AND col.table_name = relation_name
                         AND col.column_name = 'department_id') INTO has_department;

        EXECUTE format('ALTER TABLE %s ADD COLUMN IF NOT EXISTS organization_id uuid', table_name);
        EXECUTE format('ALTER TABLE %s ADD COLUMN IF NOT EXISTS department_id uuid', table_name);

        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint c
            WHERE c.conrelid = to_regclass(table_name) AND c.contype = 'f'
              AND c.confrelid = 'iam.organization'::regclass
              AND c.conkey = ARRAY[(SELECT attnum FROM pg_attribute
                                    WHERE attrelid = to_regclass(table_name)
                                      AND attname = 'organization_id')]::smallint[]
        ) THEN
            EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I FOREIGN KEY (organization_id) REFERENCES iam.organization(id)',
                table_name, relation_name || '_creator_scope_org_fk');
        END IF;

        IF NOT has_department THEN
            EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I FOREIGN KEY (department_id, organization_id) REFERENCES iam.department(id, organization_id)',
                table_name, relation_name || '_creator_scope_department_fk');
        END IF;

        EXECUTE format('UPDATE %s t SET organization_id = coalesce(t.organization_id, u.organization_id), department_id = coalesce(t.department_id, u.department_id) FROM iam.app_user u WHERE t.created_by = u.id AND (t.organization_id IS NULL OR t.department_id IS NULL)',
            table_name);

        IF EXISTS (SELECT 1 FROM information_schema.columns col
                   WHERE col.table_schema = schema_name AND col.table_name = relation_name
                     AND col.column_name = 'created_by' AND col.is_nullable = 'NO') THEN
            EXECUTE format('ALTER TABLE %s ALTER COLUMN organization_id SET NOT NULL', table_name);
        END IF;

        EXECUTE format('DROP TRIGGER IF EXISTS business_creator_scope_insert ON %s', table_name);
        EXECUTE format('CREATE TRIGGER business_creator_scope_insert BEFORE INSERT ON %s FOR EACH ROW EXECUTE FUNCTION iam.initialize_business_creator_scope()',
            table_name);
    END LOOP;
END $$;
