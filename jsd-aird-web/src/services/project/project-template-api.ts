import type { TemplateFormat } from '@/features/template-workspace/types';
import { httpClient } from '@/services/http/client';
import type { ApiResponse } from '@/types/api';

export interface ProjectTemplateOption {
  templateId: string;
  versionId: string;
  templateCode: string;
  name: string;
  category?: string;
  versionNo: number;
  format: TemplateFormat;
}

export interface ProjectTemplateEditModel {
  templateId: string;
  versionId: string;
  templateCode: string;
  name: string;
  format: TemplateFormat;
  snapshot: Record<string, unknown>;
  snapshotHash?: string;
}

export const projectTemplateApi = {
  async list(projectId: string) {
    const response = await httpClient.get<ApiResponse<ProjectTemplateOption[]>>(
      `/api/v1/projects/${projectId}/templates`,
    );
    return response.data.data;
  },

  async getEditModel(projectId: string, versionId: string) {
    const response = await httpClient.get<ApiResponse<ProjectTemplateEditModel>>(
      `/api/v1/projects/${projectId}/templates/${versionId}/edit-model`,
    );
    return response.data.data;
  },
};
