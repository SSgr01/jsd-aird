CREATE TABLE mdm.project_communication_record (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL REFERENCES mdm.project(id) ON DELETE CASCADE,
    occurred_at timestamptz NOT NULL,
    communication_method varchar(100) NOT NULL,
    communicator varchar(100) NOT NULL,
    content text NOT NULL,
    custom_fields jsonb NOT NULL DEFAULT '{}'::jsonb,
    version bigint NOT NULL DEFAULT 0,
    deleted boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_by uuid NOT NULL,
    CONSTRAINT project_communication_custom_fields_object
        CHECK (jsonb_typeof(custom_fields) = 'object')
);
CREATE INDEX idx_project_communication_record_project
    ON mdm.project_communication_record (organization_id, project_id, occurred_at DESC)
    WHERE deleted = false;
