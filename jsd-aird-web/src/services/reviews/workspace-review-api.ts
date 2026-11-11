import { httpClient } from '@/services/http/client';

export type WorkspaceReviewType = 'PROJECT_DOCUMENT' | 'QUALITY_RECORD' | 'PRODUCTION_ORDER' | 'PRODUCTION_UPLOAD' | 'KNOWLEDGE_VERSION';
export interface WorkspaceReviewEvent {
  action: 'SUBMITTED' | 'APPROVED' | 'REJECTED';
  comment?: string;
  operatorId: string;
  operatorName?: string;
  createdAt: string;
}
export interface WorkspaceReviewState {
  status: 'NOT_SUBMITTED' | 'SUBMITTED' | 'APPROVED' | 'REJECTED';
  submittedAt?: string;
  approvedAt?: string;
  reviewComment?: string;
}

const path = (type: WorkspaceReviewType, id: string) => `/api/v1/workspace-reviews/${type}/${id}`;

export const workspaceReviewApi = {
  async get(type: WorkspaceReviewType, id: string) {
    const response = await httpClient.get<{ data: WorkspaceReviewState }>(path(type, id));
    return response.data.data;
  },
  async history(type: WorkspaceReviewType, id: string) {
    const response = await httpClient.get<{ data: WorkspaceReviewEvent[] }>(`${path(type, id)}/history`);
    return response.data.data;
  },
  async submit(type: WorkspaceReviewType, id: string) {
    const response = await httpClient.post<{ data: WorkspaceReviewState }>(`${path(type, id)}/submit`);
    return response.data.data;
  },
  async approve(type: WorkspaceReviewType, id: string) {
    const response = await httpClient.post<{ data: WorkspaceReviewState }>(`${path(type, id)}/approve`);
    return response.data.data;
  },
  async reject(type: WorkspaceReviewType, id: string, reason: string) {
    const response = await httpClient.post<{ data: WorkspaceReviewState }>(`${path(type, id)}/reject`, { reason });
    return response.data.data;
  },
};
