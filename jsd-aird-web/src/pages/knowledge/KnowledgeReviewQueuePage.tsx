import { EditOutlined, ReloadOutlined } from '@ant-design/icons';
import { App, Button, Card, Empty, Select, Space, Table, Tag, Typography } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { knowledgeApi, type ReviewQueueItem } from '@/services/knowledge';
import { reviewQueueStatus } from './knowledge-workflow-status';

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
    <div className="business-page-heading"><div><Typography.Title level={2}><EditOutlined /> 内容校对</Typography.Title><Typography.Text type="secondary">校对识别内容，确认后发布。</Typography.Text></div><Space><Select allowClear placeholder="全部校对状态" value={status} onChange={setStatus} options={[{ value: 'PENDING_REVIEW', label: '待校对' }, { value: 'REJECTED', label: '已驳回' }]} /><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button></Space></div>
    <Card className="content-card">
      <Table className="catalog-knowledge-table knowledge-review-queue-table" scroll={{ x: 960 }} rowKey="versionId" loading={loading} dataSource={items} locale={{ emptyText: <Empty description="当前没有待处理的知识版本" /> }} columns={[
        { title: '文件', width: 320, render: (_: unknown, item: ReviewQueueItem) => <div><Typography.Text strong>{item.title}</Typography.Text><div className="binding-path">{item.originalName} · V{item.versionNo}</div></div> },
        { title: '分类', width: 120, dataIndex: 'categoryName', render: (value?: string) => value || <Tag color="error">未分类</Tag> },
        { title: '解析', width: 110, dataIndex: 'processingStatus', render: (value: string) => <Tag color={value === 'READY' ? 'success' : value === 'FAILED' ? 'error' : 'processing'}>{value === 'READY' ? '解析完成' : value === 'FAILED' ? '解析失败' : '解析中'}</Tag> },
        { title: '校对', width: 100, render: (_: unknown, item: ReviewQueueItem) => { const state = reviewQueueStatus(item); return <Tag color={state.color}>{state.label}</Tag>; } },
        { title: '最近更新', width: 180, dataIndex: 'updatedAt', render: (value: string) => new Date(value).toLocaleString('zh-CN') },
        { title: '操作', width: 130, render: (_: unknown, item: ReviewQueueItem) => <Button type={item.reviewRevisionStatus === 'FAILED' ? 'default' : 'primary'} onClick={() => navigate(`/knowledge/review/${item.documentId}/${item.versionId}`)}>{item.reviewRevisionStatus === 'FAILED' ? '查看详情' : '开始校对'}</Button> },
      ]} />
    </Card>
  </div>;
}
