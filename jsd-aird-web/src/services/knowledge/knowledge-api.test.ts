import { beforeEach, describe, expect, it, vi } from 'vitest';

const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
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

  it('reads and updates upload records through the server API', async () => {
    const task = { id: 'task-1', fileName: 'report.pdf', size: 1024, status: 'QUEUED', progress: 100 };
    httpMock.get.mockResolvedValue({ data: { data: [task] } });
    httpMock.put.mockResolvedValue({ data: { data: task } });

    expect(await knowledgeApi.uploadTasks()).toEqual([task]);
    await knowledgeApi.updateUploadTask('task-1', { status: 'QUEUED', progress: 100, documentId: 'doc-1', documentVersionId: 'version-1' });

    expect(httpMock.get).toHaveBeenCalledWith('/api/v1/knowledge/upload-tasks', { params: { limit: 100 } });
    expect(httpMock.put).toHaveBeenCalledWith('/api/v1/knowledge/upload-tasks/task-1', {
      status: 'QUEUED', progress: 100, documentId: 'doc-1', documentVersionId: 'version-1',
    });
  });

  it('creates and removes upload records through the server API', async () => {
    const task = { id: 'task-1', fileName: 'report.pdf', size: 1024, status: 'STAGING', progress: 8 };
    httpMock.post.mockResolvedValue({ data: { data: task } });

    expect(await knowledgeApi.createUploadTask({ fileName: 'report.pdf', size: 1024 })).toEqual(task);
    await knowledgeApi.deleteUploadTask('task-1');

    expect(httpMock.post).toHaveBeenCalledWith('/api/v1/knowledge/upload-tasks', { fileName: 'report.pdf', size: 1024 });
    expect(httpMock.delete).toHaveBeenCalledWith('/api/v1/knowledge/upload-tasks/task-1');
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
