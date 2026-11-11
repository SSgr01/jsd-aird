import type { ReactNode } from 'react';
import './status-tag.css';

type StatusTone = 'neutral' | 'active' | 'success' | 'warning' | 'danger';

const labels: Record<string, string> = {
  NOT_STARTED: '未开始', PENDING: '待开始', DRAFT: '草稿',
  IN_PROGRESS: '进行中', RUNNING: '运行中', PROCESSING: '处理中',
  COMPLETED: '已完成', SUCCEEDED: '已完成', PUBLISHED: '已发布',
  APPROVED: '审核通过', CONFIRMED: '已确认', ARCHIVED: '已归档',
  BLOCKED: '受阻', PAUSED: '已暂停', PENDING_REVIEW: '待审核',
  SUBMITTED: '待审核', RETURNED: '已退回', REJECTED: '已驳回',
  FAILED: '失败', CANCELLED: '已取消', VOIDED: '已作废',
  DISABLED: '已停用', ACTIVE: '有效', QUEUED: '排队中',
};

const tones: Record<string, StatusTone> = {};
for (const status of ['IN_PROGRESS', 'RUNNING', 'PROCESSING', 'PARSING', 'VALIDATING', 'COMMITTING', 'QUEUED']) tones[status] = 'active';
for (const status of ['COMPLETED', 'SUCCEEDED', 'PUBLISHED', 'APPROVED', 'CONFIRMED', 'ARCHIVED', 'ACTIVE', 'SYNCED']) tones[status] = 'success';
for (const status of ['BLOCKED', 'PAUSED', 'PENDING_REVIEW', 'SUBMITTED', 'NEEDS_REVIEW', 'WAITING_SHEET', 'WAITING_MAPPING', 'WAITING_CONFIRM', 'SUPERSEDED']) tones[status] = 'warning';
for (const status of ['FAILED', 'RETURNED', 'REJECTED', 'INVALIDATED']) tones[status] = 'danger';

/** Labels can remain domain-specific; shape and semantic colors are shared. */
export function StatusTag({ status, label, tone }: { status?: string | null; label?: ReactNode; tone?: StatusTone }) {
  if (!status) return <span className="app-status-empty">—</span>;
  const text = label ?? labels[status] ?? '未知状态';
  return <span className={`app-status-tag is-${tone ?? tones[status] ?? 'neutral'}`} title={labels[status] || label ? undefined : status}>{text}</span>;
}
