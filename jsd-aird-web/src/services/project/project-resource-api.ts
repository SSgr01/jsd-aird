import { httpClient } from '@/services/http/client';
import type { ApiResponse, PageResponse } from '@/types/api';

export type ProjectResourceType = 'KNOWLEDGE_DOCUMENT' | 'DATA_IMPORT_JOB';

export interface ProjectRelationTarget {
  projectId: string;
  stageId?: string;
  taskId?: string;
}

export interface RelatedProjectView extends ProjectRelationTarget {
  projectCode: string;
  projectName: string;
  stageName?: string;
  taskName?: string;
}

export interface ProjectReference {
  id: string;
  projectId: string;
  stageId?: string;
  stageName?: string;
  taskId?: string;
  taskName?: string;
  resourceType: ProjectResourceType;
  resourceId: string;
  fileVersionId?: string;
  fileObjectId?: string;
  sourceModule: 'KNOWLEDGE' | 'DATA_CENTER';
  title: string;
  originalName?: string;
  contentType?: string;
  size: number;
  summary?: string;
  status: 'ACTIVE' | 'REMOVED';
  addedBy: string;
  addedByName?: string;
  addedAt: string;
  removedBy?: string;
  removedAt?: string;
  sourceAvailable: boolean;
}

export const projectResourceApi = {
  async links(resourceType: ProjectResourceType, resourceId: string) {
    const response = await httpClient.get<ApiResponse<RelatedProjectView[]>>('/api/v1/project-resource-links', {
      params: { resourceType, resourceId },
    });
    return response.data.data;
  },
  async replaceLinks(resourceType: ProjectResourceType, resourceId: string, targets: ProjectRelationTarget[]) {
    const response = await httpClient.put<ApiResponse<RelatedProjectView[]>>(
      `/api/v1/project-resource-links/${resourceType}/${resourceId}`,
      { targets },
    );
    return response.data.data;
  },
  async addReferences(resourceType: ProjectResourceType, resourceId: string, targets: ProjectRelationTarget[], summary?: string) {
    const response = await httpClient.post<ApiResponse<ProjectReference[]>>('/api/v1/project-references', {
      resourceType,
      resourceId,
      targets,
      summary,
    });
    return response.data.data;
  },
  async references(projectId: string, params: {
    keyword?: string;
    sourceModule?: string;
    stageId?: string;
    taskId?: string;
    addedBy?: string;
    status?: string;
    page?: number;
    size?: number;
  } = {}) {
    const response = await httpClient.get<ApiResponse<PageResponse<ProjectReference>>>(
      `/api/v1/projects/${projectId}/references`,
      { params },
    );
    return response.data.data;
  },
  async removeReference(referenceId: string) {
    await httpClient.delete(`/api/v1/project-references/${referenceId}`);
  },
  async restoreReference(referenceId: string) {
    const response = await httpClient.post<ApiResponse<ProjectReference>>(`/api/v1/project-references/${referenceId}/restore`);
    return response.data.data;
  },
};
