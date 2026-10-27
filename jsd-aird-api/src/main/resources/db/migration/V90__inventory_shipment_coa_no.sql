DO $$
BEGIN
    IF to_regclass('mfg.inventory_shipment') IS NULL THEN
        RAISE NOTICE 'skip V90: mfg.inventory_shipment not present';
        RETURN;
    END IF;

    ALTER TABLE mfg.inventory_shipment ADD COLUMN IF NOT EXISTS coa_no varchar(255);

    UPDATE mfg.inventory_shipment
       SET coa_no = NULLIF(btrim((regexp_match(note, '(^|[；;])COA=([^；;]+)'))[2]), ''),
           note = NULLIF(btrim(regexp_replace(note, '(^|[；;])COA=[^；;]*', '', 'g'), '；; '), '')
     WHERE note ~ '(^|[；;])COA=';
END $$;
