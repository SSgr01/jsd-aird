CREATE TABLE ai.agent_private_source (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL, owner_id uuid NOT NULL,
    conversation_id uuid NOT NULL, version_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL, request_hash char(64) NOT NULL,
    payload jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(organization_id, owner_id, idempotency_key)
);
CREATE INDEX agent_private_source_owner ON ai.agent_private_source(organization_id,owner_id,conversation_id);
