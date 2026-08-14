import { EditOutlined, ReloadOutlined } from '@ant-design/icons';
import { App, Button, Card, Empty, Select, Space, Table, Tag, Typography } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { knowledgeApi, type ReviewQueueItem } from '@/services/knowledge';

export function KnowledgeReviewQueuePage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [items, setItems] = useState<ReviewQueueItem[]>([]);
  const [status, setStatus] = useState<string>();
  const [loading, setLoading] = useState(false);
  const load = useCallback(async () => {
    setLoading(true);
    try { setItems(await knowledgeApi.reviewQueue(status)); }
    catch (reason) { void message.error(reason instanceof Error ? reason.message : '审核队列加载失败'); }
    finally { setLoading(false); }
  }, [message, status]);
  useEffect(() => { void load(); }, [load]);
  return <div className="business-page">
    <div className="business-page-heading"><div><Typography.Title level={2}><EditOutlined /> 内容校对</Typography.Title><Typography.Text type="secondary">连续校对整篇识别文本，确认发布后才建立检索索引。</Typography.Text></div><Space><Select allowClear placeholder="全部校对状态" value={status} onChange={setStatus} options={[{ value: 'PENDING_REVIEW', label: '待校对' }, { value: 'REJECTED', label: '已驳回' }]} /><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button></Space></div>
    <Card className="content-card">
      <Table rowKey="versionId" loading={loading} dataSource={items} locale={{ emptyText: <Empty description="当前没有待处理的知识版本" /> }} columns={[
        { title: '文件', render: (_: unknown, item: ReviewQueueItem) => <div><Typography.Text strong>{item.title}</Typography.Text><div className="binding-path">{item.originalName} · V{item.versionNo}</div></div> },
        { title: '分类', dataIndex: 'categoryName', render: (value?: string) => value || <Tag color="error">未分类</Tag> },
        { title: '解析', dataIndex: 'processingStatus', render: (value: string) => <Tag color={value === 'READY' ? 'success' : value === 'FAILED' ? 'error' : 'processing'}>{value === 'READY' ? '待校对' : value === 'FAILED' ? '解析失败' : '解析中'}</Tag> },
        { title: '校对', dataIndex: 'reviewStatus', render: (value: string) => <Tag color={value === 'REJECTED' ? 'error' : 'gold'}>{value === 'REJECTED' ? '已驳回' : '待校对'}</Tag> },
        { title: '最近更新', dataIndex: 'updatedAt', render: (value: string) => new Date(value).toLocaleString('zh-CN') },
        { title: '操作', width: 140, render: (_: unknown, item: ReviewQueueItem) => <Button type="primary" onClick={() => navigate(`/knowledge/review/${item.documentId}/${item.versionId}`)}>开始校对</Button> },
      ]} />
    </Card>
  </div>;
}
