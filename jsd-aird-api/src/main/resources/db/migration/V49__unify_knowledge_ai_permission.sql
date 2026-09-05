-- Use the general AI permission as the single permission for authorizing
-- selected knowledge documents for AI usage.
INSERT INTO iam.permission_definition (code, module, name, risk, default_scope)
VALUES ('ai.external', 'ai', '允许知识文档 AI 使用', 'CRITICAL', 'SELECTED')
ON CONFLICT (code) DO UPDATE SET module = EXCLUDED.module,
    name = EXCLUDED.name, risk = EXCLUDED.risk, default_scope = EXCLUDED.default_scope,
    enabled = TRUE, updated_at = now(),
    definition_version = iam.permission_definition.definition_version + 1;

DELETE FROM iam.role_permission_binding legacy
WHERE legacy.permission_code = 'knowledge.ai.external'
  AND EXISTS (
      SELECT 1 FROM iam.role_permission_binding current
      WHERE current.organization_id = legacy.organization_id
        AND current.role_id = legacy.role_id
        AND current.permission_code = 'ai.external'
  );

UPDATE iam.role_permission_binding
SET permission_code = 'ai.external', updated_at = now()
WHERE permission_code = 'knowledge.ai.external';

DELETE FROM iam.user_permission_override legacy
WHERE legacy.permission_code = 'knowledge.ai.external'
  AND EXISTS (
      SELECT 1 FROM iam.user_permission_override current
      WHERE current.organization_id = legacy.organization_id
        AND current.user_id = legacy.user_id
        AND current.permission_code = 'ai.external'
  );

UPDATE iam.user_permission_override
SET permission_code = 'ai.external', updated_at = now()
WHERE permission_code = 'knowledge.ai.external';

DELETE FROM iam.permission_scope_target legacy
WHERE legacy.permission_code = 'knowledge.ai.external'
  AND EXISTS (
      SELECT 1 FROM iam.permission_scope_target current
      WHERE current.organization_id = legacy.organization_id
        AND current.owner_type = legacy.owner_type
        AND current.owner_id = legacy.owner_id
        AND current.permission_code = 'ai.external'
        AND current.target_type = legacy.target_type
        AND current.target_id = legacy.target_id
  );

UPDATE iam.permission_scope_target
SET permission_code = 'ai.external'
WHERE permission_code = 'knowledge.ai.external';

UPDATE iam.permission_definition
SET enabled = FALSE, updated_at = now(), definition_version = definition_version + 1
WHERE code = 'knowledge.ai.external';

UPDATE iam.role SET policy_version = policy_version + 1;
UPDATE iam.app_user SET auth_version = auth_version + 1;
