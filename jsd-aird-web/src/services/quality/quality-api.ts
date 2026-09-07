import { httpClient } from '@/services/http/client';
import type { ApiResponse } from '@/types/api';

export type QualityTypeId = 'standard' | 'record' | 'coa' | 'defect' | 'manual' | 'msds' | 'label';
export type QualityUploadStatus = 'UPLOADED' | 'QUEUED' | 'PARSING' | 'DRAFT_CREATED' | 'FAILED';
export type QualityVisibility = 'ALL' | 'RND' | 'QUALITY' | 'PROJECT';
export interface QualityField {
  key: string;
  label: string;
  kind: 'text' | 'long' | 'date' | 'number' | 'select';
  required: boolean;
  options: string[];
}
export interface QualityType {
  id: QualityTypeId;
  name: string;
  prefix: string;
  fields: QualityField[];
}
export interface QualityCategory {
  id: string;
  businessType: QualityTypeId;
  name: string;
  description: string;
  systemDefault: boolean;
  sortOrder: number;
  recordCount: number;
  allowedActions?: string[];
}
export interface QualityRecord {
  id: string;
  businessType: QualityTypeId;
  categoryId: string;
  categoryName: string;
  businessNo: string;
  displayName?: string;
  data: Record<string, unknown>;
  sourceFileId?: string;
  sourceFileName?: string;
  projectId?: string;
  projectName?: string;
  stageId?: string;
  stageName?: string;
  taskId?: string;
  taskName?: string;
  visibility?: QualityVisibility;
  workbookSnapshot?: Record<string, unknown>;
  lockVersion: number;
  createdAt: string;
  updatedAt: string;
  allowedActions?: string[];
  newRow?: boolean;
}
export interface QualityRecordVersion {
  id: string;
  recordId: string;
  versionNo: number;
  businessNo: string;
  data: Record<string, unknown>;
  workbookSnapshot?: Record<string, unknown>;
  lockVersion: number;
  changeType: 'INITIAL' | 'EDIT' | 'UPLOAD' | 'PUBLISH';
  createdBy: string;
  createdAt: string;
}
export interface QualityUpload {
  id: string;
  fileId: string;
  categoryId: string;
  categoryName: string;
  originalName: string;
  contentType: string;
  size: number;
  status: QualityUploadStatus;
  generatedRecordId?: string;
  projectId?: string;
  projectName?: string;
  stageId?: string;
  stageName?: string;
  taskId?: string;
  taskName?: string;
  visibility: QualityVisibility;
  errorMessage?: string;
  createdAt: string;
  allowedActions?: string[];
}
export interface PageData<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}
export interface QualityUploadListParams {
  keyword?: string;
  status?: string;
  page: number;
  size: number;
  projectId?: string;
}
export interface QualityUploadInput {
  fileId: string;
  categoryId: string;
  originalName: string;
  contentType: string;
  size: number;
  sha256: string;
  projectId?: string;
  projectName?: string;
  stageId?: string;
  stageName?: string;
  taskId?: string;
  taskName?: string;
  visibility: QualityVisibility;
}
const data = <T>(r: { data: ApiResponse<T> }) => r.data.data;
export const qualityApi = {
  types: async () =>
    data(await httpClient.get<ApiResponse<QualityType[]>>('/api/v1/quality/data-types')),
  categories: async (type: QualityTypeId) =>
    data(
      await httpClient.get<ApiResponse<QualityCategory[]>>('/api/v1/quality/categories', {
        params: { type },
      }),
    ),
  createCategory: async (input: { type: QualityTypeId; name: string; description: string }) =>
    data(await httpClient.post<ApiResponse<QualityCategory>>('/api/v1/quality/categories', input)),
  updateCategory: async (id: string, input: { name: string; description: string }) =>
    data(
      await httpClient.put<ApiResponse<QualityCategory>>(`/api/v1/quality/categories/${id}`, input),
    ),
  deleteCategory: async (id: string) => httpClient.delete(`/api/v1/quality/categories/${id}`),
  records: async (params: {
    type: QualityTypeId;
    categoryId?: string;
    keyword?: string;
    page: number;
    size: number;
  }) =>
    data(
      await httpClient.get<ApiResponse<PageData<QualityRecord>>>('/api/v1/quality/records', {
        params,
      }),
    ),
  record: async (id: string) =>
    data(await httpClient.get<ApiResponse<QualityRecord>>(`/api/v1/quality/records/${id}`)),
  renameRecord: async (id: string, input: {
    revision: number;
    name: string;
    projectId?: string;
    projectName?: string;
    stageId?: string;
    stageName?: string;
    taskId?: string;
    taskName?: string;
  }) =>
    data(
      await httpClient.put<ApiResponse<QualityRecord>>(
        `/api/v1/quality/records/${id}/rename`,
        input,
      ),
    ),
  exportRecord: async (id: string) => {
    const response = await httpClient.get<Blob>(`/api/v1/quality/records/${id}/export`, {
      responseType: 'blob',
      headers: { Accept: '*/*' },
    });
    return response.data;
  },
  publishRecord: async (id: string) =>
    data(
      await httpClient.post<ApiResponse<QualityRecordVersion>>(
        `/api/v1/quality/records/${id}/publish`,
      ),
    ),
  deleteRecord: async (id: string) => httpClient.delete(`/api/v1/quality/records/${id}`),
  versions: async (id: string) =>
    data(
      await httpClient.get<ApiResponse<QualityRecordVersion[]>>(
        `/api/v1/quality/records/${id}/versions`,
      ),
    ),
  saveBatch: async (input: {
    type: QualityTypeId;
    categoryId: string;
    records: Array<{
      id?: string;
      businessNo: string;
      data: Record<string, unknown>;
      workbookSnapshot?: Record<string, unknown>;
      lockVersion: number;
    }>;
    deleteIds: string[];
  }) => httpClient.put('/api/v1/quality/records/batch', input),
  move: async (ids: string[], categoryId: string) =>
    httpClient.post('/api/v1/quality/records/move', { ids, categoryId }),
  createDefectFromRecord: async (id: string) =>
    data(await httpClient.post<ApiResponse<QualityRecord>>(`/api/v1/quality/records/${id}/create-defect`)),
  uploads: async (params: QualityUploadListParams) =>
    data(
      await httpClient.get<ApiResponse<PageData<QualityUpload>>>('/api/v1/quality/uploads', {
        params,
      }),
    ),
  createUpload: async (input: QualityUploadInput) =>
    data(await httpClient.post<ApiResponse<QualityUpload>>('/api/v1/quality/uploads', input)),
  deleteUpload: async (id: string) => httpClient.delete(`/api/v1/quality/uploads/${id}`),
  retryUpload: async (id: string) =>
    data(await httpClient.post<ApiResponse<QualityUpload>>(`/api/v1/quality/uploads/${id}/retry`)),
};
