-- 库存流水支持手动指定经办人：新增覆盖列，为空时回退到操作员显示名。
-- 与 V69/V70 一样做表存在性保护，缺失该表的环境直接跳过，不阻断迁移。
DO $$
BEGIN
    IF to_regclass('mfg.inventory_transaction') IS NULL THEN
        RAISE NOTICE 'skip V72: mfg.inventory_transaction not present';
        RETURN;
    END IF;

    ALTER TABLE mfg.inventory_transaction
        ADD COLUMN IF NOT EXISTS actor_name varchar(255);

    COMMENT ON COLUMN mfg.inventory_transaction.actor_name IS '经办人覆盖名；为空时回退到 actor_id 对应的用户显示名';
END $$;
