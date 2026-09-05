import { describe, expect, it } from 'vitest';

import type { KnowledgeDocument, KnowledgeReview } from '@/services/knowledge';

import { pendingKnowledgeReviewPath } from './knowledge-document-routing';

const document = {
  id: 'document-1',
  currentVersionId: 'version-1',
  reviewStatus: 'PENDING_REVIEW',
} as KnowledgeDocument;

const review = {
  documentId: 'document-1',
  versionId: 'version-1',
  reviewRevision: { status: 'DRAFT' },
} as KnowledgeReview;

describe('knowledge document routing', () => {
  it('opens an unpublished ready draft directly for a reviewer', () => {
    expect(pendingKnowledgeReviewPath(true, document, review))
      .toBe('/knowledge/review/document-1/version-1');
  });

  it('keeps an unpublished draft hidden from an ordinary reader', () => {
    expect(pendingKnowledgeReviewPath(false, document, review)).toBeUndefined();
  });

  it('keeps the published reading page when a current publication exists', () => {
    expect(pendingKnowledgeReviewPath(
      true,
      { ...document, currentPublicationId: 'publication-1' },
      review,
    )).toBeUndefined();
  });
});
