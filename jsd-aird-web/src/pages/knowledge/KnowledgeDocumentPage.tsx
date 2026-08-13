import { ArrowLeftOutlined, AuditOutlined, DownloadOutlined, EyeOutlined, ReloadOutlined, SafetyCertificateOutlined, UploadOutlined } from '@ant-design/icons';
import { Alert, App, Button, Card, Descriptions, Space, Spin, Table, Tag, Timeline, Typography } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { FilePreviewModal, downloadPreviewFile, type FilePreviewDescriptor } from '@/components/file-preview';
import { stageFile } from '@/services/files';
import { knowledgeApi, type AuditEntry, type ExtractedField, type KnowledgeDocument, type KnowledgeReview, type KnowledgeVersion, type Publication } from '@/services/knowledge';

export function KnowledgeDocumentPage() {
  const { message, modal } = App.useApp();
  const navigate = useNavigate();
  const { id } = useParams<{ id: string }>();
  const [document, setDocument] = useState<KnowledgeDocument>();
  const [versions, setVersions] = useState<KnowledgeVersion[]>([]);
  const [review, setReview] = useState<KnowledgeReview>();
  const [publications, setPublications] = useState<Publication[]>([]);
  const [audit, setAudit] = useState<AuditEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [previewFile, setPreviewFile] = useState<FilePreviewDescriptor>();
  const fileInput = useRef<HTMLInputElement>(null);
  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const [item, history, released, trail] = await Promise.all([knowledgeApi.get(id), knowledgeApi.versions(id), knowledgeApi.publications(id), knowledgeApi.audit(id)]);
      setDocument(item); setVersions(history); setPublications(released); setAudit(trail);
      try { setReview(await knowledgeApi.review(id, item.currentVersionId)); } catch { setReview(undefined); }
    } catch (reason) { void message.error(reason instanceof Error ? reason.message : '文件详情加载失败'); }
    finally { setLoading(false); }
  }, [id, message]);
  useEffect(() => { void load(); }, [load]);
  if (loading || !document) return <div className="business-page"><Spin /></div>;
  const currentPublication = publications.find((item) => item.status === 'CURRENT');
  const previewVersionId = currentPublication?.versionId || document.currentVersionId;
  const previewVersion = versions.find((version) => version.id === previewVersionId);
  const fileDescriptor: FilePreviewDescriptor = { fileName: previewVersion?.originalName || document.originalName, contentType: previewVersion?.contentType || document.contentType, size: previewVersion?.size || document.size, load: () => knowledgeApi.contentBlob(document.id, previewVersionId) };
  const downloadDocument = async () => { try { await downloadPreviewFile(fileDescriptor); void message.success('原文件下载已开始'); } catch (reason) { void message.error(reason instanceof Error ? reason.message : '原文件下载失败'); } };
  const uploadVersion = async (file?: File) => {
    if (!file || !id || !document.categoryId) return;
    setUploading(true);
    try {
      const staged = await stageFile(file, 'KNOWLEDGE');
      const preflight = await knowledgeApi.preflight(staged.fileId, document.documentType, review?.relations.map((item) => item.id) || []);
      if (preflight.decision === 'EXACT_DUPLICATE') throw new Error(`相同文件已存在：${preflight.exactMatches[0]?.title || file.name}`);
      await knowledgeApi.createGoverned({ fileId: staged.fileId, title: document.title, documentType: document.documentType, libraryScope: document.libraryScope, categoryId: document.categoryId, tags: review?.tags || [], objectRefIds: review?.relations.map((item) => item.id) || [], mediaProcessingConsent: false, resolution: 'NEW_VERSION', targetDocumentId: document.id, sourceInfo: { originalName: file.name, uploadContext: 'document-version' } });
      void message.success('新版本已提交解析；旧发布版继续保持可检索'); await load();
    } catch (reason) { void message.error(reason instanceof Error ? reason.message : '新版本上传失败'); }
    finally { setUploading(false); }
  };
  const reparse = () => {
    if (!review) return;
    modal.confirm({ title: `重新解析 V${review.versionNo}？`, content: '将生成新的不可变解析运行；当前发布版继续使用原解析结果，直到新结果重新审核发布。', okText: '提交重新解析', onOk: async () => { await knowledgeApi.reparse(review.documentId, review.versionId, review.reviewRevision, review.mediaProcessingConsent); void message.success('已提交重新解析'); await load(); } });
  };
  const grant = (action: 'APPROVE' | 'REVOKE') => {
    if (!currentPublication) return;
    modal.confirm({ title: action === 'APPROVE' ? '允许当前发布版本进入 AI 上下文？' : '撤销当前发布版本的 AI 授权？', content: `授权对象仅为发布#${currentPublication.publicationNo}，后续新版本不会继承。`, okText: action === 'APPROVE' ? '确认授权' : '确认撤销', onOk: async () => { await knowledgeApi.publicationAiUsage(currentPublication.id, action); await load(); } });
  };
  return <div className="business-page">
    <div className="business-page-heading"><Space><Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/knowledge/view')}>返回知识库</Button><div><Typography.Title level={2} style={{ margin: 0 }}>{document.title}</Typography.Title><Typography.Text type="secondary">逻辑文档 · 当前草稿 V{document.currentVersionNo} · 当前发布 {document.currentPublicationNo ? `#${document.currentPublicationNo}` : '无'}</Typography.Text></div></Space><Space wrap><input ref={fileInput} hidden type="file" onChange={(event) => { void uploadVersion(event.target.files?.[0]); event.currentTarget.value = ''; }} /><Button icon={<UploadOutlined />} loading={uploading} disabled={!document.categoryId} onClick={() => fileInput.current?.click()}>上传新版本</Button>{review && <Button icon={<ReloadOutlined />} onClick={reparse}>重新解析 V{review.versionNo}</Button>}<Button icon={<EyeOutlined />} onClick={() => setPreviewFile(fileDescriptor)}>预览原文件</Button><Button icon={<DownloadOutlined />} onClick={() => void downloadDocument()}>下载</Button>{currentPublication && (currentPublication.aiStatus === 'APPROVED' ? <Button danger onClick={() => grant('REVOKE')}>撤销 AI</Button> : <Button type="primary" icon={<SafetyCertificateOutlined />} onClick={() => grant('APPROVE')}>授权当前发布版 AI 使用</Button>)}</Space></div>
    {document.lifecycleStatus === 'DISABLED' && <Alert type="warning" showIcon message="此文档已停用" description="当前发布版已退出文件检索和 AI 问答，历史发布与知识页来源快照仍保留。" />}
    <Card className="content-card" title="文件状态"><Descriptions column={{ xs: 1, sm: 2, md: 3 }}><Descriptions.Item label="原文件">{document.originalName}</Descriptions.Item><Descriptions.Item label="草稿版本">V{document.currentVersionNo}</Descriptions.Item><Descriptions.Item label="生命周期"><Tag color={document.lifecycleStatus === 'ACTIVE' ? 'success' : 'default'}>{document.lifecycleStatus}</Tag></Descriptions.Item><Descriptions.Item label="解析状态"><Tag>{document.status}</Tag></Descriptions.Item><Descriptions.Item label="审核状态"><Tag>{document.reviewStatus}</Tag></Descriptions.Item><Descriptions.Item label="安全扫描"><Tag color={document.scanStatus === 'SAFE' ? 'green' : 'orange'}>{document.scanStatus}</Tag></Descriptions.Item><Descriptions.Item label="AI 使用"><Tag color={currentPublication?.aiStatus === 'APPROVED' ? 'green' : 'gold'}>{currentPublication?.aiStatus || 'PENDING'}</Tag></Descriptions.Item><Descriptions.Item label="SHA-256"><Typography.Text copyable ellipsis style={{ maxWidth: 240 }}>{document.sha256}</Typography.Text></Descriptions.Item><Descriptions.Item label="分类">{document.categoryName || '未分类'}</Descriptions.Item></Descriptions>{document.parseError && <Typography.Paragraph type="danger">{document.parseError}</Typography.Paragraph>}</Card>
    {review && <><Card className="content-card" title="标签与关联对象"><Space wrap>{review.tags.length ? review.tags.map((tag) => <Tag color="cyan" key={tag}>{tag}</Tag>) : <Typography.Text type="secondary">无标签</Typography.Text>}{review.relations.map((item) => <Tag key={item.id}>{item.objectType} · {item.externalId} · {item.name}</Tag>)}</Space></Card><Card className="content-card" title={`当前版本解析结果 · Run ${review.parseRun?.runNo || '—'}`} extra={(review.reviewStatus === 'PENDING_REVIEW' || review.reviewStatus === 'REJECTED') && <Button type="primary" onClick={() => navigate(`/knowledge/review/${review.documentId}/${review.versionId}`)}>进入审核</Button>}><Typography.Paragraph type="secondary">解析器：{review.parseRun?.provider || '—'} / {review.parseRun?.parserVersion || '—'}{review.parseRun?.providerTaskId ? ` · 提供方任务 ${review.parseRun.providerTaskId}` : ''}</Typography.Paragraph><Table rowKey="id" size="small" pagination={{ pageSize: 10 }} dataSource={review.blocks} columns={[{ title: '锚点', width: 180, render: (_: unknown, item: KnowledgeReview['blocks'][number]) => item.pageNo ? `第${item.pageNo}页` : item.sheetName ? `${item.sheetName}!${item.cellRange || ''}` : item.startTimeMs !== undefined ? `${(item.startTimeMs / 1000).toFixed(1)}s` : item.section }, { title: '确认文本', render: (_: unknown, item: KnowledgeReview['blocks'][number]) => item.confirmedText || item.normalizedText }, { title: '置信度', dataIndex: 'confidence', width: 90, render: (value?: number) => value === undefined ? '—' : `${Math.round(value * 100)}%` }, { title: '状态', dataIndex: 'reviewStatus', width: 100, render: (value: string) => <Tag>{value}</Tag> }]} /><Typography.Title level={5}>抽取字段</Typography.Title><Table rowKey="id" size="small" pagination={false} dataSource={review.fields} columns={[{ title: '字段', render: (_: unknown, item: ExtractedField) => <Space>{item.required && <Tag color="error">必填</Tag>}{item.name}<Typography.Text type="secondary">{item.code}</Typography.Text></Space> }, { title: '确认值', render: (_: unknown, item: ExtractedField) => item.confirmedValue || item.normalizedValue || item.rawValue || '—' }, { title: '单位', render: (_: unknown, item: ExtractedField) => item.standardUnit || item.sourceUnit || '—' }, { title: '问题', render: (_: unknown, item: ExtractedField) => item.conflict ? <Tag color="error">冲突</Tag> : item.confidence !== undefined && item.confidence < 0.8 ? <Tag color="warning">低置信度</Tag> : <Tag color="success">正常</Tag> }, { title: '状态', dataIndex: 'reviewStatus' }]} /></Card></>}
    <Card className="content-card" title="版本与发布记录"><Table rowKey="id" dataSource={versions} pagination={false} columns={[{ title: '版本', dataIndex: 'versionNo', render: (value: number) => `V${value}` }, { title: '文件名', dataIndex: 'originalName' }, { title: '解析', dataIndex: 'status' }, { title: '审核', dataIndex: 'reviewStatus', render: (value: string) => <Tag>{value}</Tag> }, { title: '媒体确认', dataIndex: 'mediaProcessingConsent', render: (value: boolean) => value ? '已确认' : '未确认' }, { title: '发布', render: (_: unknown, version: KnowledgeVersion) => { const publication = publications.find((item) => item.versionId === version.id); return publication ? <Space><Tag color={publication.status === 'CURRENT' ? 'blue' : 'default'}>发布#{publication.publicationNo}</Tag><Tag>{publication.aiStatus}</Tag></Space> : '—'; } }, { title: '大小', dataIndex: 'size', render: (value: number) => `${(value / 1024 / 1024).toFixed(2)} MB` }]} /></Card>
    <Card className="content-card" title={<span><AuditOutlined /> 操作审计</span>}><Timeline items={audit.map((item) => ({ color: item.action.includes('REJECT') || item.action.includes('DISABLED') ? 'red' : item.action.includes('PUBLISHED') ? 'green' : 'blue', children: <div><Typography.Text strong>{auditLabel(item.action)}</Typography.Text><div className="binding-path">{new Date(item.createdAt).toLocaleString('zh-CN')} · 操作者 {item.actorId}</div><Typography.Text type="secondary">{JSON.stringify(item.detail)}</Typography.Text></div> }))} /></Card>
    <FilePreviewModal open={Boolean(previewFile)} file={previewFile} onClose={() => setPreviewFile(undefined)} />
  </div>;
}

function auditLabel(action: string) { return ({ KB_DOCUMENT_CREATED: '创建文档', KB_DOCUMENT_VERSION_CREATED: '上传新版本', KB_REVIEW_SAVED: '保存审核', KB_DOCUMENT_PUBLISHED: '发布版本', KB_DOCUMENT_REJECTED: '驳回版本', KB_DOCUMENT_REPARSE_REQUESTED: '请求重新解析', KB_DOCUMENT_DISABLED: '停用文档', KB_DOCUMENT_RESTORED: '恢复文档', KB_AI_GRANT_APPROVE: '授权 AI 使用', KB_AI_GRANT_REVOKE: '撤销 AI 授权' } as Record<string, string>)[action] || action; }
