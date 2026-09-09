import { httpClient } from '@/services/http/client';
import { downloadBlob } from '@/services/files/file-api';
import type { ApiResponse, PageResponse } from '@/types/api';
import type { ExperimentModel } from '@/services/experiments/experiment-api';

export type ResearchTestType = 'REPORT' | 'STANDARD';
export type ResearchTestStatus = 'DRAFT' | 'PENDING_REVIEW' | 'RETURNED' | 'PUBLISHED' | 'ARCHIVED';
export interface ResearchTestSummary {
  id: string;
  recordType: ResearchTestType;
  businessNo: string;
  name: string;
  category?: string;
  applicableScope?: string;
  ownerName?: string;
  businessDate?: string;
  documentFormat: 'WORD' | 'EXCEL';
  sourceType: string;
  status: ResearchTestStatus;
  visibility: string;
  projectId?: string;
  projectName?: string;
  stageId?: string;
  stageName?: string;
  taskId?: string;
  taskName?: string;
  sourceFileId?: string;
  versionNo: number;
  lockVersion: number;
  createdAt: string;
  updatedAt: string;
}
export interface ResearchTestDetail {
  summary: ResearchTestSummary;
  versionId: string;
  templateVersionId?: string;
  templateSnapshotHash?: string;
  templateSnapshot?: Record<string, unknown>;
  editModel: ExperimentModel;
  memberSnapshot?: unknown[];
  effectiveFrom?: string;
  effectiveTo?: string;
  reviews: Array<Record<string, unknown>>;
}
export interface ResearchTestVersion {
  id: string;
  versionNo: number;
  status: string;
  templateVersionId?: string;
  templateSnapshotHash?: string;
  templateSnapshot?: Record<string, unknown>;
  editModel: ExperimentModel;
  memberSnapshot?: unknown[];
  effectiveFrom?: string;
  effectiveTo?: string;
  changeSummary?: string;
  submittedAt?: string;
  publishedAt?: string;
  createdBy?: string;
  createdAt: string;
}
export interface ResearchTestUpload {
  id: string;
  recordId: string;
  fileId: string;
  originalName: string;
  contentType: string;
  fileSize: number;
  status: string;
  category?: string;
  visibility?: string;
  projectName?: string;
  stageName?: string;
  taskName?: string;
  createdAt: string;
}
export interface StagedResearchTestFile {
  fileId: string;
  originalName: string;
  contentType: string;
  size: number;
  sha256: string;
}
const root = (type: ResearchTestType) =>
  type === 'REPORT' ? '/api/v1/comprehensive-reports' : '/api/v1/test-standards';
const data = <T>(r: { data: ApiResponse<T> }) => r.data.data;
export async function listResearchTests(type: ResearchTestType, params: Record<string, unknown>) {
  return data(
    await httpClient.get<ApiResponse<PageResponse<ResearchTestSummary>>>(root(type), { params }),
  );
}
export async function getResearchTest(type: ResearchTestType, id: string) {
  return data(await httpClient.get<ApiResponse<ResearchTestDetail>>(`${root(type)}/${id}`));
}
export async function createResearchTest(type: ResearchTestType, input: Record<string, unknown>) {
  return data(await httpClient.post<ApiResponse<ResearchTestDetail>>(root(type), input));
}
export async function saveResearchTest(
  type: ResearchTestType,
  id: string,
  input: Record<string, unknown>,
) {
  return data(
    await httpClient.post<ApiResponse<ResearchTestDetail>>(`${root(type)}/${id}/draft`, input),
  );
}
export async function renameResearchTest(
  type: ResearchTestType,
  id: string,
  input: {
    revision: number;
    name: string;
    businessNo?: string;
    ownerName?: string;
    date?: string;
    category?: string;
    scope?: string;
    effectiveFrom?: string;
    effectiveTo?: string;
    projectId?: string;
    stageId?: string;
    taskId?: string;
  },
) {
  return data(
    await httpClient.put<ApiResponse<ResearchTestDetail>>(`${root(type)}/${id}/rename`, input),
  );
}
export async function actResearchTest(
  type: ResearchTestType,
  id: string,
  action: 'submit-review' | 'approve' | 'return' | 'archive',
  revision: number,
  comment?: string,
) {
  return data(
    await httpClient.post<ApiResponse<ResearchTestDetail>>(`${root(type)}/${id}/${action}`, {
      revision,
      comment,
    }),
  );
}
export async function copyResearchTest(type: ResearchTestType, id: string) {
  return data(await httpClient.post<ApiResponse<ResearchTestDetail>>(`${root(type)}/${id}/copy`));
}
export async function deleteResearchTest(type: ResearchTestType, id: string, revision: number) {
  await httpClient.delete(`${root(type)}/${id}`, { params: { revision } });
}
export async function listResearchTestVersions(type: ResearchTestType, id: string) {
  return data(
    await httpClient.get<ApiResponse<ResearchTestVersion[]>>(`${root(type)}/${id}/versions`),
  );
}
export async function createResearchTestRevision(
  type: ResearchTestType,
  id: string,
  revision: number,
  reason: string,
) {
  return data(
    await httpClient.post<ApiResponse<ResearchTestDetail>>(`${root(type)}/${id}/versions`, {
      revision,
      reason,
    }),
  );
}
export async function stageResearchTestFile(
  file: File,
  type: ResearchTestType = 'REPORT',
  signal?: AbortSignal,
): Promise<StagedResearchTestFile> {
  const body = new FormData();
  body.append('file', file);
  return data(
    await httpClient.post<
      ApiResponse<{
        fileId: string;
        originalName: string;
        contentType: string;
        size: number;
        sha256: string;
      }>
    >(`/api/v1/files/staged?kind=${type === 'STANDARD' ? 'RESEARCH_TEST_STANDARD_SOURCE' : 'RESEARCH_TEST_SOURCE'}`, body, { signal }),
  );
}
export async function registerResearchTestUpload(type: ResearchTestType, input: Record<string, unknown>, signal?: AbortSignal) {
  return data(
    await httpClient.post<ApiResponse<ResearchTestUpload>>(
      `${root(type)}/uploads`,
      input,
      { signal },
    ),
  );
}
export async function listResearchTestUploads(type: ResearchTestType, params: Record<string, unknown>) {
  return data(
    await httpClient.get<ApiResponse<PageResponse<ResearchTestUpload>>>(
      `${root(type)}/uploads`,
      { params },
    ),
  );
}
export async function retryResearchTestUpload(type: ResearchTestType, id: string) {
  return data(
    await httpClient.post<ApiResponse<ResearchTestUpload>>(
      `${root(type)}/uploads/${id}/retry`,
    ),
  );
}
export async function deleteResearchTestUpload(type: ResearchTestType, id: string) {
  await httpClient.delete(`${root(type)}/uploads/${id}`);
}

export async function downloadResearchTest(
  type: ResearchTestType,
  id: string,
  fileName: string,
  format: 'WORD' | 'EXCEL',
) {
  const response = await httpClient.get<Blob>(`${root(type)}/${id}/export`, {
    responseType: 'blob',
  });
  downloadBlob(response.data, `${fileName}.${format === 'WORD' ? 'docx' : 'xlsx'}`);
}
