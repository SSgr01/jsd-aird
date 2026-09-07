import { httpClient } from '@/services/http/client';
import type { ApiResponse } from '@/types/api';

export type ProductionUploadVisibility = 'ALL' | 'QUALITY' | 'PROJECT';

export interface ProductionUpload {
  id: string;
  fileId: string;
  originalName: string;
  contentType: string;
  size: number;
  sha256: string;
  productionName?: string;
  orderNo?: string;
  productName?: string;
  category?: string;
  manufactureDate?: string;
  projectId?: string;
  projectName?: string;
  stageId?: string;
  stageName?: string;
  taskId?: string;
  taskName?: string;
  visibility: ProductionUploadVisibility;
  sourceType: 'XLSX' | 'PHOTO';
  status:
    | 'QUEUED'
    | 'PARSING'
    | 'MATCHING_TEMPLATE'
    | 'EXTRACTING'
    | 'REVIEW_REQUIRED'
    | 'SAVED'
    | 'PUBLISHED'
    | 'FAILED'
    | 'DELETED';
  createdBy: string;
  createdAt: string;
  workbookSnapshot?: Record<string, unknown>;
  recognitionProgress: number;
  currentStage?: string;
  selectedTemplateVersionId?: string;
  matchMode?:
    'EXACT_MANIFEST' | 'SIMILAR_AUTO' | 'USER_REVIEW' | 'USER_SELECTED_TEMPLATE' | 'NO_TEMPLATE';
  templateMatchScore?: number;
  recognitionResult?: {
    sourceType?: 'XLSX' | 'PHOTO';
    model?: string;
    message?: string;
    data?: Record<string, unknown>;
    items?: Array<Record<string, unknown>>;
    issues?: Array<Record<string, unknown>>;
    templateCandidates?: Array<{
      templateVersionId: string;
      templateCode: string;
      templateName: string;
      score: number;
      exact: boolean;
    }>;
    requiresTemplateSelection?: boolean;
  };
  structureSummary?: Record<string, unknown>;
  failureMessage?: string;
  lockVersion: number;
  updatedBy?: string;
  updatedAt: string;
  allowedActions?: string[];
}

export interface ProductionUploadVersion {
  id: string;
  uploadId: string;
  versionNo: number;
  workbookSnapshot: Record<string, unknown>;
  lockVersion: number;
  changeType: 'PUBLISH';
  createdBy: string;
  createdAt: string;
}

export interface ProductionUploadField {
  id: string;
  itemKey: string;
  itemKind: string;
  bindingId?: string;
  fieldCode?: string;
  dataPath?: string;
  recordIndex?: number;
  rawValue?: unknown;
  normalizedValue?: unknown;
  sourceLocator?: Record<string, unknown>;
  confidence: number;
  reviewStatus: string;
}

export interface ProductionUploadPage {
  items: ProductionUpload[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

export interface CreateProductionUploadInput {
  fileId: string;
  sourceType?: 'XLSX' | 'PHOTO';
  templateVersionId?: string;
  replaceExisting?: boolean;
  productionName?: string;
  orderNo?: string;
  productName?: string;
  category?: string;
  manufactureDate?: string;
  projectId?: string;
  projectName?: string;
  stageId?: string;
  stageName?: string;
  taskId?: string;
  taskName?: string;
  visibility: ProductionUploadVisibility;
}

export const productionUploadApi = {
  async get(id: string) {
    const response = await httpClient.get<ApiResponse<ProductionUpload>>(
      `/api/v1/production-uploads/${id}`,
    );
    return response.data.data;
  },

  async list(params: {
    keyword?: string;
    status?: string;
    projectId?: string;
    viewableOnly?: boolean;
    page: number;
    size: number;
  }) {
    const response = await httpClient.get<ApiResponse<ProductionUploadPage>>(
      '/api/v1/production-uploads',
      { params },
    );
    return response.data.data;
  },

  async create(input: CreateProductionUploadInput) {
    const response = await httpClient.post<ApiResponse<ProductionUpload>>(
      '/api/v1/production-uploads',
      input,
    );
    return response.data.data;
  },

  async rename(id: string, input: {
    revision: number;
    name: string;
    projectId?: string;
    projectName?: string;
    stageId?: string;
    stageName?: string;
    taskId?: string;
    taskName?: string;
  }) {
    const response = await httpClient.put<ApiResponse<ProductionUpload>>(
      `/api/v1/production-uploads/${id}/rename`,
      input,
    );
    return response.data.data;
  },

  async delete(id: string) {
    await httpClient.delete(`/api/v1/production-uploads/${id}`);
  },

  async fields(id: string) {
    const response = await httpClient.get<ApiResponse<ProductionUploadField[]>>(
      `/api/v1/production-uploads/${id}/fields`,
    );
    return response.data.data;
  },

  async selectTemplate(id: string, templateVersionId: string) {
    const response = await httpClient.post<ApiResponse<ProductionUpload>>(
      `/api/v1/production-uploads/${id}/select-template`,
      { templateVersionId },
    );
    return response.data.data;
  },

  async retry(id: string) {
    const response = await httpClient.post<ApiResponse<ProductionUpload>>(
      `/api/v1/production-uploads/${id}/retry`,
    );
    return response.data.data;
  },
};

export const productionOrderRecordApi = {
  async get(id: string) {
    const response = await httpClient.get<ApiResponse<ProductionUpload>>(
      `/api/v1/production-orders/records/${id}`,
    );
    return response.data.data;
  },

  async saveBatch(input: {
    records: Array<{
      id: string;
      workbookSnapshot?: Record<string, unknown>;
      lockVersion: number;
      productionName?: string;
      orderNo?: string;
      productName?: string;
      category?: string;
      manufactureDate?: string;
    }>;
  }) {
    await httpClient.put('/api/v1/production-orders/records/batch', input);
  },

  async versions(id: string) {
    const response = await httpClient.get<ApiResponse<ProductionUploadVersion[]>>(
      `/api/v1/production-orders/records/${id}/versions`,
    );
    return response.data.data;
  },

  async publish(id: string) {
    const response = await httpClient.post<ApiResponse<ProductionUploadVersion>>(
      `/api/v1/production-orders/records/${id}/publish`,
    );
    return response.data.data;
  },
};
