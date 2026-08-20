ALTER TABLE iam.app_user
    DROP COLUMN IF EXISTS must_change_password,
    DROP COLUMN IF EXISTS password_changed_at;
