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
}

export interface KnowledgeCategory { id: string; scope: 'INTERNAL' | 'EXTERNAL'; name: string; sortOrder: number; documentCount: number }

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
  async list(params: { keyword?: string; status?: string; aiStatus?: string; scope?: string; categoryId?: string; page?: number; size?: number } = {}) {
    const response = await httpClient.get<ApiResponse<PageResponse<KnowledgeDocument>>>('/api/v1/knowledge/documents', { params });
    return response.data.data;
  },
  async categories(scope?: 'INTERNAL' | 'EXTERNAL') {
    const response = await httpClient.get<ApiResponse<KnowledgeCategory[]>>('/api/v1/knowledge/categories', { params: scope ? { scope } : undefined });
    return response.data.data;
  },
  async createCategory(input: { name: string; scope: 'INTERNAL' | 'EXTERNAL' }) {
    const response = await httpClient.post<ApiResponse<KnowledgeCategory>>('/api/v1/knowledge/categories', input); return response.data.data;
  },
  async renameCategory(id: string, name: string) {
    const response = await httpClient.put<ApiResponse<KnowledgeCategory>>(`/api/v1/knowledge/categories/${id}`, { name }); return response.data.data;
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
  async reindex(id: string) {
    const response = await httpClient.post<ApiResponse<KnowledgeDocument>>(`/api/v1/knowledge/documents/${id}/reindex`);
    return response.data.data;
  },
  async search(query: string, limit = 20, aiOnly = false) {
    const response = await httpClient.post<ApiResponse<{ knowledgeHits: KnowledgeSearchHit[] }>>('/api/v1/assistant/file-search', { query, aiOnly });
    return response.data.data.knowledgeHits.slice(0, Math.min(50, Math.max(1, limit)));
  },
  contentUrl(id: string) {
    return `${appEnv.apiBaseUrl}/api/v1/knowledge/documents/${id}/content`;
  },
};
