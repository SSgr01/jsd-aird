-- Keep the legacy ORG code in stored grants; authorization resolves it as ALL.
ALTER TABLE iam.role_permission_binding DROP CONSTRAINT IF EXISTS role_binding_scope_check;
ALTER TABLE iam.role_permission_binding ADD CONSTRAINT role_binding_scope_check
    CHECK (scope_type IN ('ALL', 'ORG', 'SELF', 'ASSIGNED', 'PROJECT', 'CATEGORY', 'SELECTED', 'DEPARTMENT'));

ALTER TABLE iam.user_permission_override DROP CONSTRAINT IF EXISTS user_override_scope_check;
ALTER TABLE iam.user_permission_override ADD CONSTRAINT user_override_scope_check
    CHECK (scope_type IN ('ALL', 'ORG', 'SELF', 'ASSIGNED', 'PROJECT', 'CATEGORY', 'SELECTED', 'DEPARTMENT'));

ALTER TABLE iam.permission_level_binding DROP CONSTRAINT IF EXISTS permission_level_scope_check;
ALTER TABLE iam.permission_level_binding ADD CONSTRAINT permission_level_scope_check
    CHECK (scope_type IN ('ALL', 'ORG', 'SELF', 'ASSIGNED', 'PROJECT', 'CATEGORY', 'SELECTED',
                          'PROJECT_GROUP', 'CREATOR', 'OWNER', 'MANAGER', 'PARTICIPANT', 'DEPARTMENT'));

ALTER TABLE iam.user_scope_override DROP CONSTRAINT IF EXISTS user_scope_override_scope_check;
ALTER TABLE iam.user_scope_override ADD CONSTRAINT user_scope_override_scope_check
    CHECK (scope_type IN ('ALL', 'ORG', 'SELF', 'ASSIGNED', 'PROJECT', 'CATEGORY', 'SELECTED',
                          'PROJECT_GROUP', 'CREATOR', 'OWNER', 'MANAGER', 'PARTICIPANT', 'DEPARTMENT'));
