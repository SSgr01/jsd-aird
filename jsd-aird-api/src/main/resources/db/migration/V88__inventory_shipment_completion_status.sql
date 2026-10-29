DO $$
BEGIN
    IF to_regclass('mfg.inventory_shipment') IS NULL THEN
        RAISE NOTICE 'skip V88: mfg.inventory_shipment not present';
        RETURN;
    END IF;

    ALTER TABLE mfg.inventory_shipment
        ADD COLUMN IF NOT EXISTS status varchar(16) NOT NULL DEFAULT 'COMPLETED';
    ALTER TABLE mfg.inventory_shipment
        ALTER COLUMN status SET DEFAULT 'PENDING';
    ALTER TABLE mfg.inventory_shipment
        ALTER COLUMN inventory_transaction_id DROP NOT NULL;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'inventory_shipment_status_check'
          AND conrelid = 'mfg.inventory_shipment'::regclass
    ) THEN
        ALTER TABLE mfg.inventory_shipment
            ADD CONSTRAINT inventory_shipment_status_check
            CHECK (status IN ('PENDING', 'COMPLETED'));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'inventory_shipment_completion_transaction_check'
          AND conrelid = 'mfg.inventory_shipment'::regclass
    ) THEN
        ALTER TABLE mfg.inventory_shipment
            ADD CONSTRAINT inventory_shipment_completion_transaction_check
            CHECK ((status = 'PENDING' AND inventory_transaction_id IS NULL)
                OR (status = 'COMPLETED' AND inventory_transaction_id IS NOT NULL));
    END IF;
END $$;
