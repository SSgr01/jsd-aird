import { projectResourceApi, type ProjectReference } from './project-resource-api';

export type ReferenceMaterial = ProjectReference;

export interface ReferenceMaterialQuery {
  keyword?: string;
  source?: string;
  stageId?: string;
  taskId?: string;
  addedBy?: string;
  status?: string;
  page?: number;
  size?: number;
}

export const REFERENCE_SOURCE_OPTIONS = [
  { value: 'KNOWLEDGE', label: '研发知识库' },
  { value: 'DATA_CENTER', label: '数据中心' },
];

export const REFERENCE_STATUS_OPTIONS = [
  { value: 'ACTIVE', label: '有效' },
  { value: 'REMOVED', label: '已移除' },
];

export function listReferenceMaterials(projectId: string, query: ReferenceMaterialQuery) {
  return projectResourceApi.references(projectId, {
    keyword: query.keyword,
    sourceModule: query.source,
    stageId: query.stageId,
    taskId: query.taskId,
    addedBy: query.addedBy,
    status: query.status,
    page: query.page,
    size: query.size,
  });
}

export function removeReferenceMaterial(_projectId: string, id: string) {
  return projectResourceApi.removeReference(id);
}

export function restoreReferenceMaterial(id: string) {
  return projectResourceApi.restoreReference(id);
}
