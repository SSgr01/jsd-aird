-- 发样/出货出库允许库存扣减为负数（业务设计见 InventoryService.move 的 allowNegative），
-- 但 mfg.inventory_transaction.after_kg 上存在 >= 0 的检查约束、inventory_stock_balance.quantity_kg
-- 上也可能存在类似约束，会直接拒绝负余额写入。此处放宽这些约束以匹配设计意图。
-- 表由外部库存 DDL 注入，部分集成测试库可能不存在这些表，故用 to_regclass 守卫，缺失则跳过。
DO $$
DECLARE
    r RECORD;
BEGIN
    IF to_regclass('mfg.inventory_transaction') IS NOT NULL THEN
        ALTER TABLE mfg.inventory_transaction DROP CONSTRAINT IF EXISTS inventory_transaction_after_kg_check;
    END IF;

    IF to_regclass('mfg.inventory_stock_balance') IS NOT NULL THEN
        FOR r IN
            SELECT conname
            FROM pg_constraint c
            JOIN pg_class t ON t.oid = c.conrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            WHERE n.nspname = 'mfg'
              AND t.relname = 'inventory_stock_balance'
              AND c.contype = 'c'
              AND pg_get_constraintdef(c.oid) ILIKE '%quantity_kg%>=%'
        LOOP
            EXECUTE format('ALTER TABLE mfg.inventory_stock_balance DROP CONSTRAINT %I', r.conname);
        END LOOP;
    END IF;
END $$;
