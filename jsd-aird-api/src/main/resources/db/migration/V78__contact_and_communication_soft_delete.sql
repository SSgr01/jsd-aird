-- 负责人（partner_contact）与跟进记录（communication_record）软删除支持。
-- 删除负责人/跟进记录时记录删除时间与操作人，不再依赖改状态（停用）来模拟删除。
-- communication_record 已存在 deleted 列，这里仅补充 deleted_at / deleted_by。

ALTER TABLE mdm.partner_contact
    ADD COLUMN IF NOT EXISTS deleted boolean NOT NULL DEFAULT false;
ALTER TABLE mdm.partner_contact
    ADD COLUMN IF NOT EXISTS deleted_at timestamptz;
ALTER TABLE mdm.partner_contact
    ADD COLUMN IF NOT EXISTS deleted_by varchar(160);

COMMENT ON COLUMN mdm.partner_contact.deleted IS '逻辑删除标记：true=已删除';
COMMENT ON COLUMN mdm.partner_contact.deleted_at IS '删除时间';
COMMENT ON COLUMN mdm.partner_contact.deleted_by IS '删除操作人';

ALTER TABLE mdm.communication_record
    ADD COLUMN IF NOT EXISTS deleted_at timestamptz;
ALTER TABLE mdm.communication_record
    ADD COLUMN IF NOT EXISTS deleted_by varchar(160);

COMMENT ON COLUMN mdm.communication_record.deleted_at IS '删除时间';
COMMENT ON COLUMN mdm.communication_record.deleted_by IS '删除操作人';
