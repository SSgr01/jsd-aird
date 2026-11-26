ALTER TABLE mdm.project_task ADD COLUMN description text;
ALTER TABLE mdm.project_task ADD COLUMN owner_user_id uuid REFERENCES iam.app_user(id);
CREATE INDEX idx_project_task_owner_user_active ON mdm.project_task(owner_user_id) WHERE deleted = false;

ALTER TABLE mdm.project ADD COLUMN owner_user_id uuid;
CREATE INDEX idx_project_owner_user ON mdm.project (organization_id, owner_user_id) WHERE deleted = false;
ALTER TABLE mdm.project ADD COLUMN customer_requirements text;
