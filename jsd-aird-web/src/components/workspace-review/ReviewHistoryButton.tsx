import { HistoryOutlined } from '@ant-design/icons';
import { Button, List, Modal, Space } from 'antd';
import { useState } from 'react';

export interface ReviewHistoryItem {
  action?: unknown;
  comment?: unknown;
  operatorName?: unknown;
  createdAt?: unknown;
}

const actionLabels: Record<string, string> = {
  SUBMITTED: '提交审核', PENDING_REVIEW: '提交审核',
  APPROVED: '审核通过', COMPLETED: '审核通过并发布',
  REJECTED: '已退回', RETURNED: '已退回',
};

export function ReviewHistoryButton({ items }: { items: ReviewHistoryItem[] }) {
  const [open, setOpen] = useState(false);
  return <>
    <Button icon={<HistoryOutlined />} onClick={() => setOpen(true)}>审核记录</Button>
    <Modal title="审核记录" open={open} footer={null} onCancel={() => setOpen(false)}>
      <List locale={{ emptyText: '暂无审核记录' }} dataSource={items} renderItem={(item) => <List.Item>
        <Space direction="vertical" size={0}>
          <strong>{actionLabels[String(item.action ?? '')] || String(item.action ?? '')}</strong>
          <span>{[item.operatorName, item.createdAt ? new Date(String(item.createdAt)).toLocaleString('zh-CN') : undefined].filter(Boolean).join(' · ')}</span>
          {typeof item.comment === 'string' && item.comment.trim() && <span>{item.comment}</span>}
        </Space>
      </List.Item>} />
    </Modal>
  </>;
}
