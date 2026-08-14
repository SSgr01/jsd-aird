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

  /** 文档内容预览地址：复用文件对象的内容下载接口。 */
  contentUrl(fileObjectId?: string) {
    if (!fileObjectId) return undefined;
    return `${appEnv.apiBaseUrl}/files/${fileObjectId}/content`;
  },
};
