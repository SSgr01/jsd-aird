-- 放开“库存不能为负”的数据库检查约束（发样/出货允许扣成负数，业务设计见 InventoryService.move 的 allowNegative）。
--
-- 背景：V71 只按固定名字删 mfg.inventory_transaction 上的 after_kg 约束，而库存表由外部 DDL 建表，
-- 约束名不固定时删不掉；此时任何把流水 after_kg 扣成负数的写入都会以 SQLSTATE 23514 失败，
-- 并被 InventoryService 翻译成“业务单号或幂等键已存在”这种误导提示。
-- 这里改为按约束定义匹配，名称无关地清理 after_kg / before_kg / quantity_kg 上的 >= 0 限制。
-- 只处理“仅由该非负判断构成”的检查约束，带其他条件（AND/OR）的约束保持不动。
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT n.nspname AS schema_name, t.relname AS table_name, c.conname AS constraint_name
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = 'mfg'
          AND t.relname IN ('inventory_transaction', 'inventory_stock_balance')
          AND c.contype = 'c'
          AND pg_get_constraintdef(c.oid) ~* '(after_kg|before_kg|quantity_kg)\s*>=\s*\(?\s*0'
          AND pg_get_constraintdef(c.oid) !~* '\y(and|or)\y'
    LOOP
        RAISE NOTICE 'drop inventory non-negative check constraint: %.%.%',
            r.schema_name, r.table_name, r.constraint_name;
        EXECUTE format('ALTER TABLE %I.%I DROP CONSTRAINT %I',
            r.schema_name, r.table_name, r.constraint_name);
    END LOOP;
END $$;
