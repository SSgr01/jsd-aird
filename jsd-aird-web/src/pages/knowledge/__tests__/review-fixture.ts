import type { KnowledgeReview } from '@/services/knowledge';

export function reviewFixture(): KnowledgeReview {
  return {
    documentId: 'one', versionId: 'v1', title: '光固化涂层', libraryScope: 'INTERNAL', lifecycleStatus: 'ACTIVE',
    versionNo: 1, fileObjectId: 'file', originalName: '涂层.pdf', contentType: 'application/pdf', size: 10,
    processingStatus: 'READY', reviewStatus: 'PENDING_REVIEW', sourceInfo: {}, sourceNodes: [], issues: [], tags: ['涂层'],
    reviewRevision: { id: 'revision', parseRunId: 'parse', revisionNo: 1, lockVersion: 0,
      confirmedDocument: { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: '初稿' }] }] },
      excludedReviewNodeIds: [], status: 'DRAFT', updatedAt: '2026-11-21T00:00:00Z' },
  };
}
