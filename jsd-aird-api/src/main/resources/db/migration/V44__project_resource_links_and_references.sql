CREATE TABLE core.project_resource_link (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    resource_type varchar(40) NOT NULL CHECK (resource_type IN ('KNOWLEDGE_DOCUMENT', 'DATA_IMPORT_JOB')),
    resource_id uuid NOT NULL,
    project_id uuid NOT NULL,
    stage_id uuid,
    task_id uuid,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (task_id IS NULL OR stage_id IS NOT NULL),
    UNIQUE NULLS NOT DISTINCT (organization_id, resource_type, resource_id, project_id, stage_id, task_id)
);

CREATE INDEX idx_core_project_resource_link_resource
    ON core.project_resource_link (organization_id, resource_type, resource_id);

CREATE INDEX idx_core_project_resource_link_project
    ON core.project_resource_link (organization_id, project_id, resource_type, resource_id);

CREATE TABLE core.project_reference (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    project_id uuid NOT NULL,
    stage_id uuid,
    task_id uuid,
    resource_type varchar(40) NOT NULL CHECK (resource_type IN ('KNOWLEDGE_DOCUMENT', 'DATA_IMPORT_JOB')),
    resource_id uuid NOT NULL,
    file_version_id uuid,
    file_object_id uuid,
    source_module varchar(32) NOT NULL CHECK (source_module IN ('KNOWLEDGE', 'DATA_CENTER')),
    title varchar(500) NOT NULL,
    original_name varchar(500),
    content_type varchar(260),
    size_bytes bigint NOT NULL DEFAULT 0 CHECK (size_bytes >= 0),
    summary text,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REMOVED')),
    added_by uuid NOT NULL REFERENCES iam.app_user(id),
    added_at timestamptz NOT NULL DEFAULT now(),
    removed_by uuid REFERENCES iam.app_user(id),
    removed_at timestamptz,
    CHECK (task_id IS NULL OR stage_id IS NOT NULL),
    UNIQUE NULLS NOT DISTINCT (organization_id, resource_type, resource_id, project_id, stage_id, task_id)
);

CREATE INDEX idx_core_project_reference_project
    ON core.project_reference (organization_id, project_id, status, added_at DESC);

CREATE INDEX idx_core_project_reference_resource
    ON core.project_reference (organization_id, resource_type, resource_id);
