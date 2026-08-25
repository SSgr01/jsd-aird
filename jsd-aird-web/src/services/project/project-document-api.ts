import { appEnv } from '@/app/config/env';
import { httpClient } from '@/services/http/client';
import type { ApiResponse } from '@/types/api';

export type ProjectDocumentFormat = 'DOCX' | 'XLSX' | 'OTHER';
export type ProjectDocumentSource = 'TEMPLATE' | 'BLANK' | 'IMPORT';
export type ProjectDocumentStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';

export interface ProjectDocumentSummary {
  id: string;
  title: string;
  format: ProjectDocumentFormat;
  source: ProjectDocumentSource;
  status: ProjectDocumentStatus;
  templateId?: string;
  templateVersionId?: string;
  templateName?: string;
  fileObjectId?: string;
  createdAt: string;
  createdBy: string;
}

export interface ProjectDocumentDetail extends ProjectDocumentSummary {
  projectId: string;
  updatedAt: string;
  updatedBy?: string;
  contentSnapshot?: Record<string, unknown>;
  contentSchema?: Record<string, unknown>;
  contentMapping?: unknown[];
  contentData?: Record<string, unknown>;
  contentStructure?: Record<string, unknown>;
}

export interface CreateProjectDocumentInput {
  title: string;
  format: ProjectDocumentFormat;
  source: ProjectDocumentSource;
  templateId?: string;
  templateVersionId?: string;
  fileObjectId?: string;
}

export interface ProjectDocumentVersion {
  id: string;
  documentId: string;
  versionNo: number;
  status: ProjectDocumentStatus;
  contentJsonb: Record<string, unknown>;
  snapshotReason?: string;
  createdBy?: string;
  createdAt: string;
}

export interface ProjectDocumentAudit {
  id: string;
  documentId: string;
  action: string;
  beforeJsonb?: Record<string, unknown>;
  afterJsonb?: Record<string, unknown>;
  operatorId?: string;
  operatorName?: string;
  createdAt: string;
}

export const projectDocumentApi = {
  async list(projectId: string) {
    const response = await httpClient.get<ApiResponse<ProjectDocumentSummary[]>>(
      `/api/v1/projects/${projectId}/documents`,
    );
    return response.data.data;
  },

  async get(projectId: string, documentId: string) {
    const response = await httpClient.get<ApiResponse<ProjectDocumentDetail>>(
      `/api/v1/projects/${projectId}/documents/${documentId}`,
    );
    return response.data.data;
  },

  async create(projectId: string, input: CreateProjectDocumentInput) {
    const response = await httpClient.post<ApiResponse<string>>(
      `/api/v1/projects/${projectId}/documents`,
      input,
    );
    return response.data.data;
  },

  async remove(projectId: string, documentId: string) {
    const response = await httpClient.delete<ApiResponse<void>>(
      `/api/v1/projects/${projectId}/documents/${documentId}`,
    );
    return response.data.data;
  },

  async exportDocument(projectId: string, documentId: string) {
    const response = await httpClient.get<Blob>(
      `/api/v1/projects/${projectId}/documents/${documentId}/export`,
      { responseType: 'blob', headers: { Accept: '*/*' } },
    );
    return response.data;
  },

  async importDocument(projectId: string, input: { title: string; format: 'DOCX' | 'XLSX'; fileObjectId: string }) {
    const response = await httpClient.post<ApiResponse<string>>(`/api/v1/projects/${projectId}/documents/import`, input);
    return response.data.data;
  },

  async saveContent(projectId: string, documentId: string, input: {
    snapshot: Record<string, unknown>;
    schema: Record<string, unknown>;
    mapping: unknown[];
    data: Record<string, unknown>;
  }) {
    const response = await httpClient.put<ApiResponse<Record<string, unknown>>>(
      `/api/v1/projects/${projectId}/documents/${documentId}/content`,
      input,
    );
    return response.data.data;
  },

  async publish(projectId: string, documentId: string, input: {
    snapshot: Record<string, unknown>;
    schema: Record<string, unknown>;
    mapping: unknown[];
    data: Record<string, unknown>;
  }) {
    const response = await httpClient.post<ApiResponse<ProjectDocumentDetail>>(
      `/api/v1/projects/${projectId}/documents/${documentId}/publish`,
      input,
    );
    return response.data.data;
  },

  /** 文档内容预览地址：复用文件对象的内容下载接口。 */
  contentUrl(fileObjectId?: string) {
    if (!fileObjectId) return undefined;
    return `${appEnv.apiBaseUrl}/files/${fileObjectId}/content`;
  },

  async listVersions(projectId: string, documentId: string) {
    const response = await httpClient.get<ApiResponse<ProjectDocumentVersion[]>>(
      `/api/v1/projects/${projectId}/documents/${documentId}/versions`,
    );
    return response.data.data;
  },

  async compareVersions(projectId: string, documentId: string, from: number, to: number) {
    const response = await httpClient.get<ApiResponse<{ before: Record<string, unknown>; after: Record<string, unknown> }>>(
      `/api/v1/projects/${projectId}/documents/${documentId}/versions/compare`,
      { params: { from, to } },
    );
    return response.data.data;
  },

  async listAudits(projectId: string, documentId: string) {
    const response = await httpClient.get<ApiResponse<ProjectDocumentAudit[]>>(
      `/api/v1/projects/${projectId}/documents/${documentId}/audits`,
    );
    return response.data.data;
  },
};
