CREATE TABLE iam.department (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    name varchar(160) NOT NULL,
    UNIQUE (organization_id, name),
    UNIQUE (id, organization_id)
);

INSERT INTO iam.department (organization_id, name)
SELECT DISTINCT organization_id, trim(department_name)
FROM iam.app_user
WHERE department_name IS NOT NULL AND trim(department_name) <> '';

ALTER TABLE iam.app_user ADD COLUMN department_id uuid REFERENCES iam.department(id);
UPDATE iam.app_user u SET department_id = d.id
FROM iam.department d
WHERE d.organization_id = u.organization_id AND d.name = trim(u.department_name);

CREATE FUNCTION iam.sync_user_department() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.department_name IS NULL OR trim(NEW.department_name) = '' THEN
        NEW.department_id := NULL;
    ELSE
        INSERT INTO iam.department (organization_id, name)
        VALUES (NEW.organization_id, trim(NEW.department_name))
        ON CONFLICT (organization_id, name) DO NOTHING;
        SELECT id INTO NEW.department_id FROM iam.department
        WHERE organization_id = NEW.organization_id AND name = trim(NEW.department_name);
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER app_user_department_sync BEFORE INSERT OR UPDATE OF department_name, organization_id
ON iam.app_user FOR EACH ROW EXECUTE FUNCTION iam.sync_user_department();

ALTER TABLE mdm.business_partner
    ADD COLUMN organization_id uuid REFERENCES iam.organization(id),
    ADD COLUMN department_id uuid REFERENCES iam.department(id),
    ADD CONSTRAINT customer_department_organization_fk
        FOREIGN KEY (department_id, organization_id) REFERENCES iam.department(id, organization_id);

-- Only a unique creation event is sufficient evidence for historical ownership.
WITH creation AS (
    SELECT aggregate_id, min(organization_id::text)::uuid AS organization_id,
           min(actor_id::text)::uuid AS actor_id
    FROM ops.audit_log
    WHERE aggregate_type = 'BUSINESS_PARTNER' AND action = 'CUSTOMER_CREATED'
    GROUP BY aggregate_id
    HAVING count(*) = 1
)
UPDATE mdm.business_partner p
SET organization_id = c.organization_id,
    created_by = c.actor_id::text,
    department_id = u.department_id
FROM creation c JOIN iam.app_user u ON u.id = c.actor_id AND u.organization_id = c.organization_id
WHERE p.id = c.aggregate_id;

CREATE INDEX idx_business_partner_scope ON mdm.business_partner (organization_id, department_id, created_by)
WHERE deleted = false;

CREATE VIEW mdm.customer_scope_reconciliation AS
SELECT id, partner_code, created_by, created_at
FROM mdm.business_partner
WHERE organization_id IS NULL OR created_by IS NULL OR created_by = 'system' OR department_id IS NULL;

UPDATE iam.role_permission_binding SET scope_type = 'ALL' WHERE scope_type = 'ORG';
UPDATE iam.user_permission_override SET scope_type = 'ALL' WHERE scope_type = 'ORG';
ALTER TABLE iam.role_permission_binding DROP CONSTRAINT IF EXISTS role_binding_scope_check;
ALTER TABLE iam.role_permission_binding ADD CONSTRAINT role_binding_scope_check
    CHECK (scope_type IN ('ALL', 'SELF', 'ASSIGNED', 'PROJECT', 'CATEGORY', 'SELECTED', 'DEPARTMENT'));
ALTER TABLE iam.user_permission_override DROP CONSTRAINT IF EXISTS user_override_scope_check;
ALTER TABLE iam.user_permission_override ADD CONSTRAINT user_override_scope_check
    CHECK (scope_type IN ('ALL', 'SELF', 'ASSIGNED', 'PROJECT', 'CATEGORY', 'SELECTED', 'DEPARTMENT'));

DO $$ BEGIN
    IF to_regclass('iam.permission_level_binding') IS NOT NULL THEN
        UPDATE iam.permission_level_binding SET scope_type = 'ALL' WHERE scope_type = 'ORG';
    END IF;
    IF to_regclass('iam.user_scope_override') IS NOT NULL THEN
        UPDATE iam.user_scope_override SET scope_type = 'ALL' WHERE scope_type = 'ORG';
    END IF;
END $$;
