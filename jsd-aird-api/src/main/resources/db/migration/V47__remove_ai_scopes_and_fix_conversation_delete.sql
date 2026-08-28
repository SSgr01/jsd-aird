ALTER TABLE ai.ai_call_audit
    DROP CONSTRAINT IF EXISTS ai_call_audit_conversation_id_fkey;

ALTER TABLE ai.ai_call_audit
    ADD CONSTRAINT ai_call_audit_conversation_id_fkey
    FOREIGN KEY (conversation_id)
    REFERENCES ai.assistant_conversation(id)
    ON DELETE SET NULL;

ALTER TABLE ai.assistant_conversation
    DROP COLUMN IF EXISTS scope_snapshot_jsonb;

DROP TABLE IF EXISTS ai.ai_scope_resource;
DROP TABLE IF EXISTS ai.ai_scope;
