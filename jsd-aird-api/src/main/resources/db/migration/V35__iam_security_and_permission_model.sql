ALTER TABLE iam.app_user
    ADD COLUMN IF NOT EXISTS password_hash varchar(500),
    ADD COLUMN IF NOT EXISTS status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS email varchar(160),
    ADD COLUMN IF NOT EXISTS phone varchar(40),
    ADD COLUMN IF NOT EXISTS department_name varchar(160),
    ADD COLUMN IF NOT EXISTS password_changed_at timestamptz,
    ADD COLUMN IF NOT EXISTS auth_version bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_login_at timestamptz,
    ADD COLUMN IF NOT EXISTS failed_login_count integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS locked_until timestamptz,
    ADD COLUMN IF NOT EXISTS must_change_password boolean NOT NULL DEFAULT false;

ALTER TABLE iam.app_user
    DROP CONSTRAINT IF EXISTS app_user_status_check;

ALTER TABLE iam.app_user
    ADD CONSTRAINT app_user_status_check CHECK (status IN ('ACTIVE', 'DISABLED'));

CREATE TABLE IF NOT EXISTS iam.role (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    code varchar(80) NOT NULL,
    name varchar(120) NOT NULL,
    builtin boolean NOT NULL DEFAULT false,
    enabled boolean NOT NULL DEFAULT true,
    policy_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, code)
);

ALTER TABLE iam.app_user
    ADD COLUMN IF NOT EXISTS role_id uuid REFERENCES iam.role(id);

CREATE TABLE IF NOT EXISTS iam.permission_definition (
    code varchar(160) PRIMARY KEY,
    module varchar(80) NOT NULL,
    name varchar(160) NOT NULL,
    risk varchar(20) NOT NULL DEFAULT 'MEDIUM',
    default_scope varchar(20) NOT NULL DEFAULT 'ALL',
    enabled boolean NOT NULL DEFAULT true,
    definition_version integer NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT permission_definition_risk_check CHECK (risk IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT permission_definition_scope_check CHECK (
        default_scope IN ('ALL', 'SELF', 'ASSIGNED', 'PROJECT', 'CATEGORY', 'SELECTED')
    )
);

CREATE TABLE IF NOT EXISTS iam.role_permission_binding (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    role_id uuid NOT NULL REFERENCES iam.role(id) ON DELETE CASCADE,
    permission_code varchar(160) NOT NULL REFERENCES iam.permission_definition(code),
    effect varchar(10) NOT NULL,
    scope_type varchar(20) NOT NULL,
    target_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, role_id, permission_code),
    CONSTRAINT role_binding_effect_check CHECK (effect IN ('ALLOW', 'DENY')),
    CONSTRAINT role_binding_scope_check CHECK (
        scope_type IN ('ALL', 'SELF', 'ASSIGNED', 'PROJECT', 'CATEGORY', 'SELECTED')
    ),
    CONSTRAINT role_binding_target_check CHECK (
        (effect = 'DENY' AND target_ids = '[]'::jsonb)
        OR (effect = 'ALLOW')
    )
);

CREATE TABLE IF NOT EXISTS iam.user_permission_override (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE CASCADE,
    permission_code varchar(160) NOT NULL REFERENCES iam.permission_definition(code),
    effect varchar(10) NOT NULL,
    scope_type varchar(20) NOT NULL,
    target_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, user_id, permission_code),
    CONSTRAINT user_override_effect_check CHECK (effect IN ('ALLOW', 'DENY')),
    CONSTRAINT user_override_scope_check CHECK (
        scope_type IN ('ALL', 'SELF', 'ASSIGNED', 'PROJECT', 'CATEGORY', 'SELECTED')
    ),
    CONSTRAINT user_override_target_check CHECK (
        (effect = 'DENY' AND target_ids = '[]'::jsonb)
        OR (effect = 'ALLOW')
    )
);

CREATE TABLE IF NOT EXISTS iam.permission_scope_target (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    owner_type varchar(10) NOT NULL,
    owner_id uuid NOT NULL,
    permission_code varchar(160) NOT NULL REFERENCES iam.permission_definition(code),
    target_type varchar(80) NOT NULL,
    target_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, owner_type, owner_id, permission_code, target_type, target_id),
    CONSTRAINT scope_target_owner_check CHECK (owner_type IN ('ROLE', 'USER'))
);

CREATE TABLE IF NOT EXISTS iam.login_session (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE CASCADE,
    token_hash char(64) NOT NULL UNIQUE,
    issued_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    absolute_expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    auth_version bigint NOT NULL DEFAULT 0,
    ip_address varchar(64),
    user_agent varchar(500),
    remember_me boolean NOT NULL DEFAULT false
);

CREATE INDEX IF NOT EXISTS idx_login_session_user ON iam.login_session (organization_id, user_id, revoked_at);
CREATE INDEX IF NOT EXISTS idx_login_session_expiry ON iam.login_session (expires_at) WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS iam.login_attempt (
    id uuid PRIMARY KEY,
    organization_id uuid REFERENCES iam.organization(id),
    username varchar(80) NOT NULL,
    ip_address varchar(64),
    succeeded boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_login_attempt_account ON iam.login_attempt (organization_id, username, created_at);
CREATE INDEX IF NOT EXISTS idx_login_attempt_ip ON iam.login_attempt (ip_address, created_at);

ALTER TABLE ops.audit_log
    ALTER COLUMN actor_id DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_audit_log_org_created
    ON ops.audit_log (organization_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_org_actor
    ON ops.audit_log (organization_id, actor_id, created_at DESC);
