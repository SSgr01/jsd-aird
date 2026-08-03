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
