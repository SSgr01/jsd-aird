-- 发样记录表字段扩展：新增库存域（研发/生产扣减）、收件人电话、客户端联系人电话、
-- 本公司发样人。mfg.inventory_* 表由非 Flyway 基线创建，因此这里做表存在性保护，
-- 缺失该表的环境（例如未引入库存 DDL 的集成测试库）直接跳过，不阻断迁移。
DO $$
BEGIN
    IF to_regclass('mfg.inventory_sample_dispatch') IS NULL THEN
        RAISE NOTICE 'skip V74: mfg.inventory_sample_dispatch not present';
        RETURN;
    END IF;

    ALTER TABLE mfg.inventory_sample_dispatch
        ADD COLUMN IF NOT EXISTS inventory_scope varchar(32) NOT NULL DEFAULT 'RND';
    ALTER TABLE mfg.inventory_sample_dispatch
        ADD COLUMN IF NOT EXISTS recipient_phone varchar(64);
    ALTER TABLE mfg.inventory_sample_dispatch
        ADD COLUMN IF NOT EXISTS client_contact_phone varchar(64);
    ALTER TABLE mfg.inventory_sample_dispatch
        ADD COLUMN IF NOT EXISTS sender_name varchar(128);

    COMMENT ON COLUMN mfg.inventory_sample_dispatch.inventory_scope IS '扣减库存域：RND=研发库存，PRODUCTION=生产库存';
END $$;
