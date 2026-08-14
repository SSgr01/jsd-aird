import { httpClient } from '@/services/http/client';

interface ApiResponse<T> {
  data: T;
}

interface PageData<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

export interface Material {
  id: string;
  code: string;
  name: string;
  category: string;
  sourceCategory: string;
  sourceModule: string;
  stage?: string;
  contactPerson?: string;
  status: string;
  description?: string;
  version: number;
  linked?: boolean;
}

export interface MaterialInput {
  code: string;
  name: string;
  category: string;
  sourceCategory: string;
  sourceModule: string;
  stage?: string;
  contactPerson?: string;
  status?: string;
  description?: string;
  version?: number;
}

export interface ProjectMaterial {
  id: string;
  projectId: string;
  materialId: string;
  materialCode: string;
  materialName: string;
  materialCategory: string;
  sourceCategory: string;
  sourceModule: string;
  stage?: string;
  contactPerson?: string;
  status: string;
  linkedAt?: string;
  linkedBy?: string;
  remark?: string;
}

export interface ProjectMaterialQuery {
  category?: string;
  stage?: string;
  status?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

export const MATERIAL_CATEGORY_OPTIONS = [
  { value: '标准', label: '标准' },
  { value: '规范', label: '规范' },
  { value: '图纸', label: '图纸' },
  { value: '文档', label: '文档' },
  { value: '其他', label: '其他' },
];

export const STAGE_OPTIONS = [
  { value: '调研', label: '调研' },
  { value: '方案', label: '方案' },
  { value: '实施', label: '实施' },
  { value: '验收', label: '验收' },
  { value: '交付', label: '交付' },
];

export const SOURCE_CATEGORY_OPTIONS = [
  { value: '内部', label: '内部' },
  { value: '外部', label: '外部' },
];

export const SOURCE_MODULE_OPTIONS = [
  { value: '项目库', label: '项目库' },
  { value: '工艺库', label: '工艺库' },
  { value: '法规库', label: '法规库' },
  { value: '客户库', label: '客户库' },
];

export const STATUS_OPTIONS = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'PUBLISHED', label: '已发布' },
  { value: 'ARCHIVED', label: '已归档' },
];

export async function listProjectMaterials(projectId: string, query: ProjectMaterialQuery): Promise<PageData<ProjectMaterial>> {
  const { data } = await httpClient.get<ApiResponse<PageData<ProjectMaterial>>>(`/api/v1/projects/${projectId}/materials`, {
    params: {
      category: query.category,
      stage: query.stage,
      status: query.status,
      keyword: query.keyword,
      page: query.page ?? 1,
      size: query.size ?? 50,
    },
  });
  return data.data;
}

export async function listMaterials(query: { page?: number; size?: number }): Promise<PageData<Material>> {
  const { data } = await httpClient.get<ApiResponse<PageData<Material>>>('/api/v1/materials', {
    params: { page: query.page ?? 1, size: query.size ?? 100 },
  });
  return data.data;
}

export async function createMaterial(input: MaterialInput): Promise<Material> {
  const { data } = await httpClient.post<ApiResponse<Material>>('/api/v1/materials', input);
  return data.data;
}

export async function linkProjectMaterial(projectId: string, materialId: string, remark?: string): Promise<void> {
  await httpClient.post(`/api/v1/projects/${projectId}/materials/link`, { materialId, remark });
}

export async function unlinkProjectMaterial(projectId: string, materialId: string): Promise<void> {
  await httpClient.post(`/api/v1/projects/${projectId}/materials/unlink`, null, { params: { materialId } });
}

export async function listMaterialsByProject(query: {
  projectId: string;
  keyword?: string;
  category?: string;
  owner?: string;
  page?: number;
  size?: number;
}): Promise<PageData<Material>> {
  const { projectId, ...rest } = query;
  const { data } = await httpClient.get<ApiResponse<PageData<Material>>>(`/api/v1/materials/project/${projectId}`, {
    params: rest,
  });
  return data.data;
}

export async function saveAssociations(projectId: string, materialIds: string[]): Promise<void> {
  await httpClient.post(`/api/v1/projects/${projectId}/materials/associations`, materialIds);
}