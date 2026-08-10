CREATE TABLE tpl.template_category (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    name varchar(120) NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, name)
);

ALTER TABLE tpl.template
    ADD COLUMN category_id uuid REFERENCES tpl.template_category(id);

INSERT INTO tpl.template_category (id, organization_id, name, sort_order, created_by)
SELECT gen_random_uuid(), t.organization_id, trim(t.category),
       row_number() OVER (PARTITION BY t.organization_id ORDER BY min(t.created_at), trim(t.category))::integer,
       min(t.created_by::text)::uuid
FROM tpl.template t
WHERE nullif(trim(t.category), '') IS NOT NULL
GROUP BY t.organization_id, trim(t.category);

UPDATE tpl.template t
SET category_id = c.id,
    category = c.name
FROM tpl.template_category c
WHERE c.organization_id = t.organization_id
  AND c.name = trim(t.category);

CREATE INDEX idx_template_category_organization_sort
    ON tpl.template_category (organization_id, sort_order, created_at);

CREATE INDEX idx_template_category_id ON tpl.template (category_id);
