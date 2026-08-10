CREATE TABLE kb.document_category (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    scope varchar(16) NOT NULL CHECK (scope IN ('INTERNAL', 'EXTERNAL')),
    name varchar(120) NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, scope, name)
);

ALTER TABLE kb.document
    ADD COLUMN library_scope varchar(16) NOT NULL DEFAULT 'INTERNAL'
        CHECK (library_scope IN ('INTERNAL', 'EXTERNAL')),
    ADD COLUMN category_id uuid REFERENCES kb.document_category(id);

CREATE INDEX idx_kb_document_category
    ON kb.document (organization_id, library_scope, category_id, updated_at DESC);

CREATE INDEX idx_kb_document_category_list
    ON kb.document_category (organization_id, scope, sort_order, created_at);

INSERT INTO kb.document_category (id, organization_id, scope, name, sort_order, created_by)
SELECT gen_random_uuid(), o.id, s.scope, '未分类', 0, u.id
FROM iam.organization o
JOIN LATERAL (
    SELECT 'INTERNAL'::varchar AS scope
    UNION ALL SELECT 'EXTERNAL'::varchar
) s ON true
JOIN LATERAL (
    SELECT id FROM iam.app_user WHERE organization_id = o.id ORDER BY created_at LIMIT 1
) u ON true
ON CONFLICT (organization_id, scope, name) DO NOTHING;

INSERT INTO kb.document_category (id, organization_id, scope, name, sort_order, created_by)
SELECT gen_random_uuid(), o.id, 'INTERNAL', seed.name, seed.sort_order, u.id
FROM iam.organization o
JOIN LATERAL (
    SELECT * FROM (VALUES
        ('产品知识', 10), ('实验知识', 20), ('测试知识', 30), ('配方知识', 40), ('技术知识', 50)
    ) AS values_table(name, sort_order)
) seed ON true
JOIN LATERAL (
    SELECT id FROM iam.app_user WHERE organization_id = o.id ORDER BY created_at LIMIT 1
) u ON true
ON CONFLICT (organization_id, scope, name) DO NOTHING;

UPDATE kb.document d
SET category_id = c.id
FROM kb.document_category c
WHERE d.category_id IS NULL
  AND c.organization_id = d.organization_id
  AND c.scope = d.library_scope
  AND c.name = '未分类';
