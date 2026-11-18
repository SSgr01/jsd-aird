import type { KnowledgeDocument, KnowledgeProcessing, ReviewQueueItem } from '@/services/knowledge';

export type WorkflowStatus = { label: string; color: string };

const parsingStatuses: Record<string, WorkflowStatus> = {
  QUEUED: { label: '排队中', color: 'blue' },
  PROCESSING: { label: '解析中', color: 'processing' },
  FAILED: { label: '解析失败', color: 'red' },
  REJECTED: { label: '解析失败', color: 'red' },
  PENDING_PROVIDER: { label: '解析服务暂不可用', color: 'orange' },
};

export function documentWorkflowStatus(item: Pick<KnowledgeDocument, 'status' | 'reviewStatus' | 'reviewRevisionStatus'>, processing?: KnowledgeProcessing): WorkflowStatus {
  if (processing && item.status !== 'READY') {
    if (processing.terminal && ['FAILED', 'CANCELLED', 'CANCELED'].includes(processing.jobStatus || '')) return { label: '解析失败', color: 'red' };
    if (!processing.terminal && processing.jobStatus === 'READY' && processing.lastError) return { label: '等待重试', color: 'orange' };
    if (!processing.terminal && processing.jobStatus === 'RUNNING') {
      const remote: Record<string, string> = { 'waiting-file': '等待文件上传', pending: '远端排队中', running: '远端处理中', converting: '远端转换中', done: '正在处理解析结果', failed: '远端解析失败', error: '远端解析失败' };
      return { label: remote[processing.providerState || ''] || (processing.attemptCount > 1 ? '正在重试解析' : '解析任务执行中'), color: ['failed', 'error'].includes(processing.providerState || '') ? 'red' : 'processing' };
    }
  }
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
