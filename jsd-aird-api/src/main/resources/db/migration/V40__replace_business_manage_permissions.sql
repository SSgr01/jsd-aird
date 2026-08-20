-- Business authorization is an atomic-action contract.  System-management
-- permissions remain unchanged in this migration by design.
DELETE FROM iam.user_permission_override
WHERE permission_code IN (
    'customer.manage', 'project.manage', 'experiment.manage', 'template.manage',
    'template.category.manage', 'production.manage', 'knowledge.manage',
    'data.manage', 'spectrum.manage'
);

DELETE FROM iam.role_permission_binding
WHERE permission_code IN (
    'customer.manage', 'project.manage', 'experiment.manage', 'template.manage',
    'template.category.manage', 'production.manage', 'knowledge.manage',
    'data.manage', 'spectrum.manage'
);

DELETE FROM iam.permission_definition
WHERE code IN (
    'customer.manage', 'project.manage', 'experiment.manage', 'template.manage',
    'template.category.manage', 'production.manage', 'knowledge.manage',
    'data.manage', 'spectrum.manage'
);

-- Force the next authenticated request to reload the new role policy and
-- personal overrides instead of carrying the old permission summary.
UPDATE iam.role SET policy_version = policy_version + 1;
UPDATE iam.app_user SET auth_version = auth_version + 1;
