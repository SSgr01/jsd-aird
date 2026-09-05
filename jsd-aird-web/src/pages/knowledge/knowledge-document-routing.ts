import type { KnowledgeDocument, KnowledgeReview } from '@/services/knowledge';

export function pendingKnowledgeReviewPath(
  canReview: boolean,
  document?: KnowledgeDocument,
  review?: KnowledgeReview,
) {
  if (!canReview || !document || !review?.reviewRevision) return undefined;
  if (document.currentPublicationId) return undefined;
  if (!['PENDING_REVIEW', 'REJECTED'].includes(document.reviewStatus)) return undefined;
  if (review.versionId !== document.currentVersionId || review.reviewRevision.status !== 'DRAFT') {
    return undefined;
  }
  return `/knowledge/review/${encodeURIComponent(review.documentId)}/${encodeURIComponent(review.versionId)}`;
}
