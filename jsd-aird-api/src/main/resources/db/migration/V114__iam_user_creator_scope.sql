ALTER TABLE iam.app_user
    ADD COLUMN IF NOT EXISTS created_by uuid REFERENCES iam.app_user(id);

WITH verified_creators AS (
    SELECT organization_id, aggregate_id AS user_id, min(actor_id::text)::uuid AS creator_id
    FROM ops.audit_log
    WHERE action = 'IAM_USER_CREATED' AND aggregate_type = 'USER'
    GROUP BY organization_id, aggregate_id
    HAVING count(DISTINCT actor_id) = 1
)
UPDATE iam.app_user u
SET created_by = v.creator_id
FROM verified_creators v
JOIN iam.app_user creator ON creator.id = v.creator_id
    AND creator.organization_id = v.organization_id
WHERE u.id = v.user_id AND u.organization_id = v.organization_id
    AND u.created_by IS NULL;

CREATE INDEX IF NOT EXISTS app_user_creator_scope_idx
    ON iam.app_user (organization_id, created_by);
