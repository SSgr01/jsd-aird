ALTER TABLE mfg.production_upload ADD COLUMN IF NOT EXISTS department_id uuid;

UPDATE mfg.production_upload p SET department_id = u.department_id
FROM iam.app_user u
WHERE p.created_by = u.id AND p.organization_id = u.organization_id;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'production_upload_scope_department_fk') THEN
        ALTER TABLE mfg.production_upload ADD CONSTRAINT production_upload_scope_department_fk
            FOREIGN KEY (department_id, organization_id) REFERENCES iam.department(id, organization_id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_production_upload_scope_creator
    ON mfg.production_upload (organization_id, created_by, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_production_upload_scope_department
    ON mfg.production_upload (organization_id, department_id, created_at DESC);

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'mfg.production_upload'::regclass
                   AND tgname = 'business_creator_scope_insert') THEN
        CREATE TRIGGER business_creator_scope_insert
            BEFORE INSERT ON mfg.production_upload
            FOR EACH ROW EXECUTE FUNCTION iam.initialize_business_creator_scope();
    END IF;
END $$;
