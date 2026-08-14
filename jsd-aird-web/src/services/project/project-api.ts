import { httpClient } from '@/services/http/client';

interface ApiResponse<T> {
  data: T;
}

export interface PageData<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

export type StageStatus = 'NOT_STARTED' | 'PENDING' | 'IN_PROGRESS' | 'COMPLETED';

export interface ProjectStage {
  id: string;
  projectId: string;
  projectCode: string;
  projectName: string;
  stageCode: string;
  name: string;
  orderNo: number;
  status: StageStatus;
  owner?: string;
  description?: string;
  plannedStart?: string;
  plannedEnd?: string;
  actualStart?: string;
  actualEnd?: string;
  taskCount: number;
  openTaskCount: number;
  experimentCount?: number;
  materialCount?: number;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface StageInput {
  name: string;
  stageCode?: string;
  status?: StageStatus;
  owner?: string;
  description?: string;
  plannedStart?: string;
  plannedEnd?: string;
  transitionReason?: string;
  version?: number;
}

export interface StageQuery {
  keyword?: string;
  projectId?: string;
  status?: StageStatus;
  owner?: string;
  plannedFrom?: string;
  plannedTo?: string;
  page?: number;
  size?: number;
}

export type ProjectLogObjectType = 'PROJECT_STAGE' | 'PROJECT_TASK' | 'PROJECT_EXPERIMENT' | 'PROJECT';
export type ProjectLogAction = 'CREATE' | 'UPDATE' | 'REOPEN' | 'DELETE' | 'REORDER';

export interface ProjectAuditLog {
  id: string;
  projectId: string;
  objectType: ProjectLogObjectType;
  objectId: string;
  objectName?: string;
  action: ProjectLogAction;
  operator: string;
  detail?: string;
  traceId?: string;
  createdAt: string;
}

export interface ProjectLogQuery {
  keyword?: string;
  objectType?: ProjectLogObjectType;
  action?: ProjectLogAction;
  operator?: string;
  createdFrom?: string;
  createdTo?: string;
  page?: number;
  size?: number;
}

export type TaskStatus = 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';

export interface ProjectTask {
  id: string;
  taskCode: string;
  projectId: string;
  projectName?: string;
  stageId: string;
  stageName?: string;
  name: string;
  owner?: string;
  priority?: ProjectPriority;
  plannedDate?: string;
  status: TaskStatus | string;
  experimentCount: number;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface TaskQuery {
  keyword?: string;
  projectId?: string;
  stageId?: string;
  status?: TaskStatus | string;
  owner?: string;
  priority?: ProjectPriority;
  page?: number;
  size?: number;
}

export interface ProjectExperiment { id:string; experimentCode:string; projectId:string; stageId:string; taskId:string; title:string; category?:string; owner:string; experimentDate:string; status:string; templateName?:string; templateVersion?:string; workbookContent?:string; version:number }

export type ProjectPriority = 'HIGH' | 'MEDIUM' | 'LOW';
export type ProjectStatus =
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'AUDITING'
  | 'MASS_PRODUCTION'
  | 'PENDING';

export interface Project {
  id: string;
  projectCode: string;
  name: string;
  partnerId?: string;
  partnerName?: string;
  owner?: string;
  startDate?: string;
  endDate?: string;
  priority: ProjectPriority;
  status: ProjectStatus;
  teamSize: number;
  background?: string;
  customFields?: Record<string, unknown>;
  teamMembers: string[];
  version: number;
}

export interface ProjectInput {
  projectCode?: string;
  name: string;
  owner?: string;
  startDate?: string;
  endDate?: string;
  priority?: ProjectPriority;
  status?: ProjectStatus;
  teamSize?: number;
  background?: string;
  customFields?: Record<string, unknown>;
  teamMembers?: string[];
  version?: number;
}

export interface ProjectQuery {
  keyword?: string;
  owner?: string;
  priority?: ProjectPriority;
  status?: ProjectStatus;
  partnerId?: string;
  startDateFrom?: string;
  startDateTo?: string;
  page?: number;
  size?: number;
}

const priorityLabels: Record<ProjectPriority, string> = {
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低',
};

const statusLabels: Record<ProjectStatus, string> = {
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成',
  AUDITING: '审核中',
  MASS_PRODUCTION: '已量产',
  PENDING: '待启动',
};

export const projectPriorities: { value: ProjectPriority; label: string }[] = [
  { value: 'HIGH', label: priorityLabels.HIGH },
  { value: 'MEDIUM', label: priorityLabels.MEDIUM },
  { value: 'LOW', label: priorityLabels.LOW },
];

export const projectStatuses: { value: ProjectStatus; label: string }[] = [
  { value: 'IN_PROGRESS', label: statusLabels.IN_PROGRESS },
  { value: 'COMPLETED', label: statusLabels.COMPLETED },
  { value: 'AUDITING', label: statusLabels.AUDITING },
  { value: 'MASS_PRODUCTION', label: statusLabels.MASS_PRODUCTION },
  { value: 'PENDING', label: statusLabels.PENDING },
];

const stageStatusLabels: Record<StageStatus, string> = {
  NOT_STARTED: '未开始',
  PENDING: '待开始',
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成',
};

export const stageStatuses = (Object.entries(stageStatusLabels) as [StageStatus, string][]).map(
  ([value, label]) => ({ value, label }),
);

export function formatStageStatus(value?: StageStatus) {
  return value ? stageStatusLabels[value] : '—';
}

const taskStatusLabels: Record<TaskStatus, string> = {
  PENDING: '待开始',
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
};

export const taskStatuses = (Object.entries(taskStatusLabels) as [TaskStatus, string][]).map(
  ([value, label]) => ({ value, label }),
);

export function formatTaskStatus(value?: TaskStatus | string | null) {
  if (!value) return '—';
  return (taskStatusLabels as Record<string, string>)[value] ?? value;
}

export function formatProjectPriority(value?: ProjectPriority) {
  return value ? (priorityLabels[value] ?? value) : '—';
}

export function formatProjectStatus(value?: ProjectStatus | string) {
  return value ? (statusLabels[value as ProjectStatus] ?? value) : '—';
}

function cleanParams<T extends object>(query: T) {
  return Object.fromEntries(
    Object.entries(query).filter(([, value]) => value !== undefined && value !== ''),
  );
}

export async function getProjects(query: ProjectQuery): Promise<PageData<Project>> {
  const { data } = await httpClient.get<ApiResponse<PageData<Project>>>('/api/v1/projects', {
    params: cleanParams(query),
  });
  return data.data;
}

export async function getProject(id: string): Promise<Project> {
  const { data } = await httpClient.get<ApiResponse<Project>>(`/api/v1/projects/${id}`);
  return data.data;
}

export async function createProject(input: ProjectInput) {
  const { data } = await httpClient.post<ApiResponse<{ id: string; version: number }>>(
    '/api/v1/projects',
    input,
  );
  return data.data;
}

export async function updateProject(id: string, input: ProjectInput) {
  await httpClient.put(`/api/v1/projects/${id}`, input);
}

export async function deleteProjects(ids: string[]) {
  await Promise.all(ids.map((id) => httpClient.delete(`/api/v1/projects/${id}`)));
}

export async function copyProjects(ids: string[]) {
  await httpClient.post('/api/v1/projects/copy', { ids });
}

export async function getProjectStages(projectId: string): Promise<ProjectStage[]> {
  const { data } = await httpClient.get<ApiResponse<ProjectStage[]>>(`/api/v1/projects/${projectId}/stages`);
  return data.data;
}

export async function getStages(query: StageQuery): Promise<PageData<ProjectStage>> {
  const { data } = await httpClient.get<ApiResponse<PageData<ProjectStage>>>('/api/v1/project-stages', {
    params: cleanParams(query),
  });
  return data.data;
}

export async function createStage(projectId: string, input: StageInput): Promise<ProjectStage> {
  const { data } = await httpClient.post<ApiResponse<ProjectStage>>(`/api/v1/projects/${projectId}/stages`, input);
  return data.data;
}

export async function updateStage(id: string, input: StageInput): Promise<ProjectStage> {
  const { data } = await httpClient.put<ApiResponse<ProjectStage>>(`/api/v1/stages/${id}`, input);
  return data.data;
}

export async function deleteStage(id: string, version: number): Promise<void> {
  await httpClient.delete(`/api/v1/stages/${id}`, { params: { version } });
}

export async function reorderStages(projectId: string, stages: ProjectStage[]): Promise<ProjectStage[]> {
  const { data } = await httpClient.put<ApiResponse<ProjectStage[]>>(`/api/v1/projects/${projectId}/stages/reorder`, {
    items: stages.map(({ id, version }) => ({ id, version })),
  });
  return data.data;
}

export async function getProjectLogs(projectId: string, query: ProjectLogQuery = {}): Promise<PageData<ProjectAuditLog>> {
  const { data } = await httpClient.get<ApiResponse<PageData<ProjectAuditLog>>>(`/api/v1/projects/${projectId}/logs`, {
    params: cleanParams(query),
  });
  return data.data;
}

export async function getStageTasks(stageId:string):Promise<ProjectTask[]>{const {data}=await httpClient.get<ApiResponse<ProjectTask[]>>(`/api/v1/stages/${stageId}/tasks`);return data.data;}
export async function createProjectTask(projectId:string,input:{stageId:string;name:string;owner?:string;plannedDate?:string;status?:string}):Promise<ProjectTask>{const {data}=await httpClient.post<ApiResponse<ProjectTask>>(`/api/v1/projects/${projectId}/tasks`,input);return data.data;}
export async function getProjectTask(taskId:string):Promise<ProjectTask>{const {data}=await httpClient.get<ApiResponse<ProjectTask>>(`/api/v1/tasks/${taskId}`);return data.data;}
export async function updateProjectTask(taskId:string,input:{stageId:string;name:string;owner?:string;plannedDate?:string;status?:string;version:number}):Promise<ProjectTask>{const {data}=await httpClient.put<ApiResponse<ProjectTask>>(`/api/v1/tasks/${taskId}`,input);return data.data;}
export async function getTaskExperiments(taskId:string):Promise<ProjectExperiment[]>{const {data}=await httpClient.get<ApiResponse<ProjectExperiment[]>>(`/api/v1/tasks/${taskId}/experiments`);return data.data;}
export async function createTaskExperiment(taskId:string,input:{experimentCode?:string;title:string;category?:string;owner:string;experimentDate:string;templateName?:string;templateVersion?:string;workbookContent?:string}):Promise<ProjectExperiment>{const {data}=await httpClient.post<ApiResponse<ProjectExperiment>>(`/api/v1/tasks/${taskId}/experiments`,input);return data.data;}
export async function updateTaskExperiment(id:string,input:{experimentCode?:string;title:string;category?:string;owner:string;experimentDate:string;version:number}):Promise<ProjectExperiment>{const {data}=await httpClient.put<ApiResponse<ProjectExperiment>>(`/api/v1/experiments/${id}`,input);return data.data;}
export async function deleteTaskExperiment(id:string,version:number):Promise<void>{const {data}=await httpClient.delete<ApiResponse<void>>(`/api/v1/experiments/${id}`,{params:{version}});return data.data;}
export async function getTasks(query: TaskQuery = {}): Promise<PageData<ProjectTask>> {
  const { data } = await httpClient.get<ApiResponse<PageData<ProjectTask>>>('/api/v1/tasks', {
    params: cleanParams(query),
  });
  return data.data;
}

export async function getTaskOwners(): Promise<string[]> {
  const { data } = await httpClient.get<ApiResponse<string[]>>('/api/v1/tasks/owners');
  return data.data;
}
