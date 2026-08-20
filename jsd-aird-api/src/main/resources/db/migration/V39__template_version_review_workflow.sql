CREATE TABLE IF NOT EXISTS tpl.template_version_review (
    version_id uuid PRIMARY KEY REFERENCES tpl.template_version(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    review_status varchar(20) NOT NULL DEFAULT 'NOT_SUBMITTED',
    submitted_by uuid REFERENCES iam.app_user(id),
    submitted_at timestamptz,
    reviewed_by uuid REFERENCES iam.app_user(id),
    reviewed_at timestamptz,
    review_comment text,
    lock_version bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT template_version_review_status_check CHECK (
        review_status IN ('NOT_SUBMITTED', 'SUBMITTED', 'APPROVED', 'REJECTED')
    )
);

CREATE TABLE IF NOT EXISTS tpl.template_version_review_event (
    id uuid PRIMARY KEY,
    version_id uuid NOT NULL REFERENCES tpl.template_version(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    from_status varchar(20),
    to_status varchar(20) NOT NULL,
    actor_id uuid REFERENCES iam.app_user(id),
    comment text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT template_version_review_event_status_check CHECK (
        to_status IN ('NOT_SUBMITTED', 'SUBMITTED', 'APPROVED', 'REJECTED')
    )
);

CREATE INDEX IF NOT EXISTS idx_template_version_review_org_status
    ON tpl.template_version_review (organization_id, review_status, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_template_version_review_event_version
    ON tpl.template_version_review_event (organization_id, version_id, created_at DESC);
