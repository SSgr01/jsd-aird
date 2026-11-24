ALTER TABLE iam.department ADD COLUMN IF NOT EXISTS parent_id uuid;
ALTER TABLE iam.department ADD COLUMN IF NOT EXISTS sort_order integer NOT NULL DEFAULT 1000;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'iam.department'::regclass
                   AND conname = 'department_parent_same_org_fk') THEN
        ALTER TABLE iam.department ADD CONSTRAINT department_parent_same_org_fk
            FOREIGN KEY (parent_id, organization_id) REFERENCES iam.department(id, organization_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'iam.department'::regclass
                   AND conname = 'department_not_own_parent_check') THEN
        ALTER TABLE iam.department ADD CONSTRAINT department_not_own_parent_check
            CHECK (parent_id IS DISTINCT FROM id);
    END IF;
END $$;

CREATE OR REPLACE FUNCTION iam.reject_department_cycle() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.parent_id IS NOT NULL AND EXISTS (
        WITH RECURSIVE ancestors AS (
            SELECT id, parent_id FROM iam.department WHERE id = NEW.parent_id
            UNION ALL
            SELECT d.id, d.parent_id FROM iam.department d JOIN ancestors a ON d.id = a.parent_id
        ) SELECT 1 FROM ancestors WHERE id = NEW.id
    ) THEN
        RAISE EXCEPTION 'Department cycle involving %', NEW.id;
    END IF;
    RETURN NEW;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'iam.department'::regclass
                   AND tgname = 'department_prevent_cycle' AND NOT tgisinternal) THEN
        CREATE TRIGGER department_prevent_cycle
            BEFORE INSERT OR UPDATE OF parent_id, organization_id ON iam.department
            FOR EACH ROW EXECUTE FUNCTION iam.reject_department_cycle();
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_department_parent ON iam.department (organization_id, parent_id);
CREATE INDEX IF NOT EXISTS idx_department_parent_order
    ON iam.department (organization_id, parent_id, sort_order, name);
