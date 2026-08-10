CREATE TABLE data.data_category (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    name varchar(120) NOT NULL,
    target_data_type varchar(64) CHECK (target_data_type IS NULL OR target_data_type IN (
        'MATERIAL', 'FORMULA', 'PROCESS', 'EQUIPMENT', 'TEST_STANDARD'
    )),
    sort_order integer NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, name)
);

ALTER TABLE data.import_job
    ADD COLUMN category_id uuid REFERENCES data.data_category(id);

ALTER TABLE data.data_asset
    ADD COLUMN category_id uuid REFERENCES data.data_category(id);

CREATE INDEX idx_data_category_list
    ON data.data_category (organization_id, sort_order, created_at);

CREATE INDEX idx_data_asset_category
    ON data.data_asset (organization_id, category_id, updated_at DESC);

INSERT INTO data.data_category (id, organization_id, name, target_data_type, sort_order, created_by)
SELECT gen_random_uuid(), o.id, seed.name, seed.target_data_type, seed.sort_order, u.id
FROM iam.organization o
JOIN LATERAL (
    SELECT * FROM (VALUES
        ('物料/原料', 'MATERIAL', 10), ('配方', 'FORMULA', 20), ('工艺', 'PROCESS', 30),
        ('设备/仪器', 'EQUIPMENT', 40), ('检测标准', 'TEST_STANDARD', 50)
    ) AS values_table(name, target_data_type, sort_order)
) seed ON true
JOIN LATERAL (
    SELECT id FROM iam.app_user WHERE organization_id = o.id ORDER BY created_at LIMIT 1
) u ON true
ON CONFLICT (organization_id, name) DO NOTHING;

UPDATE data.data_asset a
SET category_id = c.id
FROM data.data_category c
WHERE a.category_id IS NULL
  AND c.organization_id = a.organization_id
  AND c.target_data_type = a.target_data_type;

UPDATE data.import_job j
SET category_id = c.id
FROM data.data_category c
WHERE j.category_id IS NULL
  AND c.organization_id = j.organization_id
  AND c.target_data_type = j.target_data_type;
