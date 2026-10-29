DO $$
BEGIN
    IF to_regclass('mfg.inventory_shipment') IS NULL THEN
        RAISE NOTICE 'skip V85: mfg.inventory_shipment not present';
        RETURN;
    END IF;

    ALTER TABLE mfg.inventory_shipment
        ADD COLUMN IF NOT EXISTS package_quantity numeric(18,6);
END $$;
