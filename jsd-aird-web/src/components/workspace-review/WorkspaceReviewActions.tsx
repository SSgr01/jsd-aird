import { Button, Input, List, Modal, Space, Tag, message } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { useLatestRequest } from '@/services/query/use-request-guard';
import { usePermission } from '@/components/auth/usePermission';
import { workspaceReviewApi, type WorkspaceReviewEvent, type WorkspaceReviewState, type WorkspaceReviewType } from '@/services/reviews/workspace-review-api';

const permissionPrefixes: Record<WorkspaceReviewType, string> = {
  PROJECT_DOCUMENT: 'project.document',
  QUALITY_RECORD: 'quality.view',
  PRODUCTION_ORDER: 'production.view',
  PRODUCTION_UPLOAD: 'production.view',
  KNOWLEDGE_VERSION: 'knowledge.view',
};

export function WorkspaceReviewActions({ type, id, saved, enabled = true, published = false, refreshKey, onStatusChange }: {
  type: WorkspaceReviewType;
  id: string;
  saved: boolean;
  enabled?: boolean;
  published?: boolean;
  refreshKey?: unknown;
  onStatusChange?: (status: WorkspaceReviewState['status']) => void;
}) {
  const prefix = permissionPrefixes[type];
  const canSubmit = usePermission(`${prefix}.submit`);
  const canApprove = usePermission(`${prefix}.approve`);
  const [state, setState] = useState<WorkspaceReviewState>();
  const [busy, setBusy] = useState(false);
  const [rejectOpen, setRejectOpen] = useState(false);
  const [rejectReason, setRejectReason] = useState('');
  const [historyOpen, setHistoryOpen] = useState(false);
  const [history, setHistory] = useState<WorkspaceReviewEvent[]>([]);
  const requests = useLatestRequest(`${type}:${id}`);
  const operations = useLatestRequest(`${type}:${id}`);
  const historyLoads = useLatestRequest(`${type}:${id}`);
  const running = useRef(false);
  const statusChanged = useRef(onStatusChange);
  statusChanged.current = onStatusChange;

  useEffect(() => {
    setState(undefined); setBusy(false); running.current = false;
    setRejectOpen(false); setHistoryOpen(false); setHistory([]); setRejectReason('');
  }, [type, id]);

  useEffect(() => {
    if (!id || !saved || running.current) return;
    const isCurrent = requests.begin();
    void workspaceReviewApi.get(type, id).then((next) => {
      if (isCurrent()) { setState(next); statusChanged.current?.(next.status); }
    }).catch((error) => { if (isCurrent()) void message.error(error instanceof Error ? error.message : '审核状态加载失败'); });
    return () => requests.invalidate();
  }, [type, id, saved, refreshKey, requests]);

  const run = async (action: 'submit' | 'approve' | 'reject') => {
    if (running.current) return;
    const isCurrent = operations.begin();
    requests.invalidate();
    running.current = true;
    setBusy(true);
    try {
      const next = action === 'reject'
        ? await workspaceReviewApi.reject(type, id, rejectReason.trim())
        : await workspaceReviewApi[action](type, id);
      if (!isCurrent()) return;
      setState(next);
      statusChanged.current?.(next.status);
      if (action === 'reject') { setRejectOpen(false); setRejectReason(''); }
      message.success(action === 'submit' ? '已提交审核' : action === 'approve' ? '审核已通过' : '已驳回');
    } catch (error) {
      if (isCurrent()) message.error(error instanceof Error ? error.message : '审核操作失败');
    } finally {
      if (isCurrent()) { running.current = false; setBusy(false); }
    }
  };
  const showHistory = async () => {
    const isCurrent = historyLoads.begin(); setHistoryOpen(true); setHistory([]);
    try { const next = await workspaceReviewApi.history(type, id); if (isCurrent()) setHistory(next); }
    catch (error) { if (isCurrent()) void message.error(error instanceof Error ? error.message : '审核记录加载失败'); }
  };

  return <>
    {state && <Tag title={state.status === 'REJECTED' ? state.reviewComment : undefined} color={published ? 'green' : state.status === 'APPROVED' ? 'success' : state.status === 'SUBMITTED' ? 'processing' : state.status === 'REJECTED' ? 'error' : 'default'}>
      {published ? '已发布' : state.status === 'APPROVED' ? '审核通过' : state.status === 'SUBMITTED' ? '待审核' : state.status === 'REJECTED' ? '已退回' : '草稿'}
    </Tag>}
    {canSubmit && !published && (state?.status === 'NOT_SUBMITTED' || state?.status === 'REJECTED') && <Button disabled={!enabled || !saved || busy} onClick={() => void run('submit')}>提交审核</Button>}
    {canApprove && !published && state?.status === 'SUBMITTED' && <Space size={4}>
      <Button disabled={!enabled || busy} onClick={() => void run('approve')}>审核通过</Button>
      {(type === 'QUALITY_RECORD' || type === 'PRODUCTION_UPLOAD' || type === 'KNOWLEDGE_VERSION') && <Button danger disabled={!enabled || busy} onClick={() => setRejectOpen(true)}>驳回</Button>}
    </Space>}
    {state && <Button onClick={() => void showHistory()}>审核记录</Button>}
    <Modal title="审核记录" open={historyOpen} footer={null} onCancel={() => setHistoryOpen(false)}>
      <List locale={{ emptyText: '暂无审核记录' }} dataSource={history} renderItem={(item) => <List.Item>
        <Space direction="vertical" size={0}>
          <strong>{item.action === 'SUBMITTED' ? '提交审核' : item.action === 'APPROVED' ? '审核通过' : '已退回'}</strong>
          <span>{[item.operatorName, new Date(item.createdAt).toLocaleString('zh-CN')].filter(Boolean).join(' · ')}</span>
          {item.comment && <span>{item.comment}</span>}
        </Space>
      </List.Item>} />
    </Modal>
    <Modal title="驳回审核" open={rejectOpen} okText="确认驳回" okButtonProps={{ disabled: !rejectReason.trim(), loading: busy }}
      onOk={() => void run('reject')} onCancel={() => { setRejectOpen(false); setRejectReason(''); }}>
      <Input.TextArea aria-label="驳回原因" placeholder="请输入驳回原因" value={rejectReason} onChange={(event) => setRejectReason(event.target.value)} rows={4} maxLength={500} />
    </Modal>
  </>;
}
