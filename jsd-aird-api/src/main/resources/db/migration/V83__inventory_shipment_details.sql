DO $$
BEGIN
    IF to_regclass('mfg.inventory_shipment') IS NULL THEN
        RAISE NOTICE 'skip V83: mfg.inventory_shipment not present';
        RETURN;
    END IF;

    ALTER TABLE mfg.inventory_shipment
        ADD COLUMN IF NOT EXISTS batch_no varchar(128),
        ADD COLUMN IF NOT EXISTS specification varchar(255),
        ADD COLUMN IF NOT EXISTS total_quantity_kg numeric(18,6),
        ADD COLUMN IF NOT EXISTS recipient_name varchar(128),
        ADD COLUMN IF NOT EXISTS recipient_phone varchar(64),
        ADD COLUMN IF NOT EXISTS shipping_address text,
        ADD COLUMN IF NOT EXISTS courier_company varchar(128),
        ADD COLUMN IF NOT EXISTS tracking_no varchar(128);
END $$;
