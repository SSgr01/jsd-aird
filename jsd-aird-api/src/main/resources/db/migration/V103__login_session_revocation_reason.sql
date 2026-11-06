ALTER TABLE iam.login_session
    ADD COLUMN IF NOT EXISTS revocation_reason varchar(32);
