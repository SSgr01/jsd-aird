-- 出货记录表字段扩展：新增库存域（研发/生产扣减）与出货人。
-- 与 V69 一样做表存在性保护，缺失该表的环境直接跳过，不阻断迁移。
DO $$
BEGIN
    IF to_regclass('mfg.inventory_shipment') IS NULL THEN
        RAISE NOTICE 'skip V70: mfg.inventory_shipment not present';
        RETURN;
    END IF;

    ALTER TABLE mfg.inventory_shipment
        ADD COLUMN IF NOT EXISTS inventory_scope varchar(32) NOT NULL DEFAULT 'PRODUCTION';
    ALTER TABLE mfg.inventory_shipment
        ADD COLUMN IF NOT EXISTS sender_name varchar(128);

    COMMENT ON COLUMN mfg.inventory_shipment.inventory_scope IS '扣减库存域：RND=研发库存，PRODUCTION=生产库存';
END $$;
