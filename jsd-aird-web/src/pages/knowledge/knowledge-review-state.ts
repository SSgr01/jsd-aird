import type { KnowledgeReview } from '@/services/knowledge';

export type KnowledgeSaveState = 'saved' | 'dirty' | 'saving' | 'offline' | 'conflict';

export function knowledgeReviewSaveLabel(review: KnowledgeReview, approval: string | undefined, save: KnowledgeSaveState) {
  const status = review.reviewRevision?.status;
  if (status === 'PUBLISHED') return '已发布';
  if (status === 'SUPERSEDED') return '已被新版本替代';
  if (status === 'BUILDING') return '发布处理中';
  if (status === 'FAILED') return '发布失败';
  if (approval === 'SUBMITTED') return '待审核';
  if (approval === 'APPROVED') return '审核通过';
  return { saved: '已保存', dirty: '有未保存修改', saving: '保存中…', offline: '尚未保存', conflict: '保存冲突' }[save];
}

/** Acknowledge server revisions without overwriting edits made during the save. */
export function acknowledgeKnowledgeReview(base: KnowledgeReview, local: KnowledgeReview, saved: KnowledgeReview): KnowledgeReview {
  if (local.documentId !== base.documentId || local.reviewRevision?.id !== base.reviewRevision?.id) return local;
  return {
    ...saved,
    title: local.title === base.title ? saved.title : local.title,
    libraryScope: local.libraryScope === base.libraryScope ? saved.libraryScope : local.libraryScope,
    categoryId: local.categoryId === base.categoryId ? saved.categoryId : local.categoryId,
    tags: local.tags === base.tags ? saved.tags : local.tags,
    reviewRevision: saved.reviewRevision && local.reviewRevision && base.reviewRevision ? {
      ...saved.reviewRevision,
      confirmedDocument: local.reviewRevision.confirmedDocument === base.reviewRevision.confirmedDocument
        ? saved.reviewRevision.confirmedDocument : local.reviewRevision.confirmedDocument,
      excludedReviewNodeIds: local.reviewRevision.excludedReviewNodeIds === base.reviewRevision.excludedReviewNodeIds
        ? saved.reviewRevision.excludedReviewNodeIds : local.reviewRevision.excludedReviewNodeIds,
    } : saved.reviewRevision,
  };
}
