-- 客户需求软删除支持：新增删除标记字段。
-- 删除需求时不再物理删除，也不再将状态改为 CANCELLED，而是置 deleted=true，
-- 与 communication_record / project / project_stage 等模块的逻辑删除约定保持一致。
ALTER TABLE mdm.customer_requirement
    ADD COLUMN IF NOT EXISTS deleted boolean NOT NULL DEFAULT false;
ALTER TABLE mdm.customer_requirement
    ADD COLUMN IF NOT EXISTS deleted_at timestamptz;
ALTER TABLE mdm.customer_requirement
    ADD COLUMN IF NOT EXISTS deleted_by varchar(160);

COMMENT ON COLUMN mdm.customer_requirement.deleted IS '逻辑删除标记：true=已删除';
COMMENT ON COLUMN mdm.customer_requirement.deleted_at IS '删除时间';
COMMENT ON COLUMN mdm.customer_requirement.deleted_by IS '删除操作人';

-- 仅保留未删除需求行的局部索引，等价替换原有全量索引。
DROP INDEX IF EXISTS mdm.idx_requirement_partner;
CREATE INDEX idx_requirement_partner ON mdm.customer_requirement(partner_id, updated_at DESC) WHERE deleted = false;
