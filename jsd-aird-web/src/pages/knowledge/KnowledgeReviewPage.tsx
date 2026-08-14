import { ArrowLeftOutlined, CheckCircleOutlined, CloseCircleOutlined, EyeOutlined, ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import { Alert, App, Button, Card, Descriptions, Empty, Form, Input, Modal, Select, Space, Spin, Table, Tag, Typography } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';

import { FilePreviewModal, type FilePreviewDescriptor } from '@/components/file-preview';
import { knowledgeApi, type KnowledgeCategory, type KnowledgeReview, type ParseBlock } from '@/services/knowledge';

export function KnowledgeReviewPage() {
  const { documentId, versionId } = useParams();
  const [searchParams] = useSearchParams();
  const revisionMode = searchParams.get('mode') === 'revision';
  const { message, modal } = App.useApp();
  const navigate = useNavigate();
  const [review, setReview] = useState<KnowledgeReview>();
  const [basePublicationId, setBasePublicationId] = useState<string>();
  const [categories, setCategories] = useState<KnowledgeCategory[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [rejectOpen, setRejectOpen] = useState(false);
  const [rejectReason, setRejectReason] = useState('');
  const [previewOpen, setPreviewOpen] = useState(false);
  const [audioUrl, setAudioUrl] = useState<string>();
  const audioRef = useRef<HTMLAudioElement>(null);

  const load = useCallback(async () => {
    if (!documentId || !versionId) return;
    setLoading(true);
    try {
      const [next, item] = await Promise.all([knowledgeApi.review(documentId, versionId), knowledgeApi.get(documentId)]);
      const nextCategories = await knowledgeApi.categories(next.libraryScope);
      setReview(next); setCategories(nextCategories); setBasePublicationId(item.currentPublicationId);
    } catch (reason) { void message.error(reason instanceof Error ? reason.message : '校对内容加载失败'); }
    finally { setLoading(false); }
  }, [documentId, message, versionId]);
  useEffect(() => { void load(); }, [load]);

  const descriptor = useMemo<FilePreviewDescriptor | undefined>(() => review ? ({ fileName: review.originalName, contentType: review.contentType, size: review.size, load: () => knowledgeApi.contentBlob(review.documentId, review.versionId) }) : undefined, [review]);
  useEffect(() => {
    let active = true; let url: string | undefined;
    if (review?.contentType.startsWith('audio/')) void knowledgeApi.contentBlob(review.documentId, review.versionId).then((blob) => { if (!active) return; url = URL.createObjectURL(blob); setAudioUrl(url); }).catch(() => { if (active) setAudioUrl(undefined); });
    return () => { active = false; if (url) URL.revokeObjectURL(url); };
  }, [review?.contentType, review?.documentId, review?.versionId]);

  const update = (patch: Partial<KnowledgeReview>) => setReview((current) => current ? { ...current, ...patch } : current);
  const updateBlock = (id: string, patch: Partial<ParseBlock>) => setReview((current) => current ? { ...current, blocks: current.blocks.map((item) => item.id === id ? { ...item, ...patch } : item) } : current);
  const saveDraft = async () => {
    if (!review) return undefined;
    setSaving(true);
    try { const saved = await knowledgeApi.saveReview(review); setReview(saved); void message.success('整篇校对内容已保存'); return saved; }
    catch (reason) { void message.error(reason instanceof Error ? reason.message : '保存失败，请刷新后重试'); return undefined; }
    finally { setSaving(false); }
  };
  const publish = () => {
    if (!review) return;
    modal.confirm({
      title: `确认并发布“${review.title}”V${review.versionNo}？`,
      content: '系统将按确认文本重新切块并异步建立关键词索引；索引完成前内容不会进入搜索。', okText: '确认并发布',
      onOk: async () => {
        setSaving(true);
        try {
          const saved = await knowledgeApi.saveReview(review, true);
          await knowledgeApi.publish(saved.documentId, saved.versionId, saved.reviewRevision);
          void message.success('已提交索引构建，完成后将自动发布'); navigate('/knowledge/review');
        } finally { setSaving(false); }
      },
    });
  };
  const applyRevision = () => {
    if (!review || !basePublicationId) return;
    modal.confirm({
      title: '保存并应用本次修订？',
      content: '系统会生成新的解析运行和发布号。新索引全部成功前，旧发布版继续提供检索。', okText: '保存并应用',
      onOk: async () => {
        setSaving(true);
        try { await knowledgeApi.revise(review, basePublicationId); void message.success('修订已进入索引构建，旧发布版继续可用'); navigate(`/knowledge/documents/${review.documentId}`); }
        finally { setSaving(false); }
      },
    });
  };
  const reparse = async () => {
    if (!review) return;
    try { await knowledgeApi.reparse(review.documentId, review.versionId, review.reviewRevision); void message.success('已提交重新解析'); await load(); }
    catch (reason) { void message.error(reason instanceof Error ? reason.message : '重新解析失败'); }
  };
  const reject = async () => {
    if (!review || !rejectReason.trim()) return;
    try { await knowledgeApi.reject(review.documentId, review.versionId, review.reviewRevision, rejectReason.trim()); void message.success('已驳回'); setRejectOpen(false); navigate('/knowledge/review'); }
    catch (reason) { void message.error(reason instanceof Error ? reason.message : '驳回失败'); }
  };
  const seekAudio = (milliseconds?: number) => { if (milliseconds === undefined || !audioRef.current) return; audioRef.current.currentTime = milliseconds / 1000; void audioRef.current.play(); };

  if (loading) return <div className="business-page"><Spin /></div>;
  if (!review) return <div className="business-page"><Empty description="校对版本不存在" /></div>;
  const isAudio = review.contentType.startsWith('audio/');
  const indexing = review.parseRun?.status === 'INDEXING';
  const processingUnavailable = review.processingStatus !== 'READY';
  const processingFailed = review.processingStatus === 'FAILED' || review.parseRun?.status === 'FAILED';
  return <div className="business-page knowledge-review-page">
    <div className="business-page-heading">
      <Space><Button icon={<ArrowLeftOutlined />} onClick={() => navigate(revisionMode ? `/knowledge/documents/${review.documentId}` : '/knowledge/review')}>返回</Button><div><Typography.Title level={2} style={{ margin: 0 }}>{revisionMode ? '修订识别内容' : '内容校对'} · {review.title}</Typography.Title><Typography.Text type="secondary">V{review.versionNo} · 修订 {review.reviewRevision} · 一次保存整篇内容</Typography.Text></div></Space>
      <Space wrap>{!revisionMode && <Button icon={<ReloadOutlined />} disabled={indexing} onClick={() => void reparse()}>重新解析</Button>}{!revisionMode && <Button icon={<SaveOutlined />} loading={saving} disabled={indexing} onClick={() => void saveDraft()}>保存草稿</Button>}{!revisionMode && <Button danger icon={<CloseCircleOutlined />} disabled={indexing} onClick={() => setRejectOpen(true)}>驳回</Button>}{revisionMode ? <Button type="primary" icon={<CheckCircleOutlined />} loading={saving} disabled={!basePublicationId || indexing} onClick={applyRevision}>保存并应用修订</Button> : <Button type="primary" icon={<CheckCircleOutlined />} loading={saving} disabled={indexing} onClick={publish}>确认并发布</Button>}</Space>
    </div>
    {(processingUnavailable || indexing || processingFailed) && <Alert type={processingFailed ? 'error' : 'info'} showIcon message={indexing ? '新发布正在构建索引' : processingFailed ? '处理失败，可修正后重试' : '内容仍在处理中'} description={review.parseRun?.errorMessage || (indexing ? '完成前旧发布版继续提供检索，请稍后刷新。' : '解析服务暂不可用或任务尚未完成，请稍后重试。')} />}
    {revisionMode && <Alert type="info" showIcon message="旧发布版持续可用" description="本次修订会在关键词索引，以及已授权文档所需的向量全部成功后一次切换。" />}
    <div className="knowledge-review-split">
      <Card className="content-card knowledge-review-source" title="原文件" extra={<Button icon={<EyeOutlined />} onClick={() => setPreviewOpen(true)}>完整预览</Button>}>
        <Descriptions size="small" column={1}><Descriptions.Item label="文件名">{review.originalName}</Descriptions.Item><Descriptions.Item label="类型">{review.contentType}</Descriptions.Item><Descriptions.Item label="大小">{(review.size / 1024 / 1024).toFixed(2)} MB</Descriptions.Item><Descriptions.Item label="来源">{typeof review.sourceInfo.description === 'string' ? review.sourceInfo.description : '—'}</Descriptions.Item></Descriptions>
        {isAudio ? <audio ref={audioRef} controls preload="metadata" src={audioUrl} style={{ width: '100%', marginTop: 16 }} /> : <div className="file-preview-state"><Typography.Text type="secondary">锚点会保留页码、表格单元格、图片区域或音频时间；修改文本不会丢失来源定位。</Typography.Text></div>}
      </Card>
      <div className="knowledge-review-form">
        {!revisionMode && <Card className="content-card" title="文件信息">
          <Form layout="vertical"><Form.Item label="文件名称" required><Input value={review.title} onChange={(event) => update({ title: event.target.value })} /></Form.Item><Space align="start" wrap><Form.Item label="资料范围" required><Select style={{ width: 160 }} value={review.libraryScope} onChange={(value) => { update({ libraryScope: value, categoryId: undefined }); void knowledgeApi.categories(value).then(setCategories); }} options={[{ value: 'INTERNAL', label: '内部资料' }, { value: 'EXTERNAL', label: '外部资料' }]} /></Form.Item><Form.Item label="分类" required><Select style={{ width: 240 }} value={review.categoryId} onChange={(value) => update({ categoryId: value })} options={categories.map((item) => ({ value: item.id, label: item.name }))} /></Form.Item></Space><Form.Item label="标签"><Select mode="tags" value={review.tags} onChange={(tags) => update({ tags })} tokenSeparators={[',', '，']} /></Form.Item></Form>
        </Card>}
        <Card className="content-card knowledge-continuous-card" title="识别内容" extra={<Typography.Text type="secondary">{review.blocks.filter((item) => item.reviewStatus !== 'IGNORED').length} 段纳入发布</Typography.Text>}>
          <div className="knowledge-continuous-editor">{review.blocks.map((block) => {
            const ignored = block.reviewStatus === 'IGNORED';
            return <section key={block.id} className={`knowledge-continuous-block${ignored ? ' is-ignored' : ''}`}>
              <div className="knowledge-continuous-anchor"><Space size={6}><Typography.Text type="secondary">{anchorText(block)}</Typography.Text>{block.confidence !== undefined && block.confidence < 0.8 && <Tag color="warning">请核对</Tag>}{block.startTimeMs !== undefined && <Button type="link" size="small" onClick={() => seekAudio(block.startTimeMs)}>播放此处</Button>}</Space><Button type="link" size="small" danger={!ignored} onClick={() => updateBlock(block.id, { reviewStatus: ignored ? 'PENDING' : 'IGNORED' })}>{ignored ? '恢复' : '排除此段'}</Button></div>
              <Input.TextArea bordered={false} autoSize={{ minRows: 1, maxRows: 20 }} disabled={ignored} value={block.confirmedText ?? block.normalizedText} onChange={(event) => updateBlock(block.id, { confirmedText: event.target.value, reviewStatus: 'PENDING' })} />
            </section>;
          })}</div>
        </Card>
        {review.issues.length > 0 && <Card className="content-card" title={`解析提示（${review.issues.length}）`}><Table rowKey="id" size="small" pagination={false} dataSource={review.issues} columns={[{ title: '级别', dataIndex: 'severity', width: 90, render: (value: string) => <Tag color={value === 'BLOCKER' ? 'error' : value === 'WARNING' ? 'warning' : 'default'}>{value}</Tag> }, { title: '说明', dataIndex: 'message' }, { title: '状态', dataIndex: 'status', width: 100, render: (value: string) => <Tag>{value}</Tag> }]} /></Card>}
      </div>
    </div>
    <Modal open={rejectOpen} title="驳回此版本" okText="确认驳回" okButtonProps={{ danger: true, disabled: !rejectReason.trim() }} onOk={() => void reject()} onCancel={() => setRejectOpen(false)}><Input.TextArea rows={4} value={rejectReason} onChange={(event) => setRejectReason(event.target.value)} placeholder="请输入驳回原因（必填）" /></Modal>
    <FilePreviewModal open={previewOpen} file={descriptor} onClose={() => setPreviewOpen(false)} />
  </div>;
}

function anchorText(block: ParseBlock) {
  if (block.pageNo) return `第 ${block.pageNo} 页${block.bbox?.length ? ' · 图片区域' : ''}`;
  if (block.sheetName) return `${block.sheetName}${block.cellRange ? `!${block.cellRange}` : ''}`;
  if (block.startTimeMs !== undefined) return `${formatTime(block.startTimeMs)} - ${formatTime(block.endTimeMs || block.startTimeMs)}`;
  return block.section || `段落 ${block.blockNo + 1}`;
}
function formatTime(milliseconds: number) { const seconds = Math.floor(milliseconds / 1000); return `${Math.floor(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}`; }
