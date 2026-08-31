import type { KnowledgeDocument, ReviewQueueItem } from '@/services/knowledge';

export type WorkflowStatus = { label: string; color: string };

const parsingStatuses: Record<string, WorkflowStatus> = {
  QUEUED: { label: '排队中', color: 'blue' },
  PROCESSING: { label: '解析中', color: 'processing' },
  FAILED: { label: '解析失败', color: 'red' },
  REJECTED: { label: '解析失败', color: 'red' },
  PENDING_PROVIDER: { label: '解析服务暂不可用', color: 'orange' },
};

export function documentWorkflowStatus(item: Pick<KnowledgeDocument, 'status' | 'reviewStatus' | 'reviewRevisionStatus'>): WorkflowStatus {
  if (item.status !== 'READY') return parsingStatuses[item.status] || { label: item.status, color: 'default' };
  if (item.reviewStatus === 'PUBLISHED') return { label: '已发布', color: 'success' };
  if (item.reviewStatus === 'REJECTED') return { label: '已驳回', color: 'error' };
  if (item.reviewStatus === 'SUPERSEDED') return { label: '已被新版本替代', color: 'default' };
  if (item.reviewRevisionStatus === 'BUILDING') return { label: '发布处理中', color: 'processing' };
  if (item.reviewRevisionStatus === 'FAILED') return { label: '发布失败', color: 'error' };
  if (item.reviewRevisionStatus === 'DRAFT') return { label: '待校对', color: 'gold' };
  return { label: '解析完成', color: 'success' };
}

export function reviewQueueStatus(item: Pick<ReviewQueueItem, 'reviewStatus' | 'reviewRevisionStatus'>): WorkflowStatus {
  if (item.reviewStatus === 'REJECTED') return { label: '已驳回', color: 'error' };
  if (item.reviewRevisionStatus === 'FAILED') return { label: '发布失败', color: 'error' };
  return { label: '待校对', color: 'gold' };
}
