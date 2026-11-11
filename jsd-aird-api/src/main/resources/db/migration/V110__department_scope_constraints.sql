-- Extend the V2 permission tables to accept the customer department scope.
DO $$ BEGIN
    IF to_regclass('iam.permission_level_binding') IS NOT NULL THEN
        UPDATE iam.permission_level_binding SET scope_type = 'ALL' WHERE scope_type = 'ORG';
        ALTER TABLE iam.permission_level_binding DROP CONSTRAINT IF EXISTS permission_level_scope_check;
        ALTER TABLE iam.permission_level_binding ADD CONSTRAINT permission_level_scope_check
            CHECK (scope_type IN ('ALL', 'SELF', 'ASSIGNED', 'PROJECT', 'CATEGORY', 'SELECTED',
                                  'PROJECT_GROUP', 'CREATOR', 'OWNER', 'MANAGER', 'PARTICIPANT', 'DEPARTMENT'));
    END IF;
    IF to_regclass('iam.user_scope_override') IS NOT NULL THEN
        UPDATE iam.user_scope_override SET scope_type = 'ALL' WHERE scope_type = 'ORG';
        ALTER TABLE iam.user_scope_override DROP CONSTRAINT IF EXISTS user_scope_override_scope_check;
        ALTER TABLE iam.user_scope_override ADD CONSTRAINT user_scope_override_scope_check
            CHECK (scope_type IN ('ALL', 'SELF', 'ASSIGNED', 'PROJECT', 'CATEGORY', 'SELECTED',
                                  'PROJECT_GROUP', 'CREATOR', 'OWNER', 'MANAGER', 'PARTICIPANT', 'DEPARTMENT'));
    END IF;
END $$;
