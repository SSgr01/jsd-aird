CREATE TABLE ops.workspace_review (
    organization_id uuid NOT NULL,
    resource_type varchar(32) NOT NULL,
    resource_id uuid NOT NULL,
    status varchar(24) NOT NULL,
    resource_updated_at timestamptz NOT NULL,
    submitted_by uuid,
    submitted_at timestamptz,
    approved_by uuid,
    approved_at timestamptz,
    review_comment text,
    rejected_by uuid,
    rejected_at timestamptz,
    PRIMARY KEY (organization_id, resource_type, resource_id)
);

CREATE TABLE IF NOT EXISTS ops.workspace_review_event (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    resource_type varchar(32) NOT NULL,
    resource_id uuid NOT NULL,
    action varchar(24) NOT NULL,
    comment text,
    operator_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_workspace_review_event_resource
    ON ops.workspace_review_event (organization_id,resource_type,resource_id,created_at DESC);

INSERT INTO ops.workspace_review_event (id,organization_id,resource_type,resource_id,action,operator_id,created_at)
SELECT gen_random_uuid(),w.organization_id,w.resource_type,w.resource_id,'SUBMITTED',w.submitted_by,w.submitted_at
FROM ops.workspace_review w
WHERE w.submitted_by IS NOT NULL AND w.submitted_at IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM ops.workspace_review_event e WHERE e.organization_id=w.organization_id
      AND e.resource_type=w.resource_type AND e.resource_id=w.resource_id AND e.action='SUBMITTED'
      AND e.created_at=w.submitted_at);

INSERT INTO ops.workspace_review_event (id,organization_id,resource_type,resource_id,action,operator_id,created_at)
SELECT gen_random_uuid(),w.organization_id,w.resource_type,w.resource_id,'APPROVED',w.approved_by,w.approved_at
FROM ops.workspace_review w
WHERE w.approved_by IS NOT NULL AND w.approved_at IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM ops.workspace_review_event e WHERE e.organization_id=w.organization_id
      AND e.resource_type=w.resource_type AND e.resource_id=w.resource_id AND e.action='APPROVED'
      AND e.created_at=w.approved_at);

INSERT INTO ops.workspace_review_event (id,organization_id,resource_type,resource_id,action,comment,operator_id,created_at)
SELECT gen_random_uuid(),w.organization_id,w.resource_type,w.resource_id,'REJECTED',w.review_comment,w.rejected_by,w.rejected_at
FROM ops.workspace_review w
WHERE w.rejected_by IS NOT NULL AND w.rejected_at IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM ops.workspace_review_event e WHERE e.organization_id=w.organization_id
      AND e.resource_type=w.resource_type AND e.resource_id=w.resource_id AND e.action='REJECTED'
      AND e.created_at=w.rejected_at);
