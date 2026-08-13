import { AuditOutlined, ReloadOutlined } from '@ant-design/icons';
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
    <div className="business-page-heading"><div><Typography.Title level={2}><AuditOutlined /> 审核工作台</Typography.Title><Typography.Text type="secondary">确认解析文本、抽取字段和关联对象后，再发布当前版本。</Typography.Text></div><Space><Select allowClear placeholder="全部待审核状态" value={status} onChange={setStatus} options={[{ value: 'PENDING_REVIEW', label: '待审核' }, { value: 'REJECTED', label: '已驳回' }]} /><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button></Space></div>
    <Card className="content-card">
      <Table rowKey="versionId" loading={loading} dataSource={items} locale={{ emptyText: <Empty description="当前没有待处理的知识版本" /> }} columns={[
        { title: '文件', render: (_: unknown, item: ReviewQueueItem) => <div><Typography.Text strong>{item.title}</Typography.Text><div className="binding-path">{item.originalName} · V{item.versionNo}</div></div> },
        { title: '文档类型', dataIndex: 'documentType' },
        { title: '分类', dataIndex: 'categoryName', render: (value?: string) => value || <Tag color="error">未分类</Tag> },
        { title: '解析', dataIndex: 'processingStatus', render: (value: string) => <Tag color={value === 'READY' ? 'success' : value === 'PENDING_PROVIDER' ? 'warning' : 'processing'}>{value}</Tag> },
        { title: '审核', dataIndex: 'reviewStatus', render: (value: string) => <Tag color={value === 'REJECTED' ? 'error' : 'gold'}>{value === 'REJECTED' ? '已驳回' : '待审核'}</Tag> },
        { title: '最近更新', dataIndex: 'updatedAt', render: (value: string) => new Date(value).toLocaleString('zh-CN') },
        { title: '操作', width: 140, render: (_: unknown, item: ReviewQueueItem) => <Button type="primary" onClick={() => navigate(`/knowledge/review/${item.documentId}/${item.versionId}`)}>进入审核</Button> },
      ]} />
    </Card>
  </div>;
}
