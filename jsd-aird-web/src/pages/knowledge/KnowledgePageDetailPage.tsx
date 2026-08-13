import { ArrowLeftOutlined, CheckOutlined, SaveOutlined } from '@ant-design/icons';
import { App, Button, Card, Descriptions, Empty, Input, Space, Spin, Table, Tag, Typography } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { knowledgeApi, type KnowledgePageView } from '@/services/knowledge';

export function KnowledgePageDetailPage() {
  const { id } = useParams();
  const { message, modal } = App.useApp();
  const navigate = useNavigate();
  const [view, setView] = useState<KnowledgePageView>();
  const [title, setTitle] = useState('');
  const [summary, setSummary] = useState('');
  const [loading, setLoading] = useState(true);
  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try { const next = await knowledgeApi.page(id); setView(next); setTitle(next.page.draftTitle); setSummary(next.page.draftSummary || ''); }
    catch (reason) { void message.error(reason instanceof Error ? reason.message : '知识页加载失败'); }
    finally { setLoading(false); }
  }, [id, message]);
  useEffect(() => { void load(); }, [load]);
  const save = async () => {
    if (!id || !view) return undefined;
    try { const next = await knowledgeApi.savePageDraft(id, { title, summary, draftRevision: view.page.draftRevision }); setView(next); void message.success('摘要草稿已保存'); return next; }
    catch (reason) { void message.error(reason instanceof Error ? reason.message : '保存失败，请刷新后重试'); return undefined; }
  };
  const publish = () => { if (!id || !view) return; modal.confirm({ title: '发布新的知识页版本？', content: `将冻结当前 ${view.availableSources.length} 个来源发布快照，历史版本不会被覆盖。`, okText: '确认发布', onOk: async () => { const saved = await save(); if (!saved) throw new Error('草稿保存失败'); await knowledgeApi.publishPage(id, saved.page.draftRevision); void message.success('知识页已发布'); await load(); } }); };
  if (loading) return <div className="business-page"><Spin /></div>;
  if (!view) return <div className="business-page"><Empty description="知识页不存在" /></div>;
  return <div className="business-page"><div className="business-page-heading"><Space><Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/knowledge/pages')}>返回知识页</Button><div><Typography.Title level={2} style={{ margin: 0 }}>{view.page.objectName}</Typography.Title><Typography.Text type="secondary">{view.page.objectType} · {view.page.externalId}</Typography.Text></div></Space><Space><Button icon={<SaveOutlined />} onClick={() => void save()}>保存草稿</Button><Button type="primary" icon={<CheckOutlined />} onClick={publish}>发布新版本</Button></Space></div>
    {view.page.hasUpdates && <Card className="content-card"><Tag color="warning">有新来源待更新</Tag><Typography.Text> 当前有效来源 {view.page.availableSourceCount} 个，已发布版本冻结来源 {view.page.currentSourceCount} 个。</Typography.Text></Card>}
    <Card className="content-card" title="对象信息"><Descriptions><Descriptions.Item label="类型">{view.page.objectType}</Descriptions.Item><Descriptions.Item label="外部编号">{view.page.externalId}</Descriptions.Item><Descriptions.Item label="名称">{view.page.objectName}</Descriptions.Item></Descriptions></Card>
    <Card className="content-card" title="摘要草稿"><Input value={title} onChange={(event) => setTitle(event.target.value)} placeholder="知识页标题" style={{ marginBottom: 12 }} /><Input.TextArea rows={10} value={summary} onChange={(event) => setSummary(event.target.value)} placeholder="维护管理员摘要；发布后形成不可变版本" /></Card>
    <Card className="content-card" title={`当前可用来源（${view.availableSources.length}）`}><Table rowKey="publicationId" pagination={false} dataSource={view.availableSources} columns={[{ title: '文档', dataIndex: 'documentTitle' }, { title: '版本', dataIndex: 'versionNo', render: (value: number) => `V${value}` }, { title: '状态', dataIndex: 'active', render: (value: boolean) => <Tag color={value ? 'success' : 'default'}>{value ? '有效' : '已停用'}</Tag> }, { title: '发布时间', dataIndex: 'publishedAt', render: (value: string) => new Date(value).toLocaleString('zh-CN') }, { title: '操作', render: (_: unknown, source: KnowledgePageView['availableSources'][number]) => <Button type="link" onClick={() => navigate(`/knowledge/documents/${source.documentId}`)}>查看文档</Button> }]} /></Card>
    <Card className="content-card" title="历史版本"><Space direction="vertical" style={{ width: '100%' }}>{view.versions.map((version) => <Card size="small" key={version.id} title={`V${version.versionNo} · ${version.title}`} extra={new Date(version.publishedAt).toLocaleString('zh-CN')}><Typography.Paragraph>{version.summary || '暂无摘要'}</Typography.Paragraph><Typography.Text type="secondary">冻结来源：{version.sources.length} 个</Typography.Text><div>{version.sources.map((source) => <Tag key={source.publicationId} color={source.active ? 'blue' : 'default'}>{source.documentTitle} V{source.versionNo}{source.active ? '' : '（已停用）'}</Tag>)}</div></Card>)}</Space></Card>
  </div>;
}
