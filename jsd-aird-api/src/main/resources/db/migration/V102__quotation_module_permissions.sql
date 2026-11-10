-- 报价模块权限与数据（合并自 V99~V105 的 DML 部分）：
-- 权限定义、既有组织角色授权、会话策略版本刷新。

-- 报价记录基础权限
INSERT INTO iam.permission_definition (code, module, name, risk, default_scope)
VALUES
    ('quotation.view', 'quotation', '查看报价记录', 'LOW', 'ALL'),
    ('quotation.create', 'quotation', '上传报价记录', 'MEDIUM', 'SELF'),
    ('quotation.delete', 'quotation', '删除报价记录', 'HIGH', 'SELF')
ON CONFLICT (code) DO UPDATE SET
    module = EXCLUDED.module,
    name = EXCLUDED.name,
    risk = EXCLUDED.risk,
    default_scope = EXCLUDED.default_scope,
    enabled = TRUE,
    updated_at = now(),
    definition_version = iam.permission_definition.definition_version + 1;

-- 既有组织的角色授权：管理员与研发角色默认可使用新的报价记录模块；
-- 删除权限只授予管理员与研发负责人，与 IamBootstrapService 的角色策略一致。
INSERT INTO iam.role_permission_binding
    (id, organization_id, role_id, permission_code, effect, scope_type, target_ids)
SELECT gen_random_uuid(), r.organization_id, r.id, p.code, 'ALLOW', p.default_scope, '[]'::jsonb
FROM iam.role r
JOIN iam.permission_definition p ON p.code IN ('quotation.view', 'quotation.create', 'quotation.delete')
WHERE r.code IN ('SYSTEM_ADMIN', 'RND_MANAGER', 'RND_ENGINEER')
  AND (p.code <> 'quotation.delete' OR r.code IN ('SYSTEM_ADMIN', 'RND_MANAGER'))
ON CONFLICT (organization_id, role_id, permission_code) DO NOTHING;

-- 编辑权限
INSERT INTO iam.permission_definition (code, module, name, risk, default_scope)
VALUES ('quotation.edit', 'quotation', '编辑报价记录', 'MEDIUM', 'SELF')
ON CONFLICT (code) DO NOTHING;

INSERT INTO iam.role_permission_binding
    (id, organization_id, role_id, permission_code, effect, scope_type, target_ids)
SELECT gen_random_uuid(), r.organization_id, r.id, 'quotation.edit', 'ALLOW',
       CASE WHEN r.code = 'SYSTEM_ADMIN' THEN 'ALL' ELSE 'SELF' END, '[]'::jsonb
FROM iam.role r
WHERE r.code IN ('SYSTEM_ADMIN', 'RND_MANAGER', 'RND_ENGINEER')
ON CONFLICT (organization_id, role_id, permission_code) DO NOTHING;

-- 审核权限
INSERT INTO iam.permission_definition (code, module, name, risk, default_scope)
VALUES
    ('quotation.submit', 'quotation', '提交报价审核', 'MEDIUM', 'SELF'),
    ('quotation.review', 'quotation', '审核报价记录', 'HIGH', 'ALL')
ON CONFLICT (code) DO UPDATE SET
    module = EXCLUDED.module,
    name = EXCLUDED.name,
    risk = EXCLUDED.risk,
    default_scope = EXCLUDED.default_scope,
    enabled = TRUE,
    updated_at = now(),
    definition_version = iam.permission_definition.definition_version + 1;

INSERT INTO iam.role_permission_binding
    (id, organization_id, role_id, permission_code, effect, scope_type, target_ids)
SELECT gen_random_uuid(), r.organization_id, r.id, p.code, 'ALLOW', p.default_scope, '[]'::jsonb
FROM iam.role r
JOIN iam.permission_definition p ON p.code IN ('quotation.submit', 'quotation.review')
WHERE (p.code = 'quotation.submit' AND r.code IN ('SYSTEM_ADMIN', 'RND_MANAGER', 'RND_ENGINEER'))
   OR (p.code = 'quotation.review' AND r.code IN ('SYSTEM_ADMIN', 'RND_MANAGER'))
ON CONFLICT (organization_id, role_id, permission_code) DO NOTHING;

-- 让已登录会话重新加载角色策略与权限摘要。
UPDATE iam.role SET policy_version = policy_version + 1;
UPDATE iam.app_user SET auth_version = auth_version + 1;
