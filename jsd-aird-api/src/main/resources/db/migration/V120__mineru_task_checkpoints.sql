-- Keep remote tasks across Worker retries/restarts, separately for every partition.
CREATE TABLE kb.mineru_task_checkpoint (
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    document_version_id uuid NOT NULL REFERENCES kb.document_version(id) ON DELETE CASCADE,
    task_key varchar(64) NOT NULL,
    batch_id varchar(260) NOT NULL,
    upload_url text,
    uploaded boolean NOT NULL DEFAULT false,
    remote_state varchar(40) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, document_version_id, task_key)
);
