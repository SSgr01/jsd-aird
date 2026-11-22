import { WorkspaceFrame, WorkspaceMaximizeButton } from '@/components/workspace/WorkspaceFrame';
import { ArrowLeftOutlined, CheckCircleOutlined, ExclamationCircleOutlined, SaveOutlined } from '@ant-design/icons';
import { Alert, App, Button, Checkbox, Empty, Space, Spin, Tag, Typography } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { usePermission } from '@/components/auth/usePermission';
import { WorkspaceReviewActions } from '@/components/workspace-review/WorkspaceReviewActions';
import type { WorkspaceReviewState } from '@/services/reviews/workspace-review-api';

import { DocumentComparisonViewer, OriginalDocumentViewer, StructuredDocumentEditor, VirtualDataTable, anchorLabel, findNodeByReviewId, sourceNodeMap } from '@/components/knowledge-document';
import { fetchFilePreviewBlob } from '@/services/files';
import { knowledgeApi, type IssueAction, type KnowledgeReview, type StructuredDocument } from '@/services/knowledge';
import { useLatestRequest } from '@/services/query/use-request-guard';
import { acknowledgeKnowledgeReview, knowledgeReviewSaveLabel } from './knowledge-review-state';
import { HttpError } from '@/services/http/errors';

type SaveState = 'saved' | 'dirty' | 'saving' | 'offline' | 'conflict';

export function KnowledgeReviewPage() {
  // 校对保存与发布沿用「知识库查看」行的编辑/发布操作码，与权限配置页发放的
  // 页面操作码保持一致；没有编辑权限时校对内容只读。
  const canUpdate = usePermission('knowledge.view.update');
  const canPublish = usePermission('knowledge.view.publish');
  const { documentId, versionId } = useParams(); const [searchParams] = useSearchParams(); const revisionMode = searchParams.get('mode') === 'revision';
  const { message, modal } = App.useApp(); const navigate = useNavigate();
  const canAuthorizeAi = usePermission('ai.external');
  const [review, setReview] = useState<KnowledgeReview>(); const latestReview = useRef<KnowledgeReview>();
  const [loading, setLoading] = useState(true); const [saveState, setSaveState] = useState<SaveState>('saved');
  const [selectedReviewNodeId, setSelectedReviewNodeId] = useState<string>(); const [selectedSourceNodeKey, setSelectedSourceNodeKey] = useState<string>();
  const [approvalStatus, setApprovalStatus] = useState<WorkspaceReviewState['status']>();
  const [issueActions, setIssueActions] = useState<IssueAction[]>([]); const savePromise = useRef<Promise<KnowledgeReview | undefined>>(); const dirty = useRef(false); const revisionInitialized = useRef(false);
  const issueActionsRef = useRef(issueActions); issueActionsRef.current = issueActions;
  const resourceKey = `${documentId}:${versionId}:${revisionMode}`;
  const loads = useLatestRequest(resourceKey);
  const saves = useLatestRequest(resourceKey);
  useEffect(() => {
    latestReview.current = undefined; savePromise.current = undefined; dirty.current = false;
    revisionInitialized.current = false; setReview(undefined); setIssueActions([]); setApprovalStatus(undefined);
    setSelectedReviewNodeId(undefined); setSelectedSourceNodeKey(undefined);
  }, [resourceKey]);

  const load = useCallback(async () => {
    if (!documentId || !versionId) return; setLoading(true);
    const isCurrent = loads.begin();
    saves.invalidate();
    try {
      let next = await knowledgeApi.review(documentId, versionId);
      const item = await knowledgeApi.get(documentId);
      if (!isCurrent()) return;
      if (revisionMode && item.currentPublicationId && !revisionInitialized.current
          && (!next.reviewRevision || next.reviewRevision.status === 'PUBLISHED' || next.reviewRevision.status === 'FAILED' || next.reviewRevision.basePublicationId !== item.currentPublicationId)) {
        next = await knowledgeApi.createRevision(documentId, item.currentPublicationId);
        if (isCurrent()) revisionInitialized.current = true;
      }
      if (!isCurrent()) return;
      dirty.current = false; setIssueActions([]);
      setReview(next); latestReview.current = next; setSaveState('saved');
    } catch (reason) { if (isCurrent()) void message.error(reason instanceof Error ? reason.message : '校对内容加载失败'); }
    finally { if (isCurrent()) setLoading(false); }
  }, [documentId, loads, saves, message, revisionMode, versionId]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => { latestReview.current = review; }, [review]);

  const persist = useCallback(async (): Promise<KnowledgeReview | undefined> => {
    if (!saves.isActive()) return undefined;
    if (savePromise.current) { await savePromise.current; if (!saves.isActive()) return undefined; if (dirty.current) return persist(); return latestReview.current; }
    const snapshot = latestReview.current; if (!snapshot?.reviewRevision || !dirty.current) return snapshot;
    const isCurrent = saves.begin();
    const actions = issueActionsRef.current;
    dirty.current = false; setSaveState('saving');
    const request = knowledgeApi.saveReview(snapshot, actions).then((saved) => {
      if (!isCurrent()) return undefined;
      setIssueActions((current) => current.filter((action) => !actions.includes(action)));
      const next = acknowledgeKnowledgeReview(snapshot, latestReview.current ?? snapshot, saved);
      latestReview.current = next; setReview(next);
      setSaveState(dirty.current ? 'dirty' : 'saved'); return next;
    }).catch((reason) => { if (!isCurrent()) return undefined; dirty.current = true; const conflict = reason instanceof HttpError && reason.status === 409; setSaveState(conflict ? 'conflict' : 'offline'); if (conflict) void message.error('内容已被其他人更新，自动保存已停止，请重新加载'); return undefined; }).finally(() => { if (savePromise.current === request) savePromise.current = undefined; });
    savePromise.current = request; return request;
  }, [saves, message]);

  useEffect(() => {
    if (!review?.reviewRevision || !dirty.current || saveState === 'conflict' || saveState === 'offline') return;
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
  const forceSave = async () => { if (dirty.current || savePromise.current) return persist(); return latestReview.current; };
  const publish = () => { const current = latestReview.current; if (!current) return; let authorizeAi = canAuthorizeAi; modal.confirm({ title: revisionMode ? '保存并应用本次修订？' : `确认并发布“${current.title}”V${current.versionNo}？`, content: <Space direction="vertical"><Typography.Text>确认后将发布当前校对内容。</Typography.Text>{canAuthorizeAi && <Checkbox defaultChecked onChange={(event) => { authorizeAi = event.target.checked; }}>发布后授权 AI 使用</Checkbox>}</Space>, okText: revisionMode ? '保存并应用' : '确认并发布', onOk: async () => { const saved = await forceSave(); if (!saved) return; await knowledgeApi.publish(saved, authorizeAi); void message.success(authorizeAi ? '已提交发布，完成后将自动授权 AI' : '已提交发布，请稍候'); navigate(`/knowledge/documents/${saved.documentId}`); } }); };
  const reparse = () => {
    const current = latestReview.current; if (!current) return;
    modal.confirm({ title: '重新解析原文件？', content: <Typography.Paragraph type="secondary">系统将使用当前统一解析策略创建新的解析执行和校对草稿，当前发布内容不受影响。</Typography.Paragraph>, okText: '重新解析', onOk: async () => { const saved = await forceSave(); if (!saved) return; await knowledgeApi.reparse(saved); void message.success('已提交重新解析'); await load(); } });
  };
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
  const revision = review.reviewRevision; const readOnly = revision.status !== 'DRAFT' || !canUpdate || saveState === 'conflict' || approvalStatus === 'SUBMITTED' || approvalStatus === 'APPROVED'; const excluded = selectedReviewNodeId ? revision.excludedReviewNodeIds.includes(selectedReviewNodeId) : false;
  const saveLabel = knowledgeReviewSaveLabel(review, approvalStatus, saveState);
  return <WorkspaceFrame maximizable className="business-page knowledge-review-workbench">
    <div className="business-page-heading knowledge-review-heading"><Space><Button icon={<ArrowLeftOutlined />} onClick={() => navigate(revisionMode ? `/knowledge/documents/${review.documentId}` : '/knowledge/view?reviewStatus=PENDING_REVIEW')}>返回</Button><div><Typography.Title level={1} style={{ margin: 0 }}>{revisionMode ? '修订识别内容' : '内容校对'} · {review.title}</Typography.Title><Typography.Text type="secondary">版本 V{review.versionNo} · 对照原文校对识别内容</Typography.Text></div></Space><Space wrap><Tag color={saveState === 'saved' ? 'success' : saveState === 'conflict' ? 'error' : 'processing'} icon={saveState === 'saved' ? <CheckCircleOutlined /> : <SaveOutlined />}>{saveLabel}</Tag>{unresolved.length > 0 && <Space.Compact><Button onClick={() => jumpIssue(-1)}>上一处</Button>{canUpdate && <Button icon={<ExclamationCircleOutlined />} onClick={resolveCurrentIssue}>{unresolved.length} 处待检查</Button>}<Button onClick={() => jumpIssue(1)}>下一处</Button></Space.Compact>}{canUpdate && !readOnly && <Button onClick={reparse}>重新解析原文件</Button>}<WorkspaceReviewActions type="KNOWLEDGE_VERSION" id={review.versionId} saved={saveState === 'saved'} published={['PUBLISHED', 'SUPERSEDED'].includes(revision.status)} enabled={revision.status === 'DRAFT'} refreshKey={revision.lockVersion} onStatusChange={setApprovalStatus} />{canPublish && <Button type="primary" disabled={saveState !== 'saved' || revision.status !== 'DRAFT' || approvalStatus !== 'APPROVED'} onClick={publish}>{revisionMode ? '保存并应用修订' : '发布'}</Button>}<WorkspaceMaximizeButton /></Space></div>
    {saveState === 'offline' && <Alert type="warning" showIcon message="网络异常，修改仍保留在当前页面，恢复后可继续保存" action={<Button onClick={() => void persist()}>重试保存</Button>} />}
    {saveState === 'conflict' && <Alert type="error" showIcon message="校对内容已被其他用户修改" action={<Button onClick={() => void load()}>重新加载</Button>} />}
    {review.parseRun?.parserMode === 'AGENT_FALLBACK' && <Alert type="warning" showIcon message="当前解析结果的位置可能有偏差，请重点核对表格、图片和字段对应关系。" />}
    <DocumentComparisonViewer original={<OriginalDocumentViewer fileName={review.originalName} contentType={review.contentType} load={loadOriginal} contentUrl={knowledgeApi.contentUrl(review.documentId, review.versionId)} sourceNodes={review.sourceNodes} selectedSourceNodeKey={selectedSourceNodeKey} onSourceSelect={setSelectedSourceNodeKey} />} result={<><StructuredDocumentEditor value={revision.confirmedDocument} onChange={updateDocument} selectedSourceNodeKey={selectedSourceNodeKey} onSelectionChange={(selection) => { setSelectedReviewNodeId(selection.reviewNodeId); setSelectedSourceNodeKey(selection.sourceNodeKeys[0]); }} disabled={readOnly} />{selectedTableId && <VirtualDataTable documentId={review.documentId} versionId={review.versionId} reviewRevisionId={revision.id} sourceTableId={selectedTableId} lockVersion={revision.lockVersion} editable={!readOnly} onLockVersionChange={updateLockVersion} />}</>} resultExtra={<Space size={4}>{selectedSource && <Typography.Text type="secondary">{anchorLabel(selectedSource)}</Typography.Text>}<Button size="small" disabled={readOnly || !selectedReviewNodeId} danger={excluded} onClick={toggleExcluded}>{excluded ? '恢复索引' : '排除索引'}</Button></Space>} />
  </WorkspaceFrame>;
}
