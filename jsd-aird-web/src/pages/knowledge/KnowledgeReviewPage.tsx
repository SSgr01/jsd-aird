import { ArrowLeftOutlined, CheckCircleOutlined, ExclamationCircleOutlined, SaveOutlined } from '@ant-design/icons';
import { Alert, App, Button, Checkbox, Empty, Input, Modal, Space, Spin, Tag, Typography } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';

import { DocumentComparisonViewer, OriginalDocumentViewer, StructuredDocumentEditor, VirtualDataTable, anchorLabel, findNodeByReviewId, sourceNodeMap } from '@/components/knowledge-document';
import { usePermission } from '@/components/auth/usePermission';
import { fetchFilePreviewBlob } from '@/services/files';
import { knowledgeApi, type IssueAction, type KnowledgeReview, type StructuredDocument } from '@/services/knowledge';

type SaveState = 'saved' | 'dirty' | 'saving' | 'offline' | 'conflict';

export function KnowledgeReviewPage() {
  const { documentId, versionId } = useParams(); const [searchParams] = useSearchParams(); const revisionMode = searchParams.get('mode') === 'revision';
  const { message, modal } = App.useApp(); const navigate = useNavigate();
  const canAuthorizeAi = usePermission('ai.external');
  const [review, setReview] = useState<KnowledgeReview>(); const latestReview = useRef<KnowledgeReview>();
  const [loading, setLoading] = useState(true); const [saveState, setSaveState] = useState<SaveState>('saved');
  const [selectedReviewNodeId, setSelectedReviewNodeId] = useState<string>(); const [selectedSourceNodeKey, setSelectedSourceNodeKey] = useState<string>();
  const [rejectOpen, setRejectOpen] = useState(false); const [rejectReason, setRejectReason] = useState('');
  const [issueActions, setIssueActions] = useState<IssueAction[]>([]); const savePromise = useRef<Promise<KnowledgeReview | undefined>>(); const dirty = useRef(false); const revisionInitialized = useRef(false);

  const load = useCallback(async () => {
    if (!documentId || !versionId) return; setLoading(true);
    try {
      let next = await knowledgeApi.review(documentId, versionId);
      const item = await knowledgeApi.get(documentId);
      if (revisionMode && item.currentPublicationId && !revisionInitialized.current
          && (!next.reviewRevision || next.reviewRevision.status === 'PUBLISHED' || next.reviewRevision.status === 'FAILED' || next.reviewRevision.basePublicationId !== item.currentPublicationId)) {
        revisionInitialized.current = true; next = await knowledgeApi.createRevision(documentId, item.currentPublicationId);
      }
      setReview(next); latestReview.current = next; setSaveState('saved');
    } catch (reason) { void message.error(reason instanceof Error ? reason.message : '校对内容加载失败'); }
    finally { setLoading(false); }
  }, [documentId, message, revisionMode, versionId]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => { latestReview.current = review; }, [review]);

  const persist = useCallback(async (): Promise<KnowledgeReview | undefined> => {
    if (savePromise.current) { await savePromise.current; if (dirty.current) return persist(); return latestReview.current; }
    const snapshot = latestReview.current; if (!snapshot?.reviewRevision || !dirty.current) return snapshot;
    dirty.current = false; setSaveState('saving');
    const request = knowledgeApi.saveReview(snapshot, issueActions).then((saved) => {
      setIssueActions([]); setReview((current) => {
        if (!current?.reviewRevision || current.reviewRevision.id !== saved.reviewRevision?.id || !dirty.current) return saved;
        return { ...saved, title: current.title, libraryScope: current.libraryScope, categoryId: current.categoryId, tags: current.tags, reviewRevision: saved.reviewRevision ? { ...saved.reviewRevision, confirmedDocument: current.reviewRevision.confirmedDocument, excludedReviewNodeIds: current.reviewRevision.excludedReviewNodeIds } : saved.reviewRevision };
      });
      latestReview.current = dirty.current && latestReview.current?.reviewRevision && saved.reviewRevision
        ? { ...saved, reviewRevision: { ...saved.reviewRevision, confirmedDocument: latestReview.current.reviewRevision.confirmedDocument, excludedReviewNodeIds: latestReview.current.reviewRevision.excludedReviewNodeIds } } : saved;
      setSaveState(dirty.current ? 'dirty' : 'saved'); return saved;
    }).catch((reason) => { dirty.current = true; const conflict = reason instanceof Error && /409|其他用户|刷新/.test(reason.message); setSaveState(conflict ? 'conflict' : 'offline'); if (conflict) void message.error('内容已被其他人更新，自动保存已停止，请重新加载'); return undefined; }).finally(() => { savePromise.current = undefined; });
    savePromise.current = request; return request;
  }, [issueActions, message]);

  useEffect(() => {
    if (!review?.reviewRevision || !dirty.current || saveState === 'conflict') return;
    const bytes = new TextEncoder().encode(JSON.stringify(review.reviewRevision.confirmedDocument)).byteLength;
    const timer = window.setTimeout(() => { void persist(); }, bytes > 1024 * 1024 ? 10_000 : 2_000);
    return () => window.clearTimeout(timer);
  }, [persist, review?.reviewRevision?.confirmedDocument, review?.reviewRevision?.excludedReviewNodeIds, review?.title, review?.categoryId, review?.tags, saveState]);

  const edit = (updater: (current: KnowledgeReview) => KnowledgeReview) => { dirty.current = true; setSaveState('dirty'); setReview((current) => { if (!current) return current; const next = updater(current); latestReview.current = next; return next; }); };
  const updateLockVersion = (lockVersion: number) => setReview((current) => {
    if (!current?.reviewRevision) return current;
    const next = { ...current, reviewRevision: { ...current.reviewRevision, lockVersion } };
    latestReview.current = next;
    return next;
  });
  const updateDocument = (document: StructuredDocument) => edit((current) => current.reviewRevision ? { ...current, reviewRevision: { ...current.reviewRevision, confirmedDocument: document } } : current);
  const toggleExcluded = () => { if (!selectedReviewNodeId) return; edit((current) => { if (!current.reviewRevision) return current; const values = new Set(current.reviewRevision.excludedReviewNodeIds); if (values.has(selectedReviewNodeId)) values.delete(selectedReviewNodeId); else values.add(selectedReviewNodeId); return { ...current, reviewRevision: { ...current.reviewRevision, excludedReviewNodeIds: [...values] } }; }); };
  const forceSave = async () => { if (dirty.current) return persist(); return latestReview.current; };
  const publish = () => { const current = latestReview.current; if (!current) return; let authorizeAi = canAuthorizeAi; modal.confirm({ title: revisionMode ? '保存并应用本次修订？' : `确认并发布“${current.title}”V${current.versionNo}？`, content: <Space direction="vertical"><Typography.Text>确认后将发布当前校对内容。</Typography.Text>{canAuthorizeAi && <Checkbox defaultChecked onChange={(event) => { authorizeAi = event.target.checked; }}>发布后授权 AI 使用</Checkbox>}</Space>, okText: revisionMode ? '保存并应用' : '确认并发布', onOk: async () => { const saved = await forceSave(); if (!saved) return; await knowledgeApi.publish(saved, authorizeAi); void message.success(authorizeAi ? '已提交发布，完成后将自动授权 AI' : '已提交发布，请稍候'); navigate(`/knowledge/documents/${saved.documentId}`); } }); };
  const reparse = () => {
    const current = latestReview.current; if (!current) return;
    modal.confirm({ title: '重新解析原文件？', content: <Typography.Paragraph type="secondary">系统将使用当前统一解析策略创建新的解析执行和校对草稿，当前发布内容不受影响。</Typography.Paragraph>, okText: '重新解析', onOk: async () => { const saved = await forceSave(); if (!saved) return; await knowledgeApi.reparse(saved); void message.success('已提交重新解析'); await load(); } });
  };
  const reject = async () => { const current = await forceSave(); if (!current || !rejectReason.trim()) return; await knowledgeApi.reject(current, rejectReason.trim()); void message.success('已驳回'); navigate('/knowledge/view?reviewStatus=PENDING_REVIEW'); };
  const unresolved = review?.issues.filter((item) => item.status === 'OPEN') || [];
  const jumpIssue = (direction: number) => { if (!unresolved.length) return; const current = unresolved.findIndex((item) => item.sourceNodeKeys.includes(selectedSourceNodeKey || '')); const nextIssue = unresolved[(current + direction + unresolved.length) % unresolved.length]; if (nextIssue) setSelectedSourceNodeKey(nextIssue.sourceNodeKeys[0]); };
  const resolveCurrentIssue = () => { const issue = unresolved.find((item) => item.sourceNodeKeys.includes(selectedSourceNodeKey || '')); if (!issue) return; setIssueActions((current) => [...current.filter((item) => item.issueId !== issue.id), { issueId: issue.id, status: 'RESOLVED', resolution: '人工校对确认' }]); dirty.current = true; setSaveState('dirty'); };

  const loadOriginal = useCallback(() => {
    const current = latestReview.current; if (!current) return Promise.reject(new Error('文档未加载'));
    if (/\.doc$/i.test(current.originalName) && current.fileObjectId) return fetchFilePreviewBlob(current.fileObjectId);
    return knowledgeApi.contentBlob(current.documentId, current.versionId);
  }, []);
  const sourceMap = useMemo(() => sourceNodeMap(review?.sourceNodes || []), [review?.sourceNodes]); const selectedSource = sourceMap.get(selectedSourceNodeKey || '');
  const selectedNode = review?.reviewRevision ? findNodeByReviewId(review.reviewRevision.confirmedDocument, selectedReviewNodeId) : undefined;
  const selectedTableId = typeof selectedNode?.attrs?.sourceTableId === 'string' ? selectedNode.attrs.sourceTableId : undefined;

  if (loading) return <div className="business-page"><Spin /></div>;
  if (!review?.reviewRevision) return <div className="business-page"><Empty description={review?.parseRun?.status === 'FAILED' ? review.parseRun.errorMessage || '解析失败' : '暂无可校对内容'} /></div>;
  const revision = review.reviewRevision; const readOnly = revision.status !== 'DRAFT'; const excluded = selectedReviewNodeId ? revision.excludedReviewNodeIds.includes(selectedReviewNodeId) : false;
  const saveLabel = readOnly ? '已发布' : { saved: '已保存', dirty: '有未保存修改', saving: '保存中…', offline: '尚未保存', conflict: '保存冲突' }[saveState];
  return <div className="business-page knowledge-review-workbench">
    <div className="business-page-heading knowledge-review-heading"><Space><Button icon={<ArrowLeftOutlined />} onClick={() => navigate(revisionMode ? `/knowledge/documents/${review.documentId}` : '/knowledge/view?reviewStatus=PENDING_REVIEW')}>返回</Button><div><Typography.Title level={2} style={{ margin: 0 }}>{revisionMode ? '修订识别内容' : '内容校对'} · {review.title}</Typography.Title><Typography.Text type="secondary">版本 V{review.versionNo} · 对照原文校对识别内容</Typography.Text></div></Space><Space wrap><Tag color={saveState === 'saved' ? 'success' : saveState === 'conflict' ? 'error' : 'processing'} icon={saveState === 'saved' ? <CheckCircleOutlined /> : <SaveOutlined />}>{saveLabel}</Tag>{unresolved.length > 0 && <Space.Compact><Button onClick={() => jumpIssue(-1)}>上一处</Button><Button icon={<ExclamationCircleOutlined />} onClick={resolveCurrentIssue}>{unresolved.length} 处待检查</Button><Button onClick={() => jumpIssue(1)}>下一处</Button></Space.Compact>}<Button onClick={reparse}>重新解析原文件</Button><Button danger onClick={() => setRejectOpen(true)}>驳回本次结果</Button><Button type="primary" disabled={saveState === 'conflict' || revision.status !== 'DRAFT'} onClick={publish}>{revisionMode ? '保存并应用修订' : '确认并发布'}</Button></Space></div>
    {saveState === 'offline' && <Alert type="warning" showIcon message="网络异常，修改仍保留在当前页面，恢复后可继续保存" />}
    {saveState === 'conflict' && <Alert type="error" showIcon message="校对内容已被其他用户修改" action={<Button onClick={() => void load()}>重新加载</Button>} />}
    {review.parseRun?.parserMode === 'AGENT_FALLBACK' && <Alert type="warning" showIcon message="当前解析结果的位置可能有偏差，请重点核对表格、图片和字段对应关系。" />}
    <DocumentComparisonViewer original={<OriginalDocumentViewer fileName={review.originalName} contentType={review.contentType} load={loadOriginal} contentUrl={knowledgeApi.contentUrl(review.documentId, review.versionId)} sourceNodes={review.sourceNodes} selectedSourceNodeKey={selectedSourceNodeKey} onSourceSelect={setSelectedSourceNodeKey} />} result={<><StructuredDocumentEditor value={revision.confirmedDocument} onChange={updateDocument} selectedSourceNodeKey={selectedSourceNodeKey} onSelectionChange={(selection) => { setSelectedReviewNodeId(selection.reviewNodeId); setSelectedSourceNodeKey(selection.sourceNodeKeys[0]); }} disabled={readOnly} />{selectedTableId && <VirtualDataTable documentId={review.documentId} versionId={review.versionId} reviewRevisionId={revision.id} sourceTableId={selectedTableId} lockVersion={revision.lockVersion} editable={!readOnly} onLockVersionChange={updateLockVersion} />}</>} resultExtra={<Space size={4}>{selectedSource && <Typography.Text type="secondary">{anchorLabel(selectedSource)}</Typography.Text>}<Button size="small" disabled={readOnly || !selectedReviewNodeId} danger={excluded} onClick={toggleExcluded}>{excluded ? '恢复索引' : '排除索引'}</Button></Space>} />
    <Modal title="驳回解析结果" open={rejectOpen} okButtonProps={{ danger: true, disabled: !rejectReason.trim() }} okText="确认驳回" onOk={() => void reject()} onCancel={() => setRejectOpen(false)}><Input.TextArea rows={4} value={rejectReason} onChange={(event) => setRejectReason(event.target.value)} placeholder="说明需要重新处理的原因" /></Modal>
  </div>;
}
