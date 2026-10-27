-- 业务伙伴（客户）软删除支持：新增删除标记字段。
-- 删除客户时不再物理删除，也不依赖停用状态，而是置 deleted=true，
-- 并记录删除时间 deleted_at 与删除操作人 deleted_by，与 customer_requirement
-- 等模块的逻辑删除约定保持一致。
ALTER TABLE mdm.business_partner
    ADD COLUMN IF NOT EXISTS deleted boolean NOT NULL DEFAULT false;
ALTER TABLE mdm.business_partner
    ADD COLUMN IF NOT EXISTS deleted_at timestamptz;
ALTER TABLE mdm.business_partner
    ADD COLUMN IF NOT EXISTS deleted_by varchar(160);

COMMENT ON COLUMN mdm.business_partner.deleted IS '逻辑删除标记：true=已删除';
COMMENT ON COLUMN mdm.business_partner.deleted_at IS '删除时间';
COMMENT ON COLUMN mdm.business_partner.deleted_by IS '删除操作人';
