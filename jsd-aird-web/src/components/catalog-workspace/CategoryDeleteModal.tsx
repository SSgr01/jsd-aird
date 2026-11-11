import { Modal, Select, Typography } from 'antd';

export interface CategoryDeleteReplacement {
  value?: string;
  options: Array<{ value: string; label: string }>;
  onChange: (value?: string) => void;
}

interface CategoryDeleteModalProps {
  open: boolean;
  title: string;
  description: string;
  confirmLoading?: boolean;
  replacement?: CategoryDeleteReplacement;
  onCancel: () => void;
  onConfirm: () => void;
}

/**
 * 分类删除确认框：以模板中心的交互为基准，所有分类入口保持一致。
 * 传入 replacement 时展示迁移目标下拉并给出「删除并迁移」；
 * 业务侧不支持迁移（如品管、图谱）时只给出「删除」。
 */
export function CategoryDeleteModal({ open, title, description, confirmLoading, replacement, onCancel, onConfirm }: CategoryDeleteModalProps) {
  return (
    <Modal
      open={open}
      title={title}
      okText={replacement ? '删除并迁移' : '删除'}
      okButtonProps={{ danger: true }}
      cancelText="取消"
      confirmLoading={confirmLoading}
      onCancel={onCancel}
      onOk={onConfirm}
    >
      <Typography.Paragraph>{description}</Typography.Paragraph>
      {replacement && (
        <Select
          allowClear
          value={replacement.value}
          onChange={replacement.onChange}
          options={replacement.options}
          style={{ width: '100%' }}
        />
      )}
    </Modal>
  );
}
