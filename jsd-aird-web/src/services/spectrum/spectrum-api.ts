import type { ApiResponse, PageResponse } from '@/types/api';
import { httpClient } from '@/services/http/client';
import { downloadBlob } from '@/services/files/file-api';

export interface SpectrumFieldDefinition {
  key: string;
  label: string;
}
export interface SpectrumTestMethodSnapshot {
  schemaVersion: 1;
  methodType: string;
  presetId?: string;
  presetRevision?: string;
  presetName?: string;
  instrument: Record<string, unknown>;
  conditions: Record<string, unknown>;
  notes?: string;
  legacyText?: string;
}
export interface SpectrumMethodField {
  key: string; label: string; target?: 'instrument' | 'conditions';
  kind?: 'number' | 'integer' | 'program' | 'specimens';
  unit?: string; min?: number; max?: number; variable?: boolean; options?: string[];
  placeholder?: string; when?: string;
}
export interface SpectrumMethodSchema {
  code: string; name: string; fields: SpectrumMethodField[]; comparisonKeys: string[];
}
export interface SpectrumMethodPreset {
  id: string; revision: string; name: string; methodType: string; version: number;
  active: boolean; snapshot: SpectrumTestMethodSnapshot;
}
export interface SpectrumMethodCatalog {
  methods: SpectrumMethodSchema[]; presets: SpectrumMethodPreset[]; canManage: boolean;
}
export interface SpectrumCategory {
  revision: string;
  id: string;
  code: string;
  name: string;
  description?: string;
  analysisHint?: string;
  fields: SpectrumFieldDefinition[];
  sortOrder: number;
  systemCategory: boolean;
  chartCount: number;
  allowedActions?: string[];
}
export interface SpectrumChart {
  revision: string;
  id: string;
  categoryId: string;
  categoryCode: string;
  categoryName: string;
  fileObjectId: string;
  title: string;
  originalName: string;
  contentType: string;
  size: number;
  sha256: string;
  sampleName?: string;
  batchNo?: string;
  testConditions?: string;
  metadata: Record<string, unknown>;
  pageCount: number;
  status: string;
  createdAt: string;
  updatedAt: string;
  allowedActions?: string[];
}
export interface SpectrumUploadAttempt {
  requestId: string;
  categoryId?: string;
  chartId?: string;
  fileObjectId?: string;
  originalName: string;
  size: number;
  status: 'SUCCEEDED' | 'FAILED' | 'DUPLICATE' | 'CANCELLED';
  errorCode?: string;
  errorMessage?: string;
  uploadContext: Record<string, unknown>;
  parentRequestId?: string;
  createdAt: string;
}
export interface SpectrumPage {
  pageNo: number;
  pageCount: number;
}
export const spectrumApi = {
  async methodPresets() {
    return (await httpClient.get<ApiResponse<SpectrumMethodCatalog>>('/api/v1/spc/method-presets')).data.data;
  },
  async saveMethodPreset(input: { name: string; snapshot: SpectrumTestMethodSnapshot; active?: boolean; revision?: string }, id?: string) {
    const response = id
      ? await httpClient.put<ApiResponse<SpectrumMethodPreset>>(`/api/v1/spc/method-presets/${id}`, input)
      : await httpClient.post<ApiResponse<SpectrumMethodPreset>>('/api/v1/spc/method-presets', input);
    return response.data.data;
  },
  async categories() {
    const response =
      await httpClient.get<ApiResponse<SpectrumCategory[]>>('/api/v1/spc/categories');
    return response.data.data;
  },
  async createCategory(input: {
    code?: string;
    name: string;
    description?: string;
    analysisHint?: string;
    fields?: SpectrumFieldDefinition[];
  }) {
    const response = await httpClient.post<ApiResponse<SpectrumCategory>>(
      '/api/v1/spc/categories',
      input,
    );
    return response.data.data;
  },
  async updateCategory(
    id: string,
    input: {
      revision: string;
      name: string;
      description?: string;
      analysisHint?: string;
      fields?: SpectrumFieldDefinition[];
    },
  ) {
    const response = await httpClient.put<ApiResponse<SpectrumCategory>>(
      `/api/v1/spc/categories/${id}`,
      input,
    );
    return response.data.data;
  },
  async assignChartCategory(chartId: string, categoryId: string, revision: string) {
    const response = await httpClient.put<ApiResponse<SpectrumChart>>(
      `/api/v1/spc/charts/${chartId}/category`,
      { categoryId, revision },
    );
    return response.data.data;
  },
  async deleteCategory(id: string, revision: string, replacementCategoryId?: string) {
    const params: Record<string, string> = { revision };
    if (replacementCategoryId) params.replacementCategoryId = replacementCategoryId;
    await httpClient.delete(`/api/v1/spc/categories/${id}`, { params });
  },
  async listCharts(
    params: {
      keyword?: string;
      categoryId?: string;
      status?: string;
      page?: number;
      size?: number;
    } = {},
    signal?: AbortSignal,
  ) {
    const response = await httpClient.get<ApiResponse<PageResponse<SpectrumChart>>>(
      '/api/v1/spc/charts',
      { params, signal },
    );
    return response.data.data;
  },
  async createChart(input: {
    requestId: string;
    fileId: string;
    title?: string;
    categoryId: string;
    sampleName?: string;
    batchNo?: string;
    testConditions?: string;
    metadata?: Record<string, unknown>;
    originalName: string;
    size: number;
    duplicateOverride?: boolean;
    uploadContext?: Record<string, unknown>;
    parentRequestId?: string;
  }) {
    const response = await httpClient.post<ApiResponse<SpectrumChart>>('/api/v1/spc/charts', input);
    return response.data.data;
  },
  async chart(id: string) {
    const response = await httpClient.get<ApiResponse<SpectrumChart>>(`/api/v1/spc/charts/${id}`);
    return response.data.data;
  },
  async updateChart(
    id: string,
    input: {
      revision: string;
      title: string;
      categoryId?: string;
      sampleName?: string;
      batchNo?: string;
      testConditions?: string;
      metadata?: Record<string, unknown>;
    },
  ) {
    const response = await httpClient.put<ApiResponse<SpectrumChart>>(
      `/api/v1/spc/charts/${id}`,
      input,
    );
    return response.data.data;
  },
  async deleteChart(id: string, revision: string) {
    await httpClient.delete(`/api/v1/spc/charts/${id}`, { params: { revision } });
  },
  async uploadAttempts(limit = 100) {
    const response = await httpClient.get<ApiResponse<SpectrumUploadAttempt[]>>(
      '/api/v1/spc/uploads/attempts', { params: { limit } },
    );
    return response.data.data;
  },
  async recordUploadAttempt(input: {
    requestId: string;
    categoryId?: string;
    fileObjectId?: string;
    originalName: string;
    size: number;
    status: 'FAILED' | 'DUPLICATE' | 'CANCELLED';
    errorCode?: string;
    errorMessage?: string;
    uploadContext?: Record<string, unknown>;
    parentRequestId?: string;
  }) {
    await httpClient.post('/api/v1/spc/uploads/attempts', input);
  },
  async hideUploadAttempt(requestId: string) {
    await httpClient.delete(`/api/v1/spc/uploads/attempts/${requestId}`);
  },
  async exportCharts(chartIds: string[]) {
    const response = await httpClient.post<Blob>('/api/v1/spc/charts/export', { chartIds }, {
      responseType: 'blob', headers: { Accept: '*/*' }, timeout: 0,
    });
    const encodedName = /filename\*=UTF-8''([^;]+)/i.exec(String(response.headers['content-disposition'] || ''))?.[1];
    downloadBlob(response.data, encodedName ? decodeURIComponent(encodedName) : '图谱文件.zip');
  },
  async pages(id: string) {
    const response = await httpClient.get<ApiResponse<SpectrumPage[]>>(
      `/api/v1/spc/charts/${id}/pages`,
    );
    return response.data.data;
  },
  async contentBlob(id: string) {
    const response = await httpClient.get<Blob>(`/api/v1/spc/charts/${id}/content`, {
      responseType: 'blob',
    });
    return response.data;
  },
  async previewBlob(id: string) {
    const response = await httpClient.get<Blob>(`/api/v1/spc/charts/${id}/preview`, {
      responseType: 'blob',
    });
    return response.data;
  },
  contentUrl(id: string) {
    return `/api/v1/spc/charts/${id}/content`;
  },
};
