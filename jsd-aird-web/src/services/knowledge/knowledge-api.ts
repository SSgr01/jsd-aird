import type { ApiResponse, PageResponse } from '@/types/api';
import { httpClient } from '@/services/http/client';
import { appEnv } from '@/app/config/env';

export type KnowledgeStatus = 'QUEUED' | 'PROCESSING' | 'READY' | 'FAILED' | 'REJECTED' | 'PENDING_PROVIDER';
export type AiStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'REVOKED';

export interface KnowledgeDocument {
  id: string;
  title: string;
  documentType: string;
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

export interface KnowledgeVersion {
  id: string;
  documentId: string;
  versionNo: number;
  fileObjectId: string;
  originalName: string;
  contentType: string;
  size: number;
  sha256: string;
  status: KnowledgeStatus;
  parserVersion?: string;
  errorMessage?: string;
  reviewStatus: string;
  reviewRevision: number;
  mediaProcessingConsent: boolean;
}

export interface KnowledgeSearchHit {
  chunkId: string;
  documentId: string;
  versionId: string;
  title: string;
  originalName: string;
  pageNo?: number;
  section?: string;
  content: string;
  score: number;
}

export interface BusinessObjectRef { id: string; objectType: string; externalId: string; name: string; sourceSystem: string; status: string; metadata?: Record<string, unknown> }
export interface DuplicateMatch { documentId: string; versionId: string; versionNo: number; title: string; originalName: string; documentType: string; sha256: string; similarity: number; lifecycleStatus: string; reviewStatus: string }
export interface UploadPreflight { decision: 'EXACT_DUPLICATE' | 'POSSIBLE_VERSION' | 'NEW_DOCUMENT'; fileId: string; originalName: string; sha256: string; exactMatches: DuplicateMatch[]; possibleVersions: DuplicateMatch[] }
export interface ParseBlock { id: string; blockNo: number; pageNo?: number; sheetName?: string; cellRange?: string; paragraphId?: string; bbox?: number[]; startTimeMs?: number; endTimeMs?: number; section?: string; rawText: string; normalizedText: string; confirmedText?: string; confidence?: number; reviewStatus: 'PENDING' | 'CONFIRMED' | 'IGNORED' | 'ISSUE' }
export interface ExtractedField { id: string; code: string; name: string; rawValue?: string; normalizedValue?: string; confirmedValue?: string; sourceUnit?: string; standardUnit?: string; confidence?: number; required: boolean; conflict: boolean; candidates?: unknown; reviewStatus: 'PENDING' | 'CONFIRMED' | 'IGNORED' | 'ISSUE' }
export interface ParseRun { id: string; documentId: string; versionId: string; runNo: number; status: string; parserVersion?: string; provider?: string; providerTaskId?: string; errorMessage?: string; createdAt: string }
export interface ParseIssue { id: string; blockId?: string; fieldId?: string; code: string; severity: 'INFO' | 'WARNING' | 'BLOCKER'; message: string; status: 'OPEN' | 'RESOLVED' | 'IGNORED'; resolution?: string }
export interface KnowledgeReview { documentId: string; title: string; documentType: string; libraryScope: 'INTERNAL' | 'EXTERNAL'; categoryId?: string; categoryName?: string; lifecycleStatus: string; versionId: string; versionNo: number; originalName: string; contentType: string; size: number; processingStatus: string; reviewStatus: string; reviewRevision: number; mediaProcessingConsent: boolean; sourceInfo: Record<string, unknown>; parseRun?: ParseRun; blocks: ParseBlock[]; fields: ExtractedField[]; issues: ParseIssue[]; tags: string[]; relations: BusinessObjectRef[] }
export interface ReviewQueueItem { documentId: string; title: string; documentType: string; versionId: string; versionNo: number; originalName: string; processingStatus: string; reviewStatus: string; reviewRevision: number; categoryName?: string; updatedAt: string }
export interface Publication { id: string; documentId: string; versionId: string; parseRunId: string; publicationNo: number; status: string; aiStatus: AiStatus; publishedAt: string }
export interface KnowledgePageListItem { id: string; objectRefId: string; objectType: string; externalId: string; objectName: string; title: string; draftTitle: string; summary: string; draftSummary: string; draftRevision: number; currentVersionNo?: number; currentSourceCount: number; availableSourceCount: number; hasUpdates: boolean; updatedAt: string }
export interface KnowledgePageSource { publicationId: string; documentId: string; documentTitle: string; versionId: string; versionNo: number; active: boolean; publishedAt: string }
export interface KnowledgePageVersion { id: string; versionNo: number; title: string; summary: string; publishedAt: string; sources: KnowledgePageSource[] }
export interface KnowledgePageView { page: KnowledgePageListItem; availableSources: KnowledgePageSource[]; versions: KnowledgePageVersion[] }
export interface BatchResult { documentId: string; success: boolean; errorCode?: string; message?: string }
export interface AuditEntry { id: string; actorId: string; action: string; aggregateType: string; aggregateId: string; detail: Record<string, unknown>; createdAt: string }

export const knowledgeApi = {
  async upload(file: File, title?: string, options: { libraryScope?: string; categoryId?: string } = {}) {
    const body = new FormData();
    body.append('file', file);
    if (title?.trim()) body.append('title', title.trim());
    if (options.libraryScope) body.append('libraryScope', options.libraryScope);
    if (options.categoryId) body.append('categoryId', options.categoryId);
    const response = await httpClient.post<ApiResponse<KnowledgeDocument>>('/api/v1/knowledge/documents', body);
    return response.data.data;
  },
  async list(params: { keyword?: string; status?: string; aiStatus?: string; scope?: string; categoryId?: string; lifecycleStatus?: string; reviewStatus?: string; page?: number; size?: number } = {}) {
    const response = await httpClient.get<ApiResponse<PageResponse<KnowledgeDocument>>>('/api/v1/knowledge/documents', { params });
    return response.data.data;
  },
  async categories(scope?: 'INTERNAL' | 'EXTERNAL') {
    const response = await httpClient.get<ApiResponse<KnowledgeCategory[]>>('/api/v1/knowledge/categories', { params: scope ? { scope } : undefined });
    return response.data.data;
  },
  async createCategory(input: { name: string; scope: 'INTERNAL' | 'EXTERNAL'; description?: string }) {
    const response = await httpClient.post<ApiResponse<KnowledgeCategory>>('/api/v1/knowledge/categories', input); return response.data.data;
  },
  async renameCategory(id: string, input: { name: string; description?: string }) {
    const response = await httpClient.put<ApiResponse<KnowledgeCategory>>(`/api/v1/knowledge/categories/${id}`, input); return response.data.data;
  },
  async deleteCategory(id: string, replacementCategoryId?: string) {
    await httpClient.delete(`/api/v1/knowledge/categories/${id}`, { params: replacementCategoryId ? { replacementCategoryId } : undefined });
  },
  async assignCategory(documentId: string, categoryId: string) {
    await httpClient.put(`/api/v1/knowledge/documents/${documentId}/category`, { categoryId });
  },
  async rename(id: string, title: string) {
    const response = await httpClient.put<ApiResponse<KnowledgeDocument>>(`/api/v1/knowledge/documents/${id}`, { title });
    return response.data.data;
  },
  async remove(id: string) {
    await httpClient.delete(`/api/v1/knowledge/documents/${id}`);
  },
  async exportDocuments(documentIds: string[]) {
    const response = await httpClient.post<Blob>('/api/v1/knowledge/documents/export', { documentIds }, { responseType: 'blob' });
    return response.data;
  },
  async get(id: string) {
    const response = await httpClient.get<ApiResponse<KnowledgeDocument>>(`/api/v1/knowledge/documents/${id}`);
    return response.data.data;
  },
  async versions(id: string) {
    const response = await httpClient.get<ApiResponse<KnowledgeVersion[]>>(`/api/v1/knowledge/documents/${id}/versions`);
    return response.data.data;
  },
  async contentBlob(documentId: string, versionId?: string) {
    const path = versionId
      ? `/api/v1/knowledge/documents/${documentId}/versions/${versionId}/content`
      : `/api/v1/knowledge/documents/${documentId}/content`;
    const response = await httpClient.get<Blob>(path, { responseType: 'blob' });
    return response.data;
  },
  async uploadVersion(id: string, file: File) {
    const body = new FormData();
    body.append('file', file);
    const response = await httpClient.post<ApiResponse<KnowledgeDocument>>(`/api/v1/knowledge/documents/${id}/versions`, body);
    return response.data.data;
  },
  async grant(id: string, action: 'APPROVE' | 'REJECT' | 'REVOKE', reason?: string) {
    const response = await httpClient.post<ApiResponse<KnowledgeDocument>>(`/api/v1/knowledge/documents/${id}/ai-grant`, { action, reason });
    return response.data.data;
  },
  async search(query: string, limit = 20, aiOnly = false) {
    const response = await httpClient.post<ApiResponse<{ files: Array<{ logicalDocumentId?: string; fileVersionId: string; title: string; originalName: string; hits: Array<{ id: string; snippet: string; score: number; anchor?: { pageNo?: number; section?: string } }> }> }>>('/api/v1/search/files', { query, aiOnly, limit });
    return response.data.data.files.flatMap((file) => file.logicalDocumentId ? file.hits.map((hit) => ({ chunkId: hit.id, documentId: file.logicalDocumentId!, versionId: file.fileVersionId, title: file.title, originalName: file.originalName, pageNo: hit.anchor?.pageNo, section: hit.anchor?.section, content: hit.snippet, score: hit.score })) : []).slice(0, Math.min(50, Math.max(1, limit)));
  },
  async preflight(fileId: string, documentType: string, objectRefIds: string[] = []) {
    const response = await httpClient.post<ApiResponse<UploadPreflight>>('/api/v1/knowledge/uploads/preflight', { fileId, documentType, objectRefIds });
    return response.data.data;
  },
  async createGoverned(input: { fileId: string; title?: string; documentType: string; libraryScope: string; categoryId: string; tags?: string[]; objectRefIds?: string[]; mediaProcessingConsent: boolean; resolution?: 'NEW_DOCUMENT' | 'NEW_VERSION'; targetDocumentId?: string; sourceInfo?: Record<string, unknown> }) {
    const response = await httpClient.post<ApiResponse<KnowledgeDocument>>('/api/v1/knowledge/documents', input);
    return response.data.data;
  },
  async reviewQueue(status?: string) {
    const response = await httpClient.get<ApiResponse<ReviewQueueItem[]>>('/api/v1/knowledge/review-queue', { params: status ? { status } : undefined }); return response.data.data;
  },
  async review(documentId: string, versionId: string) {
    const response = await httpClient.get<ApiResponse<KnowledgeReview>>(`/api/v1/knowledge/documents/${documentId}/versions/${versionId}/review`); return response.data.data;
  },
  async saveReview(review: KnowledgeReview) {
    const response = await httpClient.put<ApiResponse<KnowledgeReview>>(`/api/v1/knowledge/documents/${review.documentId}/versions/${review.versionId}/review`, {
      documentId: review.documentId, versionId: review.versionId, reviewRevision: review.reviewRevision,
      title: review.title, documentType: review.documentType, libraryScope: review.libraryScope,
      categoryId: review.categoryId, tags: review.tags, objectRefIds: review.relations.map((item) => item.id),
      blocks: review.blocks.map((item) => ({ id: item.id, confirmedText: item.confirmedText, reviewStatus: item.reviewStatus })),
      fields: review.fields.map((item) => ({ id: item.id, confirmedValue: item.confirmedValue, reviewStatus: item.reviewStatus })),
    }); return response.data.data;
  },
  async publish(documentId: string, versionId: string, reviewRevision: number) {
    const response = await httpClient.post<ApiResponse<Publication>>(`/api/v1/knowledge/documents/${documentId}/versions/${versionId}/publish`, { reviewRevision }); return response.data.data;
  },
  async reject(documentId: string, versionId: string, reviewRevision: number, reason: string) {
    await httpClient.post(`/api/v1/knowledge/documents/${documentId}/versions/${versionId}/reject`, { reviewRevision, reason });
  },
  async reparse(documentId: string, versionId: string, reviewRevision: number, mediaProcessingConsent?: boolean) {
    const response = await httpClient.post<ApiResponse<KnowledgeDocument>>(`/api/v1/knowledge/documents/${documentId}/versions/${versionId}/reparse`, { reviewRevision, mediaProcessingConsent }); return response.data.data;
  },
  async disable(documentId: string, reason: string) { await httpClient.post(`/api/v1/knowledge/documents/${documentId}/disable`, { reason }); },
  async restore(documentId: string) { await httpClient.post(`/api/v1/knowledge/documents/${documentId}/restore`); },
  async publicationAiUsage(publicationId: string, action: 'APPROVE' | 'REJECT' | 'REVOKE', reason?: string) {
    const response = await httpClient.put<ApiResponse<Publication>>(`/api/v1/knowledge/publications/${publicationId}/ai-usage`, { action, reason }); return response.data.data;
  },
  async batchMove(documentIds: string[], categoryId: string) { const response = await httpClient.post<ApiResponse<BatchResult[]>>('/api/v1/knowledge/documents/batch/move', { documentIds, categoryId }); return response.data.data; },
  async batchTags(documentIds: string[], add: string[], remove: string[]) { const response = await httpClient.post<ApiResponse<BatchResult[]>>('/api/v1/knowledge/documents/batch/tags', { documentIds, add, remove }); return response.data.data; },
  async batchAiUsage(documentIds: string[], action: 'APPROVE' | 'REJECT' | 'REVOKE', reason?: string) { const response = await httpClient.post<ApiResponse<BatchResult[]>>('/api/v1/knowledge/documents/batch/ai-usage', { documentIds, action, reason }); return response.data.data; },
  async businessObjects(params: { type?: string; keyword?: string } = {}) { const response = await httpClient.get<ApiResponse<BusinessObjectRef[]>>('/api/v1/business-objects', { params }); return response.data.data; },
  async createBusinessObject(input: { objectType: string; externalId: string; name: string; sourceSystem?: string; metadata?: Record<string, unknown> }) { const response = await httpClient.post<ApiResponse<BusinessObjectRef>>('/api/v1/business-objects', input); return response.data.data; },
  async pages() { const response = await httpClient.get<ApiResponse<KnowledgePageListItem[]>>('/api/v1/knowledge/pages'); return response.data.data; },
  async page(id: string) { const response = await httpClient.get<ApiResponse<KnowledgePageView>>(`/api/v1/knowledge/pages/${id}`); return response.data.data; },
  async savePageDraft(id: string, input: { title: string; summary: string; draftRevision: number }) { const response = await httpClient.put<ApiResponse<KnowledgePageView>>(`/api/v1/knowledge/pages/${id}/draft`, input); return response.data.data; },
  async publishPage(id: string, draftRevision: number) { const response = await httpClient.post<ApiResponse<KnowledgePageVersion>>(`/api/v1/knowledge/pages/${id}/publish`, { draftRevision }); return response.data.data; },
  async publications(documentId: string) { const response = await httpClient.get<ApiResponse<Publication[]>>(`/api/v1/knowledge/documents/${documentId}/publications`); return response.data.data; },
  async audit(documentId: string) { const response = await httpClient.get<ApiResponse<AuditEntry[]>>(`/api/v1/knowledge/documents/${documentId}/audit`); return response.data.data; },
  contentUrl(id: string, versionId?: string) {
    return `${appEnv.apiBaseUrl}/api/v1/knowledge/documents/${id}${versionId ? `/versions/${versionId}` : ''}/content`;
  },
};
