import type {
  ProductionOrderListItem,
  ProductionWorkspace,
} from '@/features/production-orders/types';
import type { TemplateFormat } from '@/features/template-workspace/types';
import { httpClient } from '@/services/http/client';
import type { ApiResponse } from '@/types/api';

export interface CreateProductionOrderInput {
  orderNo: string;
  templateVersionId: string;
  quantity?: number;
  unitCode?: string;
  plannedDate?: string;
}

export interface ProductionIngestItem {
  id: string;
  itemKey: string;
  itemKind: 'SCALAR' | 'DETAIL' | 'MATRIX';
  fieldCode?: string;
  dataPath: string;
  recordIndex?: number;
  normalizedValue: unknown;
  sourceLocator: Record<string, unknown>;
  confidence: number;
  reviewStatus: 'EXTRACTED' | 'NEEDS_REVIEW' | 'CONFIRMED';
}

export interface ProductionIngestJob {
  id: string;
  sourceType: 'XLSX' | 'PHOTO';
  selectedTemplateVersionId?: string;
  matchMode?: string;
  status: 'QUEUED' | 'PROCESSING' | 'REVIEW_REQUIRED' | 'CONFIRMED' | 'FAILED' | 'CANCELLED';
  templateMatchScore?: number;
  resultVersion: number;
  sourceFileIds: string[];
  result?: {
    data?: Record<string, unknown>;
    requiresTemplateSelection?: boolean;
    templateCandidates?: Array<{
      templateVersionId: string;
      templateCode: string;
      templateName: string;
      score: number;
    }>;
    issues?: unknown[];
  };
  errorMessage?: string;
  items: ProductionIngestItem[];
}

export const productionOrderApi = {
  async list() {
    const response = await httpClient.get<ApiResponse<ProductionOrderListItem[]>>(
      '/api/v2/production-orders',
    );
    return response.data.data;
  },

  async create(input: CreateProductionOrderInput) {
    const response = await httpClient.post<ApiResponse<ProductionWorkspace>>(
      '/api/v2/production-orders',
      input,
    );
    return response.data.data;
  },

  async getEditModel(orderId: string) {
    const response = await httpClient.get<ApiResponse<ProductionWorkspace>>(
      `/api/v2/production-orders/${orderId}/edit-model`,
    );
    return response.data.data;
  },

  async saveDraft(
    orderId: string,
    input: {
      lockVersion: number;
      baseWorkspaceHash: string;
      schema: Record<string, unknown>;
      mapping: ProductionWorkspace['mapping'];
      data: Record<string, unknown>;
      snapshotFileId: string;
      snapshotHash: string;
      editorAppVersion: string;
      pluginManifest: string;
      snapshotFormatVersion: number;
      bindingValues: Array<{ dataPath: string; dataValue: unknown; editorValue: unknown }>;
    },
  ) {
    const response = await httpClient.put<
      ApiResponse<{ lockVersion: number; workspaceHash: string; reconciliationRequired: boolean }>
    >(`/api/v2/production-orders/${orderId}/draft`, input);
    return response.data.data;
  },

  async submit(orderId: string) {
    const response = await httpClient.post<ApiResponse<{ revisionId: string }>>(
      `/api/v2/production-orders/${orderId}/submit`,
    );
    return response.data.data;
  },

  async cancel(orderId: string) {
    await httpClient.post(`/api/v2/production-orders/${orderId}/cancel`);
  },

  async delete(orderId: string) {
    await httpClient.delete(`/api/v2/production-orders/${orderId}`);
  },

  async stageInstanceSource(file: File, sourceType: 'XLSX' | 'PHOTO') {
    const body = new FormData();
    body.append('file', file);
    const kind = sourceType === 'XLSX' ? 'OFFICE' : 'INSTANCE_SOURCE';
    const response = await httpClient.post<
      ApiResponse<{ fileId: string; sha256: string; status: 'STAGED' }>
    >(`/api/v2/files/staged?kind=${kind}`, body);
    return response.data.data;
  },

  async createIngestJob(
    orderId: string,
    input: { sourceType: 'XLSX' | 'PHOTO'; sourceFileIds: string[]; requestedTemplateVersionId: string },
  ) {
    const response = await httpClient.post<ApiResponse<ProductionIngestJob>>(
      `/api/v2/production-orders/${orderId}/ingest-jobs`,
      input,
    );
    return response.data.data;
  },

  async getIngestJob(orderId: string, jobId: string) {
    const response = await httpClient.get<ApiResponse<ProductionIngestJob>>(
      `/api/v2/production-orders/${orderId}/ingest-jobs/${jobId}`,
    );
    return response.data.data;
  },

  async confirmIngestJob(
    orderId: string,
    jobId: string,
    input: {
      baseWorkspaceHash: string;
      lockVersion: number;
      resultVersion: number;
      selectedTemplateVersionId?: string;
      correctedData: Record<string, unknown>;
    },
  ) {
    const response = await httpClient.post<
      ApiResponse<{ lockVersion: number; workspaceHash: string; reconciliationRequired: boolean }>
    >(`/api/v2/production-orders/${orderId}/ingest-jobs/${jobId}/confirm`, input);
    return response.data.data;
  },

  async cancelIngestJob(orderId: string, jobId: string) {
    await httpClient.post(`/api/v2/production-orders/${orderId}/ingest-jobs/${jobId}/cancel`);
  },

  async downloadInstanceXlsx(templateVersionId: string) {
    const response = await httpClient.get<Blob>(
      `/api/v2/template-versions/${templateVersionId}/instance-xlsx`,
      { responseType: 'blob' },
    );
    const url = URL.createObjectURL(response.data);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = 'production-instance-template.xlsx';
    anchor.click();
    URL.revokeObjectURL(url);
  },

  async listRevisions(orderId: string) {
    const response = await httpClient.get<ApiResponse<Array<{
      revisionId: string; revisionNo: number; status: string; createdAt: string; dataHash: string;
    }>>>(`/api/v2/production-orders/${orderId}/revisions`);
    return response.data.data;
  },

  async checkExport(orderId: string, format: TemplateFormat, revisionId?: string) {
    const response = await httpClient.get<ApiResponse<{ canDownload: boolean; warnings: Array<{ code: string; bindingId?: string; dataPath?: string; message: string }> }>>(
      `/api/v2/production-orders/${orderId}/export/check`, { params: { format, revisionId } },
    );
    return response.data.data;
  },

  async exportOffice(orderId: string, format: TemplateFormat, revisionId?: string) {
    const response = await httpClient.get<Blob>(
      `/api/v2/production-orders/${orderId}/export`, { params: { format, revisionId }, responseType: 'blob' },
    );
    const url = URL.createObjectURL(response.data);
    const anchor = document.createElement('a'); anchor.href = url;
    anchor.download = revisionId
      ? `production-revision-${revisionId}.${format === 'XLSX' ? 'xlsx' : 'docx'}`
      : `production-draft.${format === 'XLSX' ? 'xlsx' : 'docx'}`;
    anchor.click(); window.setTimeout(() => URL.revokeObjectURL(url), 0);
  },

  async stageSnapshot(snapshot: Record<string, unknown>, format: TemplateFormat) {
    const body = new FormData();
    body.append(
      'file',
      new Blob([JSON.stringify(snapshot)], { type: 'application/json' }),
      `${format.toLowerCase()}-production-snapshot.json`,
    );
    const response = await httpClient.post<
      ApiResponse<{ fileId: string; sha256: string; status: 'STAGED' }>
    >('/api/v2/files/staged?kind=SNAPSHOT', body);
    return response.data.data;
  },

  async downloadSnapshot(fileId: string) {
    const response = await httpClient.get<Record<string, unknown>>(
      `/api/v2/files/${fileId}/content`,
    );
    return response.data;
  },
};
