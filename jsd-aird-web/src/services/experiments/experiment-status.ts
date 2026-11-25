export interface LinkedExperimentStatus {
  experimentId: string;
  versionId?: string;
  experimentNo?: string;
  title?: string;
  status: string;
}

export const experimentStatusLabels: Record<string, string> = {
  DRAFT: '草稿', PENDING: '待开始', IN_PROGRESS: '进行中', PENDING_REVIEW: '待审核',
  APPROVED: '审核通过，待发布', RETURNED: '已退回', COMPLETED: '已发布', VOIDED: '已作废',
};

export function linkedExperimentStatusText(items?: LinkedExperimentStatus[]): string {
  if (!items?.length) return '实验状态暂不可用';
  if (items.length === 1) return `实验：${experimentStatusLabels[items[0].status] || '状态暂不可用'}`;
  const counts = new Map<string, number>();
  items.forEach(item => counts.set(item.status, (counts.get(item.status) || 0) + 1));
  return `已生成 ${items.length} 个实验：${[...counts].map(([status, count]) => `${experimentStatusLabels[status] || '状态暂不可用'} ${count}`).join('、')}`;
}
