import { FileExcelOutlined, FileWordOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { App, Button, Card, Empty, Input, Popconfirm, Select, Space, Table, Tag, Typography } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import type { ProductionOrderListItem, ProductionOrderStatus } from '@/features/production-orders/types';
import { productionOrderApi } from '@/services/production-orders/production-order-api';

const statuses: Record<ProductionOrderStatus, { text: string; color: string }> = { DRAFT: { text: '填写中', color: 'processing' }, SUBMITTED: { text: '已提交', color: 'success' }, CANCELLED: { text: '已取消', color: 'default' } };

export function ProductionOrderListPage() {
  const { message } = App.useApp(); const navigate = useNavigate();
  const [items, setItems] = useState<ProductionOrderListItem[]>([]); const [loading, setLoading] = useState(true); const [keyword, setKeyword] = useState(''); const [status, setStatus] = useState<ProductionOrderStatus>();
  const load = useCallback(async () => { setLoading(true); try { setItems(await productionOrderApi.list()); } catch (error) { void message.error(error instanceof Error ? error.message : '生产单列表加载失败'); } finally { setLoading(false); } }, [message]);
  useEffect(() => { void load(); }, [load]);
  const filtered = useMemo(() => items.filter((item) => (!status || item.status === status) && (!keyword || `${item.orderNo}${item.templateName}${item.templateCode}`.toLowerCase().includes(keyword.toLowerCase()))), [items, keyword, status]);
  const cancel = async (id: string) => { await productionOrderApi.cancel(id); await load(); void message.success('生产单已取消'); };
  return <div className="business-page">
    <div className="page-heading"><div><Typography.Title level={2}>生产单查看</Typography.Title><Typography.Text type="secondary">查找生产单、继续填写草稿或查看已提交内容。</Typography.Text></div><Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/production-orders/upload')}>新建生产单</Button></div>
    <Card className="content-card filter-card"><Space wrap><Input.Search allowClear placeholder="生产单号、模板名称或编码" value={keyword} onChange={(event) => setKeyword(event.target.value)} style={{ width: 300 }} /><Select allowClear placeholder="全部状态" value={status} onChange={setStatus} style={{ width: 140 }} options={Object.entries(statuses).map(([value, item]) => ({ value, label: item.text }))} /><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button></Space></Card>
    <Card className="content-card" styles={{ body: { padding: 0 } }}><Table rowKey="id" loading={loading} dataSource={filtered} locale={{ emptyText: <Empty description="暂无符合条件的生产单" /> }} columns={[
      { title: '生产单号', dataIndex: 'orderNo', render: (value: string) => <Typography.Text strong>{value}</Typography.Text> },
      { title: '使用模板', dataIndex: 'templateName', render: (_, item) => <Space>{item.format === 'XLSX' ? <FileExcelOutlined className="excel-icon" /> : <FileWordOutlined className="word-icon" />}<span><Typography.Text>{item.templateName}</Typography.Text><small className="binding-path">{item.templateCode}</small></span></Space> },
      { title: '计划数量', width: 130, render: (_, item) => item.quantity ? `${item.quantity} ${item.unitCode || ''}` : '—' },
      { title: '计划日期', dataIndex: 'plannedDate', width: 120, render: (value?: string) => value || '—' },
      { title: '状态', dataIndex: 'status', width: 100, render: (value: ProductionOrderStatus) => <Tag color={statuses[value].color}>{statuses[value].text}</Tag> },
      { title: '最近更新', dataIndex: 'updatedAt', width: 180, render: (value: string) => new Date(value).toLocaleString('zh-CN') },
      { title: '操作', width: 190, render: (_, item) => <Space><Button type="link" onClick={() => navigate(`/production-orders/${item.id}/workspace`)}>{item.status === 'DRAFT' ? '继续填写' : '查看'}</Button>{item.status === 'DRAFT' && <Popconfirm title="取消这张生产单？" description="取消后将不能继续填写或提交。" onConfirm={() => void cancel(item.id)}><Button type="link" danger>取消</Button></Popconfirm>}</Space> },
    ]} /></Card>
  </div>;
}
