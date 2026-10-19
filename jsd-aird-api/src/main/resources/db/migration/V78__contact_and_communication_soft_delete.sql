-- 客户需求（customer_requirement）、负责人（partner_contact）与跟进记录（communication_record）
-- 软删除支持：删除时记录删除时间与操作人，不再依赖改状态（停用）来模拟删除。
-- communication_record 已存在 deleted 列，这里仅补充 deleted_at / deleted_by。

-- 一、客户需求软删除：删除需求时不再物理删除，也不再将状态改为 CANCELLED，而是置 deleted=true，
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

-- 二、负责人软删除。
ALTER TABLE mdm.partner_contact
    ADD COLUMN IF NOT EXISTS deleted boolean NOT NULL DEFAULT false;
ALTER TABLE mdm.partner_contact
    ADD COLUMN IF NOT EXISTS deleted_at timestamptz;
ALTER TABLE mdm.partner_contact
    ADD COLUMN IF NOT EXISTS deleted_by varchar(160);

COMMENT ON COLUMN mdm.partner_contact.deleted IS '逻辑删除标记：true=已删除';
COMMENT ON COLUMN mdm.partner_contact.deleted_at IS '删除时间';
COMMENT ON COLUMN mdm.partner_contact.deleted_by IS '删除操作人';

-- 三、跟进记录软删除。
ALTER TABLE mdm.communication_record
    ADD COLUMN IF NOT EXISTS deleted_at timestamptz;
ALTER TABLE mdm.communication_record
    ADD COLUMN IF NOT EXISTS deleted_by varchar(160);

COMMENT ON COLUMN mdm.communication_record.deleted_at IS '删除时间';
COMMENT ON COLUMN mdm.communication_record.deleted_by IS '删除操作人';
