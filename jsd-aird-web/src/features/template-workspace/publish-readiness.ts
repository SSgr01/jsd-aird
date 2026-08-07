export type PublishSaveState = 'SAVED' | 'DIRTY' | 'SAVING' | 'FAILED' | 'CONFLICT';
export type PublishReviewSyncState = 'FRESH' | 'REFRESHING' | 'STALE';

export function publishReadinessBlocker(
  saveState: PublishSaveState,
  reviewSyncState: PublishReviewSyncState,
): string | undefined {
  if (saveState !== 'SAVED') return '请先保存当前修改，再发布模板';
  if (reviewSyncState === 'REFRESHING') return '正在刷新识别审核状态，请稍候';
  if (reviewSyncState === 'STALE') return '审核状态需要刷新后才能发布';
  return undefined;
}
