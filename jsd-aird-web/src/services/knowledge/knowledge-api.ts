import type { ApiResponse, PageResponse } from '@/types/api';
import { httpClient } from '@/services/http/client';
import { appEnv } from '@/app/config/env';

export type KnowledgeStatus = 'QUEUED' | 'PROCESSING' | 'READY' | 'FAILED' | 'REJECTED' | 'PENDING_PROVIDER';
export type AiStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'REVOKED';

export interface KnowledgeDocument {
  id: string;
  title: string;
  status: KnowledgeStatus;
  scanStatus: string;
  aiStatus: AiStatus;
  currentVersionNo: number;
  currentVersionId: string;
  originalName: string;
  contentType: string;
  size: number;
  sha256: string;
  parseError?: string;
  createdAt: string;
  updatedAt: string;
  libraryScope: 'INTERNAL' | 'EXTERNAL';
  categoryId?: string;
  categoryName?: string;
  lifecycleStatus: 'ACTIVE' | 'DISABLED';
  reviewStatus: 'PENDING_REVIEW' | 'REJECTED' | 'PUBLISHED' | 'SUPERSEDED';
  reviewRevision: number;
  currentPublicationId?: string;
  currentPublicationNo?: number;
}

export interface KnowledgeCategory { id: string; scope: 'INTERNAL' | 'EXTERNAL'; name: string; description?: string; sortOrder: number; documentCount: number }
export interface KnowledgeVersion { id: string; documentId: string; versionNo: number; fileObjectId: string; originalName: string; contentType: string; size: number; sha256: string; status: KnowledgeStatus; errorMessage?: string; reviewStatus: string; reviewRevision: number }
export interface DuplicateMatch { documentId: string; versionId: string; versionNo: number; title: string; originalName: string; sha256: string; similarity: number; lifecycleStatus: string; reviewStatus: string }
export interface UploadPreflight { decision: 'EXACT_DUPLICATE' | 'POSSIBLE_VERSION' | 'NEW_DOCUMENT'; fileId: string; originalName: string; sha256: string; exactMatches: DuplicateMatch[]; possibleVersions: DuplicateMatch[] }
export interface ParseBlock { id: string; blockNo: number; pageNo?: number; sheetName?: string; cellRange?: string; paragraphId?: string; bbox?: number[]; startTimeMs?: number; endTimeMs?: number; section?: string; rawText: string; normalizedText: string; confirmedText?: string; confidence?: number; reviewStatus: 'PENDING' | 'CONFIRMED' | 'IGNORED' | 'ISSUE' }
export interface ParseRun { id: string; documentId: string; versionId: string; runNo: number; status: string; errorMessage?: string; createdAt: string }
export interface ParseIssue { id: string; blockId?: string; code: string; severity: 'INFO' | 'WARNING' | 'BLOCKER'; message: string; status: 'OPEN' | 'RESOLVED' | 'IGNORED'; resolution?: string }
export interface KnowledgeReview { documentId: string; title: string; libraryScope: 'INTERNAL' | 'EXTERNAL'; categoryId?: string; categoryName?: string; lifecycleStatus: string; versionId: string; versionNo: number; originalName: string; contentType: string; size: number; processingStatus: string; reviewStatus: string; reviewRevision: number; sourceInfo: Record<string, unknown>; parseRun?: ParseRun; blocks: ParseBlock[]; issues: ParseIssue[]; tags: string[] }
export interface ReviewQueueItem { documentId: string; title: string; versionId: string; versionNo: number; originalName: string; processingStatus: string; reviewStatus: string; reviewRevision: number; categoryName?: string; updatedAt: string }
export interface Publication { id: string; documentId: string; versionId: string; parseRunId: string; publicationNo: number; status: string; aiStatus: AiStatus; publishedAt: string }
export interface IndexBuildView { documentId: string; versionId: string; parseRunId: string; status: 'INDEXING' }
export interface BatchResult { documentId: string; success: boolean; errorCode?: string; message?: string }

const reviewPayload = (review: KnowledgeReview, confirmAll = false) => ({
  documentId: review.documentId,
  versionId: review.versionId,
  reviewRevision: review.reviewRevision,
  title: review.title,
  libraryScope: review.libraryScope,
  categoryId: review.categoryId,
  tags: review.tags,
  blocks: review.blocks.map((item) => ({
    id: item.id,
    confirmedText: item.confirmedText ?? item.normalizedText,
    reviewStatus: item.reviewStatus === 'IGNORED' ? 'IGNORED' : confirmAll ? 'CONFIRMED' : item.reviewStatus,
  })),
});

export const knowledgeApi = {
  async list(params: { keyword?: string; status?: string; aiStatus?: string; scope?: string; categoryId?: string; lifecycleStatus?: string; reviewStatus?: string; page?: number; size?: number } = {}) {
    const response = await httpClient.get<ApiResponse<PageResponse<KnowledgeDocument>>>('/api/v1/knowledge/documents', { params });
    return response.data.data;
  },
  async categories(scope?: 'INTERNAL' | 'EXTERNAL') { const response = await httpClient.get<ApiResponse<KnowledgeCategory[]>>('/api/v1/knowledge/categories', { params: scope ? { scope } : undefined }); return response.data.data; },
  async createCategory(input: { name: string; scope: 'INTERNAL' | 'EXTERNAL'; description?: string }) { const response = await httpClient.post<ApiResponse<KnowledgeCategory>>('/api/v1/knowledge/categories', input); return response.data.data; },
  async renameCategory(id: string, input: { name: string; description?: string }) { const response = await httpClient.put<ApiResponse<KnowledgeCategory>>(`/api/v1/knowledge/categories/${id}`, input); return response.data.data; },
  async deleteCategory(id: string, replacementCategoryId?: string) { await httpClient.delete(`/api/v1/knowledge/categories/${id}`, { params: replacementCategoryId ? { replacementCategoryId } : undefined }); },
  async assignCategory(documentId: string, categoryId: string) { await httpClient.put(`/api/v1/knowledge/documents/${documentId}/category`, { categoryId }); },
  async rename(id: string, title: string) { const response = await httpClient.put<ApiResponse<KnowledgeDocument>>(`/api/v1/knowledge/documents/${id}`, { title }); return response.data.data; },
  async remove(id: string) { await httpClient.delete(`/api/v1/knowledge/documents/${id}`); },
  async exportDocuments(documentIds: string[]) { const response = await httpClient.post<Blob>('/api/v1/knowledge/documents/export', { documentIds }, { responseType: 'blob' }); return response.data; },
  async get(id: string) { const response = await httpClient.get<ApiResponse<KnowledgeDocument>>(`/api/v1/knowledge/documents/${id}`); return response.data.data; },
  async versions(id: string) { const response = await httpClient.get<ApiResponse<KnowledgeVersion[]>>(`/api/v1/knowledge/documents/${id}/versions`); return response.data.data; },
  async contentBlob(documentId: string, versionId?: string) { const path = versionId ? `/api/v1/knowledge/documents/${documentId}/versions/${versionId}/content` : `/api/v1/knowledge/documents/${documentId}/content`; const response = await httpClient.get<Blob>(path, { responseType: 'blob' }); return response.data; },
  async grant(id: string, action: 'APPROVE' | 'REJECT' | 'REVOKE', reason?: string) { const response = await httpClient.post<ApiResponse<KnowledgeDocument>>(`/api/v1/knowledge/documents/${id}/ai-grant`, { action, reason }); return response.data.data; },
  async preflight(fileId: string, categoryId: string) { const response = await httpClient.post<ApiResponse<UploadPreflight>>('/api/v1/knowledge/uploads/preflight', { fileId, categoryId }); return response.data.data; },
  async createGoverned(input: { fileId: string; title?: string; libraryScope: string; categoryId: string; tags?: string[]; resolution?: 'NEW_DOCUMENT' | 'NEW_VERSION'; targetDocumentId?: string; sourceInfo?: Record<string, unknown> }) { const response = await httpClient.post<ApiResponse<KnowledgeDocument>>('/api/v1/knowledge/documents', input); return response.data.data; },
  async reviewQueue(status?: string) { const response = await httpClient.get<ApiResponse<ReviewQueueItem[]>>('/api/v1/knowledge/review-queue', { params: status ? { status } : undefined }); return response.data.data; },
  async review(documentId: string, versionId: string) { const response = await httpClient.get<ApiResponse<KnowledgeReview>>(`/api/v1/knowledge/documents/${documentId}/versions/${versionId}/review`); return response.data.data; },
  async saveReview(review: KnowledgeReview, confirmAll = false) { const response = await httpClient.put<ApiResponse<KnowledgeReview>>(`/api/v1/knowledge/documents/${review.documentId}/versions/${review.versionId}/review`, reviewPayload(review, confirmAll)); return response.data.data; },
  async publish(documentId: string, versionId: string, reviewRevision: number) { const response = await httpClient.post<ApiResponse<IndexBuildView>>(`/api/v1/knowledge/documents/${documentId}/versions/${versionId}/publish`, { reviewRevision }); return response.data.data; },
  async revise(review: KnowledgeReview, basePublicationId: string) { const payload = reviewPayload(review, true); const response = await httpClient.post<ApiResponse<IndexBuildView>>(`/api/v1/knowledge/documents/${review.documentId}/revisions`, { basePublicationId, reviewRevision: review.reviewRevision, blocks: payload.blocks }); return response.data.data; },
  async reject(documentId: string, versionId: string, reviewRevision: number, reason: string) { await httpClient.post(`/api/v1/knowledge/documents/${documentId}/versions/${versionId}/reject`, { reviewRevision, reason }); },
  async reparse(documentId: string, versionId: string, reviewRevision: number) { const response = await httpClient.post<ApiResponse<KnowledgeDocument>>(`/api/v1/knowledge/documents/${documentId}/versions/${versionId}/reparse`, { reviewRevision }); return response.data.data; },
  async disable(documentId: string, reason: string) { await httpClient.post(`/api/v1/knowledge/documents/${documentId}/disable`, { reason }); },
  async restore(documentId: string) { await httpClient.post(`/api/v1/knowledge/documents/${documentId}/restore`); },
  async batchMove(documentIds: string[], categoryId: string) { const response = await httpClient.post<ApiResponse<BatchResult[]>>('/api/v1/knowledge/documents/batch/move', { documentIds, categoryId }); return response.data.data; },
  async batchTags(documentIds: string[], add: string[], remove: string[]) { const response = await httpClient.post<ApiResponse<BatchResult[]>>('/api/v1/knowledge/documents/batch/tags', { documentIds, add, remove }); return response.data.data; },
  async batchAiUsage(documentIds: string[], action: 'APPROVE' | 'REJECT' | 'REVOKE', reason?: string) { const response = await httpClient.post<ApiResponse<BatchResult[]>>('/api/v1/knowledge/documents/batch/ai-usage', { documentIds, action, reason }); return response.data.data; },
  async publications(documentId: string) { const response = await httpClient.get<ApiResponse<Publication[]>>(`/api/v1/knowledge/documents/${documentId}/publications`); return response.data.data; },
  contentUrl(id: string, versionId?: string) { return `${appEnv.apiBaseUrl}/api/v1/knowledge/documents/${id}${versionId ? `/versions/${versionId}` : ''}/content`; },
};
