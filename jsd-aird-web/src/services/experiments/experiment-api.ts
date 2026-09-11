import { httpClient } from '@/services/http/client';
import type { ApiResponse } from '@/types/api';

export type ExperimentStatus =
  'DRAFT' | 'PENDING' | 'IN_PROGRESS' | 'PENDING_REVIEW' | 'RETURNED' | 'COMPLETED' | 'VOIDED';
export type ExperimentSourceType =
  'PROJECT' | 'TEMPLATE' | 'MANUAL' | 'EXCEL_IMPORT' | 'OCR_IMPORT';
export interface ExperimentSummary {
  id: string;
  experimentNo: string;
  title: string;
  categoryId?: string;
  categoryName?: string;
  sourceType: ExperimentSourceType;
  status: ExperimentStatus;
  projectId?: string;
  projectName?: string;
  stageId?: string;
  stageName?: string;
  taskId?: string;
  taskName?: string;
  ownerName: string | null;
  experimentDate: string | null;
  versionNo: number;
  revision: number;
  updatedAt: string;
  allowedActions?: string[];
}
export interface ExperimentSourceRef extends Record<string, unknown> {
  sourceType?: string;
  sourceFileId?: string;
  importJobId?: string;
  dataRecordId?: string;
  templateVersionId?: string;
  sheetId?: string;
  cellRange?: string;
  sourceHash?: string;
  recordKey?: string;
  sourceIdentity?: string;
  sourceIdentityType?: string;
  sourceGroupKey?: string;
  sampleKey?: string;
  logicalSampleKey?: string | null;
  sourceContextKey?: string;
}
export interface ExperimentItem extends Record<string, unknown> {
  itemId: string;
  sourceRefs: ExperimentSourceRef[];
  sourceGroupKey?: string;
  sampleKey?: string;
  logicalSampleKey?: string | null;
  sourceIdentity?: string;
  sourceIdentityType?: string;
  sourceRecordKey?: string;
  sourceContextKey?: string;
}
export interface ExperimentSampleGroup extends Record<string, unknown> {
  sourceGroupKey?: string;
  sampleKey: string;
  logicalSampleKey?: string | null;
  sourceIdentity: string;
  sourceIdentityType: string;
  sourceRecordKeys: string[];
  sourceSheets?: string[];
  sourceContextKey?: string;
}
export type ExperimentSourceGroup = ExperimentSampleGroup;
export interface ExperimentSourceContext extends Record<string, unknown> {
  sourceContextKey: string;
  sheetId?: string;
  sheetName?: string;
  sourceRecordKeys: string[];
  sharedContextRecordKeys: string[];
  facts?: Array<Record<string, unknown>>;
}
export interface FormulaItem extends ExperimentItem {
  materialId?: string | null;
  materialCode?: string;
  rawValue?: unknown;
  rawUnit?: string;
}
export type ProcessStep = ExperimentItem;
export interface TestResult extends ExperimentItem {
  testMethod?: string;
  testCondition?: string;
  substrate?: string;
}
export interface ExperimentModel extends Record<string, unknown> {
  schemaVersion?: number;
  title?: string;
  sourceFileId?: string;
  sourceFileName?: string;
  purpose?: string;
  plan?: string;
  documentFormat?: 'word' | 'excel';
  /** True only for an experiment created from the blank option. */
  blankDocument?: boolean;
  documentSnapshot?: Record<string, unknown>;
  dynamicValues?: Record<string, unknown>;
  sourceGroups?: ExperimentSourceGroup[];
  sourceContexts?: ExperimentSourceContext[];
  sampleGroups?: ExperimentSampleGroup[];
  formulaItems?: FormulaItem[];
  processSteps?: ProcessStep[];
  testResults?: TestResult[];
  events?: Array<Record<string, unknown>>;
  conclusion?: Record<string, unknown>;
}
export interface ExperimentDetail {
  summary: ExperimentSummary;
  currentVersionId: string;
  sourceFileId?: string;
  templateVersionId?: string;
  templateSnapshotHash?: string;
  templateSnapshot: Record<string, unknown>;
  editModel: ExperimentModel;
  reviews: Array<Record<string, unknown>>;
  attachments: Array<Record<string, unknown>>;
}
export interface ExperimentVersion {
  id: string;
  versionNo: number;
  status: string;
  templateVersionId?: string;
  snapshotHash?: string;
  templateSnapshot?: Record<string, unknown>;
  editModel: ExperimentModel;
  revisionReason?: string;
  submittedAt?: string;
  publishedAt?: string;
  createdAt: string;
  createdBy?: string;
}
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}
export interface Category {
  id: string;
  code: string;
  name: string;
  description: string;
  active: boolean;
  revision: number;
  allowedActions?: string[];
}
const data = <T>(r: { data: ApiResponse<T> }) => r.data.data;
export async function listExperiments(params: Record<string, unknown>) {
  return data(
    await httpClient.get<ApiResponse<Page<ExperimentSummary>>>('/api/v1/experiments', { params }),
  );
}
/** Project-detail experiment list. The project id is part of the resource path. */
export async function listProjectExperiments(projectId: string, params: Record<string, unknown> = {}) {
  return data(
    await httpClient.get<ApiResponse<Page<ExperimentSummary>>>(
      `/api/v1/projects/${projectId}/experiments`,
      { params },
    ),
  );
}
export async function getExperiment(id: string) {
  return data(
    await httpClient.get<ApiResponse<ExperimentDetail>>(`/api/v1/experiments/${id}/edit-model`),
  );
}
export async function exportExperiment(id: string) {
  const response = await httpClient.get<Blob>(`/api/v1/experiments/${id}/export`, {
    responseType: 'blob',
    headers: { Accept: '*/*' },
  });
  return response.data;
}
export async function createExperiment(input: Record<string, unknown>) {
  return data(await httpClient.post<ApiResponse<ExperimentSummary>>('/api/v1/experiments', input));
}
/** Create an experiment from a project detail page. */
export async function createProjectExperiment(projectId: string, input: Record<string, unknown>) {
  return data(
    await httpClient.post<ApiResponse<ExperimentSummary>>(
      `/api/v1/projects/${projectId}/experiments`,
      input,
    ),
  );
}
export async function copyExperiment(id: string) {
  return data(await httpClient.post<ApiResponse<ExperimentSummary>>(`/api/v1/experiments/${id}/copy`));
}
export async function saveExperiment(id: string, input: Record<string, unknown>) {
  return data(
    await httpClient.post<ApiResponse<ExperimentDetail>>(`/api/v1/experiments/${id}/draft`, input),
  );
}
export async function deleteExperiment(id: string, revision: number) {
  return data(await httpClient.delete<ApiResponse<null>>(`/api/v1/experiments/${id}/eln-delete`, { params: { revision } }));
}
/** Delete an experiment through its project-scoped endpoint. */
export async function deleteProjectExperiment(projectId: string, id: string, revision: number) {
  return data(
    await httpClient.delete<ApiResponse<null>>(
      `/api/v1/projects/${projectId}/experiments/${id}`,
      { params: { revision } },
    ),
  );
}
export async function publishExperiment(id: string, revision: number) {
  return data(await httpClient.post<ApiResponse<ExperimentDetail>>(`/api/v1/experiments/${id}/publish`, { revision }));
}
export async function actExperiment(
  id: string,
  action: 'start' | 'submit-review' | 'approve' | 'return' | 'void',
  revision: number,
  comment?: string,
) {
  return data(
    await httpClient.post<ApiResponse<ExperimentDetail>>(`/api/v1/experiments/${id}/${action}`, {
      revision,
      comment,
    }),
  );
}
export async function listVersions(id: string) {
  return data(
    await httpClient.get<ApiResponse<ExperimentVersion[]>>(`/api/v1/experiments/${id}/versions`),
  );
}
export async function compareVersions(id: string, from: number, to: number) {
  return data(
    await httpClient.get<ApiResponse<Record<string, unknown>>>(
      `/api/v1/experiments/${id}/versions/compare`,
      { params: { from, to } },
    ),
  );
}
export async function createRevision(id: string, revision: number, reason: string) {
  return data(
    await httpClient.post<ApiResponse<ExperimentDetail>>(`/api/v1/experiments/${id}/versions`, {
      revision,
      reason,
    }),
  );
}
export async function rollbackVersion(id: string, revision: number, versionNo: number, reason: string) {
  return data(
    await httpClient.post<ApiResponse<ExperimentDetail>>(
      `/api/v1/experiments/${id}/versions/${versionNo}/rollback`,
      { revision, reason },
    ),
  );
}
export async function listAudits(id: string) {
  return data(
    await httpClient.get<ApiResponse<Array<Record<string, unknown>>>>(
      `/api/v1/experiments/${id}/audits`,
    ),
  );
}
export async function listCategories(includeInactive = false) {
  return data(
    await httpClient.get<ApiResponse<Category[]>>('/api/v1/experiment-categories', {
      params: { includeInactive },
    }),
  );
}
export async function createCategory(input: { code: string; name: string; description: string }) {
  return data(await httpClient.post<ApiResponse<Category>>('/api/v1/experiment-categories', input));
}
export async function updateCategory(id: string, input: { revision: number; name: string; description: string }) {
  return data(await httpClient.put<ApiResponse<Category>>(`/api/v1/experiment-categories/${id}`, input));
}
export async function setCategoryActive(id: string, revision: number, active: boolean) {
  return data(await httpClient.put<ApiResponse<Category>>(`/api/v1/experiment-categories/${id}/active`, { revision, active }));
}
export interface StagedFile {
  fileId: string;
  originalName: string;
  contentType: string;
  size: number;
  sha256: string;
  status: 'STAGED';
}
export interface ExperimentImportAccepted {
  jobId: string;
  status: 'PARSING';
}
export async function stageExperimentFile(file: File) {
  const body = new FormData();
  body.append('file', file);
  return data(
    await httpClient.post<ApiResponse<StagedFile>>(
      '/api/v1/files/staged?kind=EXPERIMENT_SOURCE',
      body,
    ),
  );
}
export async function importExperimentFile(input: {
  fileId: string;
  fileName: string;
  sha256: string;
  format: string;
  categoryId?: string;
  categoryName?: string;
  projectId?: string;
  stageId?: string;
  taskId?: string;
  experimentDate: string;
  visibility?: 'ALL' | 'QUALITY' | 'PROJECT';
}) {
  return data(
    await httpClient.post<ApiResponse<ExperimentImportAccepted>>('/api/v1/experiment-imports', input),
  );
}
export interface ExperimentImportJob {
  id: string;
  sourceFileId: string;
  sourceFileName: string;
  sourceFormat: string;
  status: 'PARSING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  progress?: number;
  currentStage?: string;
  experimentId?: string;
  errorMessage?: string;
  categoryName?: string;
  projectId?: string;
  projectName?: string;
  stageId?: string;
  stageName?: string;
  taskId?: string;
  taskName?: string;
  visibility?: 'ALL' | 'QUALITY' | 'PROJECT';
  createdAt: string;
  allowedActions?: string[];
}
export async function listExperimentImports() {
  return data(
    await httpClient.get<ApiResponse<ExperimentImportJob[]>>('/api/v1/experiment-imports'),
  );
}
export async function retryExperimentImport(id: string) {
  return data(
    await httpClient.post<ApiResponse<ExperimentImportAccepted>>(`/api/v1/experiment-imports/${id}/retry`),
  );
}
export async function deleteExperimentImport(id: string) {
  await httpClient.delete(`/api/v1/experiment-imports/${id}`);
}
