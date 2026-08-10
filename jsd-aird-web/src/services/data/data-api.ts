import type { ApiResponse } from '@/types/api';
import { httpClient } from '@/services/http/client';

export type DataType = 'MATERIAL' | 'FORMULA' | 'PROCESS' | 'EQUIPMENT' | 'TEST_STANDARD';

export interface DataTemplateOption { templateId: string; versionId: string; templateCode: string; name: string; category?: string; targetDataType: DataType; versionNo: number; format: 'XLSX' | 'DOCX' }
export interface DataJob { id: string; sourceFileId: string; sourceSha256: string; sourceFileName: string; sourceFormat: string; templateVersionId: string; targetDataType: DataType; status: string; progress: number; currentStage?: string; parserVersion?: string; errorMessage?: string; createdAt: string; updatedAt: string }
export interface DataSheet { id: string; sheetId: string; sheetName: string; sheetOrder: number; selected: boolean; headerRows: number[]; dataStartRow?: number; dataEndRow?: number; structure: Record<string, unknown>; confirmationStatus: string }
export interface DataMapping { id?: string; sheetId: string; sourceColumn: string; sourceHeader?: string; fieldCode?: string; fieldName?: string; action: string; valueType?: string; sourceUnit?: string; standardUnit?: string; detail?: Record<string, unknown>; status?: string }
export interface DataRow { id: string; sheetId: string; rowNumber: number; rawValues: Record<string, unknown>; normalizedValues: Record<string, unknown>; correctedValues: Record<string, unknown>; status: string }
export interface DataIssue { id: string; sheetId?: string; fieldCode?: string; severity: 'INFO' | 'WARNING' | 'BLOCKER'; issueType: string; rowNumber?: number; column?: string; address?: string; message: string; detail: Record<string, unknown>; status: string }
export interface DataPreview { job: DataJob; sheets: DataSheet[]; mappings: DataMapping[]; rows: DataRow[]; issues: DataIssue[] }
export interface DataAsset { id: string; targetDataType: DataType; assetKey: string; displayName?: string; currentRevisionId?: string; status: string; updatedAt: string }
export interface DataAssetDetail extends DataAsset { rawData?: Record<string, unknown>; normalizedData?: Record<string, unknown>; correctedData?: Record<string, unknown>; importJobId?: string; templateVersionId?: string }
export interface DataRevision { id: string; revisionNo: number; importJobId: string; templateVersionId: string; dataHash: string; createdAt: string }
export interface DataSourceAnchor { id: string; revisionId: string; fieldCode?: string; fileId: string; sheetId?: string; sheetName?: string; rowNumber?: number; columnNumber?: number; columnName?: string; address?: string; rawValue?: unknown }

const dataTypeLabels: Record<DataType, string> = { MATERIAL: '物料/原料', FORMULA: '配方', PROCESS: '工艺', EQUIPMENT: '设备/仪器', TEST_STANDARD: '检测标准' };
export const dataTypeOptions = Object.entries(dataTypeLabels).map(([value, label]) => ({ value: value as DataType, label }));

export const dataApi = {
  async listTemplates(targetDataType?: DataType) {
    const response = await httpClient.get<ApiResponse<DataTemplateOption[]>>('/api/v2/data/templates', { params: targetDataType ? { targetDataType } : undefined });
    return response.data.data;
  },
  async stageSource(file: File) {
    const body = new FormData(); body.append('file', file);
    const response = await httpClient.post<ApiResponse<{ fileId: string; sha256: string; status: string }>>('/api/v2/files/staged?kind=DATA_SOURCE', body);
    return response.data.data;
  },
  async createJob(input: { sourceFileId: string; templateVersionId: string; targetDataType: DataType; duplicateOverride?: boolean }) {
    const response = await httpClient.post<ApiResponse<DataJob>>('/api/v2/data/import-jobs', input); return response.data.data;
  },
  async getJob(id: string) { const response = await httpClient.get<ApiResponse<DataJob>>(`/api/v2/data/import-jobs/${id}`); return response.data.data; },
  async parse(id: string) { const response = await httpClient.post<ApiResponse<DataJob>>(`/api/v2/data/import-jobs/${id}/parse`); return response.data.data; },
  async preview(id: string) { const response = await httpClient.get<ApiResponse<DataPreview>>(`/api/v2/data/import-jobs/${id}/preview`); return response.data.data; },
  async saveSheets(id: string, items: Array<Pick<DataSheet, 'sheetId' | 'selected' | 'headerRows' | 'dataStartRow' | 'dataEndRow' | 'confirmationStatus'>>) { await httpClient.put(`/api/v2/data/import-jobs/${id}/sheets`, { items }); },
  async saveMappings(id: string, items: DataMapping[]) { await httpClient.put(`/api/v2/data/import-jobs/${id}/mappings`, { items }); },
  async resolveIssue(id: string, issueId: string, status: string) { await httpClient.put(`/api/v2/data/import-jobs/${id}/issues/${issueId}`, { status }); },
  async requestField(id: string, input: { fieldId?: string; displayName: string; valueType?: string; uiType?: string; groupCode?: string; description?: string }) { const response = await httpClient.post<ApiResponse<{ id: string; status: string }>>(`/api/v2/data/import-jobs/${id}/field-requests`, input); return response.data.data; },
  async commit(id: string) { const response = await httpClient.post<ApiResponse<DataJob>>(`/api/v2/data/import-jobs/${id}/commit`); return response.data.data; },
  async listAssets(params: { targetDataType?: DataType; keyword?: string } = {}) { const response = await httpClient.get<ApiResponse<DataAsset[]>>('/api/v2/data/assets', { params }); return response.data.data; },
  async exportAssets(input: { targetDataType: DataType; templateVersionId: string; assetIds: string[] }) {
    const response = await httpClient.post<Blob>('/api/v2/data/assets/export', input, { responseType: 'blob' });
    return response.data;
  },
  async getAsset(id: string) { const response = await httpClient.get<ApiResponse<DataAssetDetail>>(`/api/v2/data/assets/${id}`); return response.data.data; },
  async listRevisions(id: string) { const response = await httpClient.get<ApiResponse<DataRevision[]>>(`/api/v2/data/assets/${id}/revisions`); return response.data.data; },
  async listSources(id: string) { const response = await httpClient.get<ApiResponse<DataSourceAnchor[]>>(`/api/v2/data/assets/${id}/source`); return response.data.data; },
};
