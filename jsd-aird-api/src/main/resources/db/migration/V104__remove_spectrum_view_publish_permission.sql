-- 图谱查看页面不提供发布操作，清理历史目录项及其授权绑定。
DELETE FROM iam.permission_scope_target WHERE permission_code = 'spectrum.view.publish';
DELETE FROM iam.user_permission_override WHERE permission_code = 'spectrum.view.publish';
DELETE FROM iam.role_permission_binding WHERE permission_code = 'spectrum.view.publish';

DO $$
BEGIN
    IF to_regclass('iam.permission_level_binding') IS NOT NULL THEN
        DELETE FROM iam.permission_level_binding WHERE permission_code = 'spectrum.view.publish';
    END IF;
    IF to_regclass('iam.user_operation_override') IS NOT NULL THEN
        DELETE FROM iam.user_operation_override WHERE permission_code = 'spectrum.view.publish';
    END IF;
    IF to_regclass('iam.user_scope_override') IS NOT NULL THEN
        DELETE FROM iam.user_scope_override WHERE permission_code = 'spectrum.view.publish';
    END IF;
    IF to_regclass('iam.role_permission_grant') IS NOT NULL THEN
        DELETE FROM iam.role_permission_grant WHERE permission_code = 'spectrum.view.publish';
    END IF;
END $$;

DELETE FROM iam.permission_definition WHERE code = 'spectrum.view.publish';
