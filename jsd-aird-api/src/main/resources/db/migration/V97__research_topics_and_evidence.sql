CREATE SCHEMA IF NOT EXISTS ai;
CREATE TABLE ai.research_record (
    kind varchar(24) NOT NULL CHECK(kind IN ('topic','annotation','card','report','bibliography','artifact')),
    id uuid NOT NULL, organization_id uuid NOT NULL, owner_id uuid NOT NULL,
    revision bigint NOT NULL DEFAULT 1, idempotency_key varchar(128), payload jsonb NOT NULL, request_payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(kind,id), UNIQUE(organization_id,owner_id,kind,idempotency_key)
);
CREATE INDEX research_record_owner ON ai.research_record(organization_id,owner_id,kind,updated_at DESC);
CREATE INDEX research_record_topic ON ai.research_record((payload->>'topic_id'));
