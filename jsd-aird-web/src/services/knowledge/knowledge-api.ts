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
}

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
  async upload(file: File, title?: string) {
    const body = new FormData();
    body.append('file', file);
    if (title?.trim()) body.append('title', title.trim());
    const response = await httpClient.post<ApiResponse<KnowledgeDocument>>('/api/v1/knowledge/documents', body);
    return response.data.data;
  },
  async list(params: { keyword?: string; status?: string; aiStatus?: string; page?: number; size?: number } = {}) {
    const response = await httpClient.get<ApiResponse<PageResponse<KnowledgeDocument>>>('/api/v1/knowledge/documents', { params });
    return response.data.data;
  },
  async get(id: string) {
    const response = await httpClient.get<ApiResponse<KnowledgeDocument>>(`/api/v1/knowledge/documents/${id}`);
    return response.data.data;
  },
  async versions(id: string) {
    const response = await httpClient.get<ApiResponse<KnowledgeVersion[]>>(`/api/v1/knowledge/documents/${id}/versions`);
    return response.data.data;
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
