import { ArrowLeftOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { App, Button, Card, Descriptions, Divider, Space, Spin, Table, Tag, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { knowledgeApi, type KnowledgeDocument, type KnowledgeVersion } from '@/services/knowledge';

export function KnowledgeDocumentPage() {
  const { message, modal } = App.useApp();
  const navigate = useNavigate();
  const { id } = useParams<{ id: string }>();
  const [document, setDocument] = useState<KnowledgeDocument>();
  const [versions, setVersions] = useState<KnowledgeVersion[]>([]);
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const load = async () => {
    if (!id) return;
    setLoading(true);
    try { const [item, history] = await Promise.all([knowledgeApi.get(id), knowledgeApi.versions(id)]); setDocument(item); setVersions(history); }
    catch (error) { void message.error(error instanceof Error ? error.message : '文件详情加载失败'); }
    finally { setLoading(false); }
  };
  useEffect(() => { void load(); }, [id]);
  if (loading || !document) return <div className="business-page"><Spin />;</div>;
  const uploadVersion = async (file?: File) => {
    if (!file || !id) return;
    setUploading(true);
    try { await knowledgeApi.uploadVersion(id, file); void message.success('新版本已提交解析'); await load(); }
    catch (error) { void message.error(error instanceof Error ? error.message : '新版本上传失败'); }
    finally { setUploading(false); }
  };
  const grant = () => { modal.confirm({ title: '允许此文件进入 AI 上下文？', content: '授权后，文件内容可随 AI 问答发送到模型网关。', okText: '确认授权', cancelText: '取消', onOk: () => { void (async () => { await knowledgeApi.grant(document.id, 'APPROVE'); void message.success('已授权 AI 使用'); await load(); })(); } }); };
  return <div className="business-page">
    <Space style={{ marginBottom: 16 }}><Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/knowledge/library')}>返回知识库</Button><Typography.Title level={2} style={{ margin: 0 }}>{document.title}</Typography.Title></Space>
    <Card className="content-card" title="文件状态" extra={<Space><input ref={fileInput} hidden type="file" onChange={(event) => { void uploadVersion(event.target.files?.[0]); event.currentTarget.value = ''; }} /><Button loading={uploading} onClick={() => fileInput.current?.click()}>上传新版本</Button>{document.aiStatus !== 'APPROVED' && <Button type="primary" icon={<SafetyCertificateOutlined />} disabled={document.status !== 'READY'} onClick={grant}>授权 AI 使用</Button>}</Space>}>
      <Descriptions column={{ xs: 1, sm: 2, md: 3 }}><Descriptions.Item label="原文件">{document.originalName}</Descriptions.Item><Descriptions.Item label="版本">V{document.currentVersionNo}</Descriptions.Item><Descriptions.Item label="解析状态"><Tag>{document.status}</Tag></Descriptions.Item><Descriptions.Item label="安全扫描"><Tag color={document.scanStatus === 'SAFE' ? 'green' : 'orange'}>{document.scanStatus}</Tag></Descriptions.Item><Descriptions.Item label="AI 使用"><Tag color={document.aiStatus === 'APPROVED' ? 'green' : 'gold'}>{document.aiStatus}</Tag></Descriptions.Item><Descriptions.Item label="SHA-256"><Typography.Text copyable ellipsis style={{ maxWidth: 240 }}>{document.sha256}</Typography.Text></Descriptions.Item></Descriptions>
      {document.parseError && <Typography.Paragraph type="danger">{document.parseError}</Typography.Paragraph>}
    </Card>
    <Card className="content-card" title="原文件预览"><iframe title={document.title} src={knowledgeApi.contentUrl(document.id)} style={{ width: '100%', height: 620, border: '1px solid #e2e8f0', borderRadius: 6 }} /></Card>
    <Card className="content-card" title="版本记录"><Table rowKey="id" dataSource={versions} pagination={false} columns={[{ title: '版本', dataIndex: 'versionNo', render: (value: number) => `V${value}` }, { title: '文件名', dataIndex: 'originalName' }, { title: '状态', dataIndex: 'status' }, { title: '解析器', dataIndex: 'parserVersion' }, { title: '大小', dataIndex: 'size', render: (value: number) => `${(value / 1024 / 1024).toFixed(2)} MB` }]} /></Card>
    <Divider />
  </div>;
}
