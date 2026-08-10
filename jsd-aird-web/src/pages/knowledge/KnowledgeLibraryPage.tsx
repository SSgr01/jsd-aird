import { EyeOutlined, ReloadOutlined, SafetyCertificateOutlined, UploadOutlined } from '@ant-design/icons';
import { App, Button, Card, Input, Select, Space, Table, Tag, Typography, Upload } from 'antd';
import type { UploadFile } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { knowledgeApi, type KnowledgeDocument } from '@/services/knowledge';

const statusLabels: Record<string, [string, string]> = {
  QUEUED: ['排队中', 'blue'], PROCESSING: ['处理中', 'processing'], READY: ['已就绪', 'green'],
  FAILED: ['处理失败', 'red'], REJECTED: ['已拒绝', 'red'], PENDING_PROVIDER: ['等待 OCR/ASR', 'orange'],
};
const aiLabels: Record<string, [string, string]> = {
  PENDING: ['待授权', 'gold'], APPROVED: ['已授权', 'green'], REJECTED: ['已拒绝', 'red'], REVOKED: ['已撤销', 'orange'],
};

export function KnowledgeLibraryPage() {
  const { message, modal } = App.useApp();
  const navigate = useNavigate();
  const [items, setItems] = useState<KnowledgeDocument[]>([]);
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState<string>();
  const [aiStatus, setAiStatus] = useState<string>();
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try { const page = await knowledgeApi.list({ keyword: keyword || undefined, status, aiStatus }); setItems(page.items); }
    catch (error) { void message.error(error instanceof Error ? error.message : '知识库加载失败'); }
    finally { setLoading(false); }
  }, [aiStatus, keyword, message, status]);

  useEffect(() => { void load(); }, [load]);

  const upload = async () => {
    const file = fileList[0]?.originFileObj;
    if (!file) { void message.warning('请选择文件'); return; }
    setLoading(true);
    try { await knowledgeApi.upload(file); setFileList([]); void message.success('文件已进入解析队列'); await load(); }
    catch (error) { void message.error(error instanceof Error ? error.message : '文件上传失败'); }
    finally { setLoading(false); }
  };

  const grant = (item: KnowledgeDocument, action: 'APPROVE' | 'REVOKE') => {
    modal.confirm({
      title: action === 'APPROVE' ? `允许“${item.title}”进入 AI 上下文？` : `撤销“${item.title}”的 AI 使用授权？`,
      content: action === 'APPROVE' ? '文件原文可能被发送到已配置的模型网关，请确认扫描和内容审批结果。' : '撤销后新的 AI 问答不会再使用该文件。',
      okText: action === 'APPROVE' ? '确认授权' : '确认撤销',
      cancelText: '取消',
      onOk: async () => { await knowledgeApi.grant(item.id, action); void message.success('AI 使用状态已更新'); await load(); },
    });
  };

  return <div className="business-page">
    <div className="page-heading"><div><Typography.Title level={2}>研发知识库</Typography.Title><Typography.Text type="secondary">支持 PDF、Office、CSV、TXT；图片和音频等待 OCR/ASR 适配器配置。</Typography.Text></div></div>
    <Card className="content-card filter-card">
      <Space wrap>
        <Upload beforeUpload={() => false} maxCount={1} fileList={fileList} onChange={({ fileList: value }) => setFileList(value)} accept=".pdf,.docx,.xlsx,.xls,.pptx,.ppt,.csv,.txt,.md">
          <Button icon={<UploadOutlined />}>选择研发文件</Button>
        </Upload>
        <Button type="primary" loading={loading} disabled={!fileList.length} onClick={() => void upload()}>上传并解析</Button>
        <Input.Search allowClear placeholder="文件名或标题" value={keyword} onChange={(event) => setKeyword(event.target.value)} onSearch={() => void load()} style={{ width: 240 }} />
        <Select allowClear placeholder="处理状态" value={status} onChange={setStatus} style={{ width: 140 }} options={Object.entries(statusLabels).map(([value, [label]]) => ({ value, label }))} />
        <Select allowClear placeholder="AI 使用状态" value={aiStatus} onChange={setAiStatus} style={{ width: 140 }} options={Object.entries(aiLabels).map(([value, [label]]) => ({ value, label }))} />
        <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
      </Space>
    </Card>
    <Card className="content-card" styles={{ body: { padding: 0 } }}>
      <Table rowKey="id" loading={loading} dataSource={items} pagination={false} columns={[
        { title: '文件', render: (_: unknown, item: KnowledgeDocument) => <Space direction="vertical" size={0}><Button type="link" style={{ padding: 0 }} onClick={() => navigate(`/knowledge/documents/${item.id}`)}>{item.title}</Button><Typography.Text type="secondary">{item.originalName}</Typography.Text></Space> },
        { title: '类型', dataIndex: 'documentType' },
        { title: '处理状态', dataIndex: 'status', render: (value: string) => <Tag color={statusLabels[value]?.[1]}>{statusLabels[value]?.[0] || value}</Tag> },
        { title: 'AI 使用', dataIndex: 'aiStatus', render: (value: string) => <Tag color={aiLabels[value]?.[1]}>{aiLabels[value]?.[0] || value}</Tag> },
        { title: '扫描', dataIndex: 'scanStatus', render: (value: string) => <Tag color={value === 'SAFE' ? 'green' : 'orange'}>{value}</Tag> },
        { title: '操作', render: (_: unknown, item: KnowledgeDocument) => <Space><Button type="link" icon={<EyeOutlined />} onClick={() => navigate(`/knowledge/documents/${item.id}`)}>详情</Button>{item.aiStatus === 'APPROVED' ? <Button type="link" danger onClick={() => grant(item, 'REVOKE')}>撤销 AI</Button> : <Button type="link" icon={<SafetyCertificateOutlined />} disabled={item.status !== 'READY'} onClick={() => grant(item, 'APPROVE')}>授权 AI</Button>}</Space> },
      ]} />
    </Card>
  </div>;
}
