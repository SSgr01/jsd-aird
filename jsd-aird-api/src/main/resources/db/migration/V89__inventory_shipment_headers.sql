CREATE TABLE IF NOT EXISTS mfg.inventory_shipment_header (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    shipment_no text NOT NULL,
    business_date date NOT NULL,
    actor_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT inventory_shipment_header_org_no_unique UNIQUE (organization_id, shipment_no)
);

DO $$
DECLARE conflicting text;
DECLARE old_constraint record;
BEGIN
    IF to_regclass('mfg.inventory_shipment') IS NULL THEN
        RAISE NOTICE 'skip V89 shipment backfill: mfg.inventory_shipment not present';
        RETURN;
    END IF;

    SELECT string_agg(organization_id::text || '/' || shipment_no, ', ')
      INTO conflicting
      FROM (SELECT organization_id, shipment_no
              FROM mfg.inventory_shipment
             GROUP BY organization_id, shipment_no
            HAVING count(DISTINCT business_date) > 1) conflicts;
    IF conflicting IS NOT NULL THEN
        RAISE EXCEPTION 'shipment dates conflict for: %', conflicting;
    END IF;

    INSERT INTO mfg.inventory_shipment_header(id, organization_id, shipment_no, business_date, actor_id)
    SELECT (array_agg(id ORDER BY created_at, id))[1], organization_id, shipment_no,
           min(business_date), (array_agg(actor_id ORDER BY created_at, id))[1]
      FROM mfg.inventory_shipment
     GROUP BY organization_id, shipment_no
    ON CONFLICT (organization_id, shipment_no) DO NOTHING;

    FOR old_constraint IN
        SELECT c.conname
          FROM pg_constraint c
          JOIN pg_class t ON t.oid = c.conrelid
          JOIN pg_namespace n ON n.oid = t.relnamespace
         WHERE n.nspname = 'mfg' AND t.relname = 'inventory_shipment'
           AND c.contype = 'u'
           AND (SELECT array_agg(a.attname::text ORDER BY a.attname)
                  FROM unnest(c.conkey) k(attnum)
                  JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum)
               IN (ARRAY['organization_id','shipment_no'], ARRAY['shipment_no'])
    LOOP
        EXECUTE format('ALTER TABLE mfg.inventory_shipment DROP CONSTRAINT %I', old_constraint.conname);
    END LOOP;

    FOR old_constraint IN
        SELECT i.relname AS index_name
          FROM pg_index x
          JOIN pg_class t ON t.oid = x.indrelid
          JOIN pg_namespace n ON n.oid = t.relnamespace
          JOIN pg_class i ON i.oid = x.indexrelid
         WHERE n.nspname = 'mfg' AND t.relname = 'inventory_shipment'
           AND x.indisunique AND NOT x.indisprimary
           AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = x.indexrelid)
           AND (SELECT array_agg(a.attname::text ORDER BY a.attname)
                  FROM unnest(x.indkey) k(attnum)
                  JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum)
               IN (ARRAY['organization_id','shipment_no'], ARRAY['shipment_no'])
    LOOP
        EXECUTE format('DROP INDEX mfg.%I', old_constraint.index_name);
    END LOOP;

    ALTER TABLE mfg.inventory_shipment
        ADD CONSTRAINT inventory_shipment_header_fk
        FOREIGN KEY (organization_id, shipment_no)
        REFERENCES mfg.inventory_shipment_header(organization_id, shipment_no);
    CREATE INDEX IF NOT EXISTS inventory_shipment_header_lookup
        ON mfg.inventory_shipment(organization_id, shipment_no);
    ALTER TABLE mfg.inventory_shipment DROP COLUMN business_date;
END $$;
