import { BookOutlined, ExclamationCircleOutlined, ReloadOutlined } from '@ant-design/icons';
import { App, Button, Card, Empty, Space, Table, Tag, Typography } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { knowledgeApi, type KnowledgePageListItem } from '@/services/knowledge';

export function KnowledgePagesPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [items, setItems] = useState<KnowledgePageListItem[]>([]);
  const [loading, setLoading] = useState(false);
  const load = useCallback(async () => {
    setLoading(true);
    try { setItems(await knowledgeApi.pages()); }
    catch (reason) { void message.error(reason instanceof Error ? reason.message : '知识页加载失败'); }
    finally { setLoading(false); }
  }, [message]);
  useEffect(() => { void load(); }, [load]);
  return <div className="business-page"><div className="business-page-heading"><div><Typography.Title level={2}><BookOutlined /> 知识页</Typography.Title><Typography.Text type="secondary">按产品、项目、实验、配方等关联对象自动聚合当前有效发布资料。</Typography.Text></div><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button></div><Card className="content-card"><Table rowKey="id" loading={loading} dataSource={items} locale={{ emptyText: <Empty description="文档关联业务对象后会自动生成知识页" /> }} columns={[
    { title: '关联对象', render: (_: unknown, item: KnowledgePageListItem) => <div><Space><Tag>{item.objectType}</Tag><Typography.Text strong>{item.objectName}</Typography.Text></Space><div className="binding-path">{item.externalId}</div></div> },
    { title: '知识页', render: (_: unknown, item: KnowledgePageListItem) => <div><Typography.Text strong>{item.title}</Typography.Text><Typography.Paragraph type="secondary" ellipsis={{ rows: 1 }} style={{ margin: 0 }}>{item.summary || '暂无已发布摘要'}</Typography.Paragraph></div> },
    { title: '当前版本', dataIndex: 'currentVersionNo', render: (value?: number) => value ? `V${value}` : <Tag>未发布</Tag> },
    { title: '来源', render: (_: unknown, item: KnowledgePageListItem) => `${item.currentSourceCount} / ${item.availableSourceCount}` },
    { title: '更新状态', render: (_: unknown, item: KnowledgePageListItem) => item.hasUpdates ? <Tag color="warning" icon={<ExclamationCircleOutlined />}>有新来源待更新</Tag> : <Tag color="success">已同步</Tag> },
    { title: '操作', render: (_: unknown, item: KnowledgePageListItem) => <Button type="primary" onClick={() => navigate(`/knowledge/pages/${item.id}`)}>查看与维护</Button> },
  ]} /></Card></div>;
}
