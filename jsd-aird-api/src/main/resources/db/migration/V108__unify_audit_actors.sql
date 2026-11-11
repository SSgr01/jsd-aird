-- Resolve legacy audit names within the owning organization before changing column types.
CREATE FUNCTION pg_temp.audit_actor_id(raw_value text, owner_org uuid) RETURNS uuid
LANGUAGE plpgsql AS $$
DECLARE
    resolved uuid;
    candidates integer;
BEGIN
    IF raw_value IS NULL THEN RETURN NULL; END IF;
    IF owner_org IS NOT NULL THEN
        SELECT u.id INTO resolved FROM iam.app_user u
        WHERE u.organization_id = owner_org
          AND (u.username = raw_value OR u.id::text = raw_value);
        IF resolved IS NOT NULL THEN RETURN resolved; END IF;
        SELECT u.id INTO resolved FROM iam.app_user u
        WHERE u.organization_id = owner_org AND u.username = 'admin';
        IF resolved IS NULL THEN
            RAISE EXCEPTION 'No admin user in organization % for audit value %', owner_org, raw_value;
        END IF;
        RETURN resolved;
    END IF;

    -- Some legacy MDM rows have no organization. A globally unique user is still unambiguous.
    SELECT count(*), min(u.id::text)::uuid INTO candidates, resolved
    FROM iam.app_user u WHERE u.username = raw_value OR u.id::text = raw_value;
    IF candidates = 1 THEN RETURN resolved; END IF;
    SELECT count(*), min(u.id::text)::uuid INTO candidates, resolved
    FROM iam.app_user u WHERE u.username = 'admin';
    IF candidates = 1 THEN RETURN resolved; END IF;
    RAISE EXCEPTION 'Cannot determine organization for legacy audit value %', raw_value;
END $$;

CREATE FUNCTION pg_temp.project_audit_org(project_uuid uuid) RETURNS uuid
LANGUAGE sql STABLE AS $$
    SELECT coalesce(
        (SELECT nullif(to_jsonb(pr)->>'organization_id', '')::uuid
         FROM mdm.project pr WHERE pr.id = project_uuid),
        (SELECT p.organization_id FROM mdm.project pr
         JOIN mdm.business_partner p ON p.id = pr.partner_id WHERE pr.id = project_uuid),
        (SELECT min(e.organization_id::text)::uuid FROM rnd.experiment e
         WHERE e.project_id = project_uuid)
    )
$$;

DROP VIEW IF EXISTS mdm.customer_scope_reconciliation;

DO $$
DECLARE
    item record;
    field_name text;
BEGIN
    FOR item IN SELECT * FROM (VALUES
        ('mdm.business_partner', 't.organization_id'),
        ('mdm.partner_contact', '(SELECT p.organization_id FROM mdm.business_partner p WHERE p.id = t.partner_id)'),
        ('mdm.communication_record', '(SELECT p.organization_id FROM mdm.business_partner p WHERE p.id = t.partner_id)'),
        ('mdm.customer_requirement', 'coalesce((SELECT p.organization_id FROM mdm.business_partner p WHERE p.id = t.partner_id), pg_temp.project_audit_org(t.project_id))'),
        ('mdm.customer_requirement_project', 'coalesce((SELECT p.organization_id FROM mdm.customer_requirement r JOIN mdm.business_partner p ON p.id = r.partner_id WHERE r.id = t.requirement_id), pg_temp.project_audit_org(t.project_id))'),
        ('mdm.material', 'NULL::uuid'),
        ('mdm.project', 'pg_temp.project_audit_org(t.id)'),
        ('mdm.partner_contact_project', '(SELECT p.organization_id FROM mdm.business_partner p JOIN mdm.partner_contact c ON c.partner_id = p.id WHERE c.id = t.contact_id)'),
        ('mdm.project_stage', 'pg_temp.project_audit_org(t.project_id)'),
        ('mdm.project_task', 'pg_temp.project_audit_org(t.project_id)'),
        ('mdm.project_material', 'pg_temp.project_audit_org(t.project_id)'),
        ('mdm.meeting_minutes', 'pg_temp.project_audit_org(t.project_id)'),
        ('mdm.project_document', 'pg_temp.project_audit_org(t.project_id)'),
        ('mdm.project_document_version', '(SELECT pg_temp.project_audit_org(d.project_id) FROM mdm.project_document d WHERE d.id = t.document_id)')
    ) AS v(table_name, owner_org) LOOP
        FOREACH field_name IN ARRAY ARRAY['created_by', 'updated_by'] LOOP
            IF EXISTS (SELECT 1 FROM information_schema.columns c
                       WHERE c.table_schema = split_part(item.table_name, '.', 1)
                         AND c.table_name = split_part(item.table_name, '.', 2)
                         AND c.column_name = field_name AND c.data_type IN ('character varying', 'text')) THEN
                EXECUTE format('UPDATE %s t SET %I = pg_temp.audit_actor_id(t.%I, %s)::text WHERE t.%I IS NOT NULL',
                    item.table_name, field_name, field_name, item.owner_org, field_name);
                -- PostgreSQL does not apply USING to a column's text default.
                EXECUTE format('ALTER TABLE %s ALTER COLUMN %I DROP DEFAULT',
                    item.table_name, field_name);
                EXECUTE format('ALTER TABLE %s ALTER COLUMN %I TYPE uuid USING %I::uuid',
                    item.table_name, field_name, field_name);
                EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES iam.app_user(id)',
                    item.table_name, replace(item.table_name, '.', '_') || '_' || field_name || '_fk', field_name);
            END IF;
        END LOOP;
    END LOOP;

    IF EXISTS (SELECT 1 FROM information_schema.columns c
               WHERE c.table_schema NOT IN ('pg_catalog', 'information_schema')
                 AND c.column_name IN ('created_by', 'updated_by')
                 AND c.data_type IN ('character varying', 'text')) THEN
        RAISE EXCEPTION 'Unconverted text audit actor columns remain';
    END IF;
END $$;

CREATE VIEW mdm.customer_scope_reconciliation AS
SELECT id, partner_code, created_by, created_at
FROM mdm.business_partner
WHERE organization_id IS NULL OR created_by IS NULL OR department_id IS NULL;
