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

  it('uses a large-document timeout for published content', async () => {
    httpMock.get.mockResolvedValue({ data: { data: { publication: { id: 'publication-1' } } } });

    await knowledgeApi.publishedContent('document-1', 'publication-1');

    expect(httpMock.get).toHaveBeenCalledWith(
      '/api/v1/knowledge/documents/document-1/published-content',
      { params: { publicationId: 'publication-1' }, timeout: 120_000 },
    );
  });

  it('deduplicates and reuses a recently loaded original file', async () => {
    const blob = new Blob(['knowledge-content'], { type: 'application/pdf' });
    httpMock.get.mockResolvedValue({ data: blob });

    const [first, second] = await Promise.all([
      knowledgeApi.contentBlob('document-cache-test', 'version-1'),
      knowledgeApi.contentBlob('document-cache-test', 'version-1'),
    ]);
    const third = await knowledgeApi.contentBlob('document-cache-test', 'version-1');

    expect(first).toBe(blob);
    expect(second).toBe(blob);
    expect(third).toBe(blob);
    expect(httpMock.get).toHaveBeenCalledTimes(1);
    expect(httpMock.get).toHaveBeenCalledWith(
      '/api/v1/knowledge/documents/document-cache-test/versions/version-1/content',
      { responseType: 'blob', timeout: 120_000 },
    );
  });
});
