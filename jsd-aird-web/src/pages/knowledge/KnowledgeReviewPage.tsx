import { ArrowLeftOutlined, CheckCircleOutlined, CloseCircleOutlined, EyeOutlined, ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import { Alert, App, Button, Card, Checkbox, Descriptions, Divider, Empty, Form, Input, Modal, Select, Space, Spin, Table, Tag, Typography } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { FilePreviewModal, type FilePreviewDescriptor } from '@/components/file-preview';
import { knowledgeApi, type BusinessObjectRef, type ExtractedField, type KnowledgeCategory, type KnowledgeReview, type ParseBlock } from '@/services/knowledge';

export function KnowledgeReviewPage() {
  const { documentId, versionId } = useParams();
  const { message, modal } = App.useApp();
  const navigate = useNavigate();
  const [review, setReview] = useState<KnowledgeReview>();
  const [categories, setCategories] = useState<KnowledgeCategory[]>([]);
  const [objects, setObjects] = useState<BusinessObjectRef[]>([]);
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
      const next = await knowledgeApi.review(documentId, versionId);
      const [nextCategories, nextObjects] = await Promise.all([knowledgeApi.categories(next.libraryScope), knowledgeApi.businessObjects()]);
      setReview(next); setCategories(nextCategories); setObjects(nextObjects);
    } catch (reason) { void message.error(reason instanceof Error ? reason.message : '审核内容加载失败'); }
    finally { setLoading(false); }
  }, [documentId, message, versionId]);
  useEffect(() => { void load(); }, [load]);
  const descriptor = useMemo<FilePreviewDescriptor | undefined>(() => review ? ({ fileName: review.originalName, contentType: review.contentType, size: review.size, load: () => knowledgeApi.contentBlob(review.documentId, review.versionId) }) : undefined, [review]);
  useEffect(() => {
    let active = true;
    let url: string | undefined;
    if (review?.contentType.startsWith('audio/')) void knowledgeApi.contentBlob(review.documentId, review.versionId).then((blob) => {
      if (!active) return;
      url = URL.createObjectURL(blob); setAudioUrl(url);
    }).catch(() => { if (active) setAudioUrl(undefined); });
    return () => { active = false; if (url) URL.revokeObjectURL(url); };
  }, [review?.contentType, review?.documentId, review?.versionId]);
  const update = (patch: Partial<KnowledgeReview>) => setReview((current) => current ? { ...current, ...patch } : current);
  const updateBlock = (id: string, patch: Partial<ParseBlock>) => setReview((current) => current ? { ...current, blocks: current.blocks.map((item) => item.id === id ? { ...item, ...patch } : item) } : current);
  const updateField = (id: string, patch: Partial<ExtractedField>) => setReview((current) => current ? { ...current, fields: current.fields.map((item) => item.id === id ? { ...item, ...patch } : item) } : current);
  const save = async () => {
    if (!review) return undefined;
    setSaving(true);
    try { const saved = await knowledgeApi.saveReview(review); setReview(saved); void message.success('审核内容已保存'); return saved; }
    catch (reason) { void message.error(reason instanceof Error ? reason.message : '保存失败，请刷新后重试'); return undefined; }
    finally { setSaving(false); }
  };
  const publish = () => {
    if (!review) return;
    modal.confirm({ title: `发布“${review.title}”V${review.versionNo}？`, content: '发布后文件检索将切换到此版本，AI 授权不会从旧版本继承。', okText: '确认发布', onOk: async () => { const saved = await save(); if (!saved) throw new Error('审核内容保存失败'); await knowledgeApi.publish(saved.documentId, saved.versionId, saved.reviewRevision); void message.success('版本已发布'); navigate('/knowledge/review'); } });
  };
  const reparse = async () => {
    if (!review) return;
    try { await knowledgeApi.reparse(review.documentId, review.versionId, review.reviewRevision, review.mediaProcessingConsent); void message.success('已提交重新解析'); await load(); }
    catch (reason) { void message.error(reason instanceof Error ? reason.message : '重新解析失败'); }
  };
  const reject = async () => {
    if (!review || !rejectReason.trim()) return;
    try { await knowledgeApi.reject(review.documentId, review.versionId, review.reviewRevision, rejectReason.trim()); void message.success('已驳回'); setRejectOpen(false); navigate('/knowledge/review'); }
    catch (reason) { void message.error(reason instanceof Error ? reason.message : '驳回失败'); }
  };
  const seekAudio = (milliseconds?: number) => { if (milliseconds === undefined || !audioRef.current) return; audioRef.current.currentTime = milliseconds / 1000; void audioRef.current.play(); };
  if (loading) return <div className="business-page"><Spin /></div>;
  if (!review) return <div className="business-page"><Empty description="审核版本不存在" /></div>;
  const isAudio = review.contentType.startsWith('audio/');
  return <div className="business-page knowledge-review-page">
    <div className="business-page-heading"><Space><Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/knowledge/review')}>返回队列</Button><div><Typography.Title level={2} style={{ margin: 0 }}>{review.title} · V{review.versionNo}</Typography.Title><Typography.Text type="secondary">审核修订 {review.reviewRevision} · {review.parseRun?.provider || '本地解析器'} · {review.parseRun?.parserVersion || '—'}</Typography.Text></div></Space><Space><Button icon={<ReloadOutlined />} onClick={() => void reparse()}>重新解析</Button><Button icon={<SaveOutlined />} loading={saving} onClick={() => void save()}>保存</Button><Button danger icon={<CloseCircleOutlined />} onClick={() => setRejectOpen(true)}>驳回</Button><Button type="primary" icon={<CheckCircleOutlined />} onClick={publish}>发布</Button></Space></div>
    {review.processingStatus !== 'READY' && <Alert type="warning" showIcon message={`当前解析状态：${review.processingStatus}`} description={review.parseRun?.errorMessage || (review.mediaProcessingConsent ? '解析尚未完成' : '需要补充媒体解析外发确认后重新解析')} action={!review.mediaProcessingConsent ? <Checkbox checked={review.mediaProcessingConsent} onChange={(event) => update({ mediaProcessingConsent: event.target.checked })}>同意本文件用于 OCR/ASR 外发解析</Checkbox> : undefined} />}
    <div className="knowledge-review-split">
      <Card className="content-card knowledge-review-source" title="原文件" extra={<Button icon={<EyeOutlined />} onClick={() => setPreviewOpen(true)}>完整预览</Button>}>
        <Descriptions size="small" column={1}><Descriptions.Item label="文件名">{review.originalName}</Descriptions.Item><Descriptions.Item label="类型">{review.contentType}</Descriptions.Item><Descriptions.Item label="大小">{(review.size / 1024 / 1024).toFixed(2)} MB</Descriptions.Item><Descriptions.Item label="来源">{typeof review.sourceInfo.description === 'string' ? review.sourceInfo.description : '—'}</Descriptions.Item></Descriptions>
        {isAudio ? <audio ref={audioRef} controls preload="metadata" src={audioUrl} style={{ width: '100%', marginTop: 16 }} /> : <div className="file-preview-state"><Typography.Text type="secondary">点击“完整预览”查看原文件；右侧文本锚点包含页码、单元格、OCR 坐标或音频时间。</Typography.Text></div>}
      </Card>
      <div className="knowledge-review-form">
        <Card className="content-card" title="文件信息、标签与关联对象">
          <Form layout="vertical"><Form.Item label="文件名称" required><Input value={review.title} onChange={(event) => update({ title: event.target.value })} /></Form.Item><Space align="start" wrap><Form.Item label="文档类型" required><Select style={{ width: 220 }} value={review.documentType} onChange={(value) => update({ documentType: value })} options={documentTypes} /></Form.Item><Form.Item label="资料范围" required><Select style={{ width: 160 }} value={review.libraryScope} onChange={(value) => { update({ libraryScope: value, categoryId: undefined }); void knowledgeApi.categories(value).then(setCategories); }} options={[{ value: 'INTERNAL', label: '内部资料' }, { value: 'EXTERNAL', label: '外部资料' }]} /></Form.Item><Form.Item label="分类" required><Select style={{ width: 220 }} value={review.categoryId} onChange={(value) => update({ categoryId: value })} options={categories.map((item) => ({ value: item.id, label: item.name }))} /></Form.Item></Space><Form.Item label="标签"><Select mode="tags" value={review.tags} onChange={(tags) => update({ tags })} tokenSeparators={[',', '，']} /></Form.Item><Form.Item label="关联对象"><Select mode="multiple" showSearch optionFilterProp="label" value={review.relations.map((item) => item.id)} onChange={(ids) => update({ relations: ids.map((id) => objects.find((item) => item.id === id)).filter(Boolean) as BusinessObjectRef[] })} options={objects.map((item) => ({ value: item.id, label: `${item.objectType} · ${item.externalId} · ${item.name}` }))} /></Form.Item><Checkbox checked={review.mediaProcessingConsent} onChange={(event) => update({ mediaProcessingConsent: event.target.checked })}>确认本文件可发送至已配置的千问 OCR/ASR 服务进行媒体解析</Checkbox></Form>
        </Card>
        <Card className="content-card" title={`解析文本块（${review.blocks.length}）`} extra={<Button size="small" onClick={() => update({ blocks: review.blocks.map((item) => ({ ...item, confirmedText: item.confirmedText ?? item.normalizedText, reviewStatus: 'CONFIRMED' as const })) })}>全部确认</Button>}>
          <Space direction="vertical" style={{ width: '100%' }}>{review.blocks.map((block) => <Card size="small" key={block.id} className={block.confidence !== undefined && block.confidence < 0.8 ? 'parse-block-low-confidence' : undefined} title={<Space><Tag>#{block.blockNo + 1}</Tag><Typography.Text>{anchorText(block)}</Typography.Text>{block.confidence !== undefined && <Tag color={block.confidence < 0.8 ? 'error' : 'success'}>{Math.round(block.confidence * 100)}%</Tag>}</Space>} extra={block.startTimeMs !== undefined && <Button size="small" onClick={() => seekAudio(block.startTimeMs)}>跳转音频</Button>}><Input.TextArea autoSize={{ minRows: 2, maxRows: 10 }} value={block.confirmedText ?? block.normalizedText} onChange={(event) => updateBlock(block.id, { confirmedText: event.target.value })} /><Select style={{ width: 140, marginTop: 8 }} value={block.reviewStatus} onChange={(value) => updateBlock(block.id, { reviewStatus: value })} options={reviewStatuses} /></Card>)}</Space>
        </Card>
        <Card className="content-card" title={`抽取字段（${review.fields.length}）`} extra={<Button size="small" onClick={() => update({ fields: review.fields.map((item) => ({ ...item, confirmedValue: item.confirmedValue ?? item.normalizedValue ?? item.rawValue, reviewStatus: 'CONFIRMED' as const })) })}>确认全部字段</Button>}>
          <Table rowKey="id" pagination={false} dataSource={review.fields} columns={[
            { title: '字段', render: (_: unknown, item: ExtractedField) => <Space>{item.required && <Tag color="error">必填</Tag>}<Typography.Text strong>{item.name}</Typography.Text><Typography.Text type="secondary">{item.code}</Typography.Text></Space> },
            { title: '原始/标准值', render: (_: unknown, item: ExtractedField) => <div><div>{item.rawValue || '—'} {item.sourceUnit}</div><Typography.Text type="secondary">{item.normalizedValue || '—'} {item.standardUnit}</Typography.Text></div> },
            { title: '确认值', render: (_: unknown, item: ExtractedField) => <Input value={item.confirmedValue ?? item.normalizedValue} status={item.conflict ? 'error' : undefined} onChange={(event) => updateField(item.id, { confirmedValue: event.target.value })} /> },
            { title: '置信度/问题', render: (_: unknown, item: ExtractedField) => <Space direction="vertical" size={2}>{item.confidence !== undefined && <Tag color={item.confidence < 0.8 ? 'error' : 'success'}>{Math.round(item.confidence * 100)}%</Tag>}{item.conflict && <Tag color="error">候选冲突</Tag>}</Space> },
            { title: '确认状态', render: (_: unknown, item: ExtractedField) => <Select style={{ width: 120 }} value={item.reviewStatus} onChange={(value) => updateField(item.id, { reviewStatus: value })} options={reviewStatuses} /> },
          ]} />
        </Card>
        <Card className="content-card" title={`解析问题（${review.issues.length}）`}>
          <Table rowKey="id" pagination={false} dataSource={review.issues} columns={[
            { title: '级别', dataIndex: 'severity', render: (value: string) => <Tag color={value === 'BLOCKER' ? 'error' : value === 'WARNING' ? 'warning' : 'default'}>{value}</Tag> },
            { title: '问题', render: (_: unknown, item: KnowledgeReview['issues'][number]) => <div><Typography.Text strong>{item.message}</Typography.Text><div className="binding-path">{item.code}</div></div> },
            { title: '状态', dataIndex: 'status', render: (value: string) => <Tag color={value === 'OPEN' ? 'error' : 'success'}>{value}</Tag> },
            { title: '处理说明', dataIndex: 'resolution', render: (value?: string) => value || '—' },
          ]} />
        </Card>
      </div>
    </div>
    <Divider />
    <Modal open={rejectOpen} title="驳回此版本" okText="确认驳回" okButtonProps={{ danger: true, disabled: !rejectReason.trim() }} onOk={() => void reject()} onCancel={() => setRejectOpen(false)}><Input.TextArea rows={4} value={rejectReason} onChange={(event) => setRejectReason(event.target.value)} placeholder="请输入驳回原因（必填）" /></Modal>
    <FilePreviewModal open={previewOpen} file={descriptor} onClose={() => setPreviewOpen(false)} />
  </div>;
}

const documentTypes = ['COA', 'PRODUCT_INFO', 'TDS', 'SDS', 'EXPERIMENT_REPORT', 'FORMULA_INFO', 'PROCESS_INFO', 'OTHER'].map((value) => ({ value, label: value }));
const reviewStatuses = [{ value: 'PENDING', label: '待确认' }, { value: 'CONFIRMED', label: '已确认' }, { value: 'IGNORED', label: '忽略' }, { value: 'ISSUE', label: '有问题' }];
function anchorText(block: ParseBlock) {
  if (block.pageNo) return `第 ${block.pageNo} 页${block.bbox?.length ? ` · bbox ${block.bbox.join(',')}` : ''}`;
  if (block.sheetName) return `${block.sheetName}${block.cellRange ? `!${block.cellRange}` : ''}`;
  if (block.startTimeMs !== undefined) return `${formatTime(block.startTimeMs)} - ${formatTime(block.endTimeMs || block.startTimeMs)}`;
  return block.section || '正文';
}
function formatTime(milliseconds: number) { const seconds = Math.floor(milliseconds / 1000); return `${Math.floor(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}`; }
