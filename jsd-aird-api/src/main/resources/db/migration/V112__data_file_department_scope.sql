ALTER TABLE data.import_job ADD COLUMN department_id uuid;
ALTER TABLE ops.file_object ADD COLUMN department_id uuid;

ALTER TABLE data.import_job ADD CONSTRAINT import_job_scope_department_fk
    FOREIGN KEY (department_id, organization_id) REFERENCES iam.department(id, organization_id);
ALTER TABLE ops.file_object ADD CONSTRAINT file_object_scope_department_fk
    FOREIGN KEY (department_id, organization_id) REFERENCES iam.department(id, organization_id);

CREATE TRIGGER business_creator_scope_insert BEFORE INSERT ON data.import_job
    FOR EACH ROW EXECUTE FUNCTION iam.initialize_business_creator_scope();
CREATE TRIGGER business_creator_scope_insert BEFORE INSERT ON ops.file_object
    FOR EACH ROW EXECUTE FUNCTION iam.initialize_business_creator_scope();

CREATE INDEX idx_import_job_scope_creator ON data.import_job (organization_id, created_by, created_at DESC);
CREATE INDEX idx_import_job_scope_department ON data.import_job (organization_id, department_id, created_at DESC);
CREATE INDEX idx_file_object_scope_creator ON ops.file_object (organization_id, created_by, created_at DESC);
CREATE INDEX idx_file_object_scope_department ON ops.file_object (organization_id, department_id, created_at DESC);
