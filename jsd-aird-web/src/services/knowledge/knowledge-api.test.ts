import { beforeEach, describe, expect, it, vi } from 'vitest';

const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}));

vi.mock('@/services/http/client', () => ({ httpClient: httpMock }));

import { knowledgeApi, type KnowledgeReview } from './knowledge-api';

describe('knowledge API', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    httpMock.post.mockResolvedValue({ data: { data: {} } });
  });

  it('creates a governed document without customer parsing policy', async () => {
    const input = {
      fileId: 'file-1',
      title: 'UA-1117',
      libraryScope: 'INTERNAL',
      categoryId: 'category-1',
    };

    await knowledgeApi.createGoverned(input);

    expect(httpMock.post).toHaveBeenCalledWith('/api/v1/knowledge/documents', input);
  });

  it('reparses without customer parsing policy', async () => {
    const review = {
      documentId: 'document-1',
      versionId: 'version-1',
      reviewRevision: { id: 'revision-1', lockVersion: 7 },
    } as KnowledgeReview;

    await knowledgeApi.reparse(review);

    expect(httpMock.post).toHaveBeenCalledWith(
      '/api/v1/knowledge/documents/document-1/versions/version-1/reparse',
      {
        reviewRevisionId: 'revision-1',
        lockVersion: 7,
      },
    );
  });
});
