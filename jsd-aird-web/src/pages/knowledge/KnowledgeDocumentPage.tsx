import { ArrowLeftOutlined, DownloadOutlined, EditOutlined, EyeOutlined, ReloadOutlined, SafetyCertificateOutlined, UploadOutlined } from '@ant-design/icons';
import { Alert, App, Button, Card, Descriptions, Space, Spin, Table, Tag, Typography } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { FilePreviewModal, downloadPreviewFile, type FilePreviewDescriptor } from '@/components/file-preview';
import { stageFile } from '@/services/files';
import { knowledgeApi, type KnowledgeDocument, type KnowledgeReview, type KnowledgeVersion, type Publication } from '@/services/knowledge';

export function KnowledgeDocumentPage() {
  const { message, modal } = App.useApp();
  const navigate = useNavigate();
  const { id } = useParams<{ id: string }>();
  const [document, setDocument] = useState<KnowledgeDocument>();
  const [versions, setVersions] = useState<KnowledgeVersion[]>([]);
  const [review, setReview] = useState<KnowledgeReview>();
  const [publications, setPublications] = useState<Publication[]>([]);
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [previewFile, setPreviewFile] = useState<FilePreviewDescriptor>();
  const fileInput = useRef<HTMLInputElement>(null);

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const [item, history, released] = await Promise.all([knowledgeApi.get(id), knowledgeApi.versions(id), knowledgeApi.publications(id)]);
      setDocument(item); setVersions(history); setPublications(released);
      try { setReview(await knowledgeApi.review(id, item.currentVersionId)); } catch { setReview(undefined); }
    } catch (reason) { void message.error(reason instanceof Error ? reason.message : '文件详情加载失败'); }
    finally { setLoading(false); }
  }, [id, message]);
  useEffect(() => { void load(); }, [load]);
  if (loading || !document) return <div className="business-page"><Spin /></div>;

  const currentPublication = publications.find((item) => item.status === 'CURRENT');
  const previewVersionId = currentPublication?.versionId || document.currentVersionId;
  const previewVersion = versions.find((version) => version.id === previewVersionId);
  const descriptor: FilePreviewDescriptor = { fileName: previewVersion?.originalName || document.originalName, contentType: previewVersion?.contentType || document.contentType, size: previewVersion?.size || document.size, load: () => knowledgeApi.contentBlob(document.id, previewVersionId) };
  const downloadDocument = async () => { try { await downloadPreviewFile(descriptor); void message.success('原文件下载已开始'); } catch (reason) { void message.error(reason instanceof Error ? reason.message : '原文件下载失败'); } };
  const uploadVersion = async (file?: File) => {
    if (!file || !document.categoryId) return;
    setUploading(true);
    try {
      const staged = await stageFile(file, 'KNOWLEDGE');
      const preflight = await knowledgeApi.preflight(staged.fileId, document.categoryId);
      if (preflight.decision === 'EXACT_DUPLICATE') throw new Error(`相同文件已存在：${preflight.exactMatches[0]?.title || file.name}`);
      await knowledgeApi.createGoverned({ fileId: staged.fileId, title: document.title, libraryScope: document.libraryScope, categoryId: document.categoryId, tags: review?.tags || [], resolution: 'NEW_VERSION', targetDocumentId: document.id, sourceInfo: { originalName: file.name, uploadContext: 'document-version' } });
      void message.success('新版本已提交解析；旧发布版继续保持可检索'); await load();
    } catch (reason) { void message.error(reason instanceof Error ? reason.message : '新版本上传失败'); }
    finally { setUploading(false); }
  };
  const reparse = () => {
    if (!review) return;
    modal.confirm({ title: `重新解析 V${review.versionNo}？`, content: '重新解析不会影响当前发布版；新结果需要人工校对并重新发布。', okText: '提交重新解析', onOk: async () => { await knowledgeApi.reparse(review.documentId, review.versionId, review.reviewRevision); void message.success('已提交重新解析'); await load(); } });
  };
  const grant = (action: 'APPROVE' | 'REVOKE') => {
    modal.confirm({
      title: action === 'APPROVE' ? '允许此文档用于 AI 问答？' : '撤销此文档的 AI 授权？',
      content: action === 'APPROVE' ? '授权永久覆盖此文档后续修订和新文件版本，直到主动撤销。' : '将取消待执行向量任务并清除该文档已有向量，关键词检索不受影响。',
      okText: action === 'APPROVE' ? '确认授权' : '确认撤销',
      onOk: async () => { await knowledgeApi.grant(document.id, action); void message.success('AI 授权状态已更新'); await load(); },
    });
  };
  const pendingNewVersion = Boolean(review && (!currentPublication || review.versionId !== currentPublication.versionId));
  return <div className="business-page">
    <div className="business-page-heading"><Space><Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/knowledge/view')}>返回知识库</Button><div><Typography.Title level={2} style={{ margin: 0 }}>{document.title}</Typography.Title><Typography.Text type="secondary">当前文件 V{document.currentVersionNo} · 当前发布 {document.currentPublicationNo ? `#${document.currentPublicationNo}` : '无'}</Typography.Text></div></Space><Space wrap><input ref={fileInput} hidden type="file" onChange={(event) => { void uploadVersion(event.target.files?.[0]); event.currentTarget.value = ''; }} /><Button icon={<UploadOutlined />} loading={uploading} disabled={!document.categoryId} onClick={() => fileInput.current?.click()}>上传新版本</Button>{review && pendingNewVersion && <Button type="primary" onClick={() => navigate(`/knowledge/review/${review.documentId}/${review.versionId}`)}>进入校对</Button>}{review && <Button icon={<ReloadOutlined />} onClick={reparse}>重新解析</Button>}{currentPublication && <Button icon={<EditOutlined />} onClick={() => navigate(`/knowledge/review/${document.id}/${currentPublication.versionId}?mode=revision`)}>修订识别内容</Button>}<Button icon={<EyeOutlined />} onClick={() => setPreviewFile(descriptor)}>预览原文件</Button><Button icon={<DownloadOutlined />} onClick={() => void downloadDocument()}>下载</Button>{document.aiStatus === 'APPROVED' ? <Button danger onClick={() => grant('REVOKE')}>撤销 AI</Button> : <Button type="primary" icon={<SafetyCertificateOutlined />} disabled={!currentPublication} onClick={() => grant('APPROVE')}>授权 AI</Button>}</Space></div>
    {document.lifecycleStatus === 'DISABLED' && <Alert type="warning" showIcon message="此文档已停用" description="当前发布版已退出文件检索和 AI 问答，原文件和发布历史仍保留。" />}
    <Card className="content-card" title="文件状态"><Descriptions column={{ xs: 1, sm: 2, md: 3 }}><Descriptions.Item label="原文件">{document.originalName}</Descriptions.Item><Descriptions.Item label="文件版本">V{document.currentVersionNo}</Descriptions.Item><Descriptions.Item label="生命周期"><Tag color={document.lifecycleStatus === 'ACTIVE' ? 'success' : 'default'}>{document.lifecycleStatus}</Tag></Descriptions.Item><Descriptions.Item label="解析状态"><Tag>{document.status}</Tag></Descriptions.Item><Descriptions.Item label="校对状态"><Tag>{document.reviewStatus}</Tag></Descriptions.Item><Descriptions.Item label="安全扫描"><Tag color={document.scanStatus === 'SAFE' ? 'green' : 'orange'}>{document.scanStatus}</Tag></Descriptions.Item><Descriptions.Item label="AI 使用"><Tag color={document.aiStatus === 'APPROVED' ? 'green' : 'gold'}>{document.aiStatus}</Tag></Descriptions.Item><Descriptions.Item label="SHA-256"><Typography.Text copyable ellipsis style={{ maxWidth: 240 }}>{document.sha256}</Typography.Text></Descriptions.Item><Descriptions.Item label="分类">{document.categoryName || '未分类'}</Descriptions.Item></Descriptions>{document.parseError && <Typography.Paragraph type="danger">{document.parseError}</Typography.Paragraph>}</Card>
    {review && <Card className="content-card" title="标签"><Space wrap>{review.tags.length ? review.tags.map((tag) => <Tag color="cyan" key={tag}>{tag}</Tag>) : <Typography.Text type="secondary">无标签</Typography.Text>}</Space></Card>}
    {review?.reviewStatus === 'PUBLISHED' && <Card className="content-card" title="当前发布内容" extra={currentPublication && <Button icon={<EditOutlined />} onClick={() => navigate(`/knowledge/review/${review.documentId}/${currentPublication.versionId}?mode=revision`)}>修订识别内容</Button>}><div className="knowledge-published-text">{review.blocks.filter((block) => block.reviewStatus !== 'IGNORED').map((block) => <section key={block.id}><Typography.Text type="secondary">{anchorText(block)}</Typography.Text><Typography.Paragraph>{block.confirmedText || block.normalizedText || '（空白）'}</Typography.Paragraph></section>)}</div></Card>}
    <Card className="content-card" title="版本与发布记录"><Table rowKey="id" dataSource={versions} pagination={false} columns={[{ title: '版本', dataIndex: 'versionNo', render: (value: number) => `V${value}` }, { title: '文件名', dataIndex: 'originalName' }, { title: '解析', dataIndex: 'status' }, { title: '校对', dataIndex: 'reviewStatus', render: (value: string) => <Tag>{value}</Tag> }, { title: '发布', render: (_: unknown, version: KnowledgeVersion) => { const publication = publications.find((item) => item.versionId === version.id); return publication ? <Tag color={publication.status === 'CURRENT' ? 'blue' : 'default'}>发布#{publication.publicationNo}</Tag> : '—'; } }, { title: '大小', dataIndex: 'size', render: (value: number) => `${(value / 1024 / 1024).toFixed(2)} MB` }]} /></Card>
    <FilePreviewModal open={Boolean(previewFile)} file={previewFile} onClose={() => setPreviewFile(undefined)} />
  </div>;
}

function anchorText(block: KnowledgeReview['blocks'][number]) {
  if (block.pageNo) return `第 ${block.pageNo} 页`;
  if (block.sheetName) return `${block.sheetName}${block.cellRange ? `!${block.cellRange}` : ''}`;
  if (block.startTimeMs !== undefined) return `${Math.floor(block.startTimeMs / 1000)} 秒`;
  return block.section || `段落 ${block.blockNo + 1}`;
}
