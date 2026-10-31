DO $$
BEGIN
    IF to_regclass('mfg.inventory_shipment_header') IS NULL THEN
        RAISE NOTICE 'skip V96: mfg.inventory_shipment_header not present';
        RETURN;
    END IF;

    ALTER TABLE mfg.inventory_shipment_header
        ADD COLUMN IF NOT EXISTS note varchar(500);
END $$;
