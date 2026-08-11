import type { ApiResponse, PageResponse } from '@/types/api';
import { httpClient } from '@/services/http/client';
import { fetchFileBlob } from '@/services/files';

export type DataType = 'MATERIAL' | 'FORMULA' | 'PROCESS' | 'EQUIPMENT' | 'TEST_STANDARD';

export interface DataTemplateOption { templateId: string; versionId: string; templateCode: string; name: string; category?: string; targetDataType: DataType; versionNo: number; format: 'XLSX' | 'DOCX' }
export interface DataJob { id: string; sourceFileId: string; sourceSha256: string; sourceFileName: string; sourceFormat: string; templateVersionId: string; targetDataType: DataType; categoryId?: string; status: string; progress: number; currentStage?: string; parserVersion?: string; errorMessage?: string; createdAt: string; updatedAt: string }
export interface DataSheet { id: string; sheetId: string; sheetName: string; sheetOrder: number; selected: boolean; headerRows: number[]; dataStartRow?: number; dataEndRow?: number; structure: Record<string, unknown>; confirmationStatus: string }
export interface DataMapping { id?: string; sheetId: string; sourceColumn: string; sourceHeader?: string; fieldCode?: string; fieldName?: string; action: string; valueType?: string; sourceUnit?: string; standardUnit?: string; detail?: Record<string, unknown>; status?: string }
export interface DataRow { id: string; sheetId: string; rowNumber: number; rawValues: Record<string, unknown>; normalizedValues: Record<string, unknown>; correctedValues: Record<string, unknown>; status: string }
export interface DataIssue { id: string; sheetId?: string; fieldCode?: string; severity: 'INFO' | 'WARNING' | 'BLOCKER'; issueType: string; rowNumber?: number; column?: string; address?: string; message: string; detail: Record<string, unknown>; status: string }
export interface TemplateFieldDefinition { fieldCode: string; displayName: string; dataType: string; defaultUnit?: string; required: boolean; identity: boolean; aliases: string[]; dataPath: string }
export interface ImportBinding { bindingId: string; fieldCode: string; dataPath: string; mappingKind: string; parentBindingId?: string; repeatAxis?: string; recordHeight: number; recordWidth: number; recordStride: number; terminationRule?: Record<string, unknown>; locator?: Record<string, unknown>; required: boolean; identity: boolean; trainingEligible: boolean; valueSource?: string; valueType?: string; unit?: string }
export interface TemplateContract { fields: TemplateFieldDefinition[]; bindings: ImportBinding[] }
export interface ProjectionSummary { datasetId?: string; status: string; recordCount: number; longValueCount: number; eligibleRecordCount: number }
export interface LongTableRow { recordKey: string; dimensions: Record<string, unknown>; measures: Record<string, unknown>; source: Record<string, unknown>; trainingEligible: boolean; exclusionReason?: string }
export interface LongTablePreview { dataset: TrainingDataset; rows: LongTableRow[] }
export interface TrainingDataset { id: string; importJobId?: string; templateVersionId: string; projectionVersion: string; name: string; status: string; schema: Record<string, unknown>; qualitySummary: Record<string, unknown>; sourceRevisionIds: string[]; recordCount: number; eligibleRecordCount: number }
export interface DataPreview { job: DataJob; sheets: DataSheet[]; mappings: DataMapping[]; rows: DataRow[]; issues: DataIssue[]; templateContract?: TemplateContract; projectionSummary?: ProjectionSummary }
export interface DataAsset { id: string; targetDataType: DataType; assetKey: string; displayName?: string; currentRevisionId?: string; status: string; updatedAt: string; categoryId?: string; categoryName?: string }
export interface DataAssetDetail extends DataAsset { rawData?: Record<string, unknown>; normalizedData?: Record<string, unknown>; correctedData?: Record<string, unknown>; importJobId?: string; templateVersionId?: string }
export interface DataRevision { id: string; revisionNo: number; importJobId: string; templateVersionId: string; dataHash: string; createdAt: string }
export interface DataSourceAnchor { id: string; revisionId: string; fieldCode?: string; fileId: string; sheetId?: string; sheetName?: string; rowNumber?: number; columnNumber?: number; columnName?: string; address?: string; rawValue?: unknown }
export interface DataSourceFile { fileId: string; fileName: string; contentType?: string; size?: number; revisionId: string }
export interface DataCategory { id: string; name: string; targetDataType?: DataType; sortOrder: number; assetCount: number }

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
  async createJob(input: { sourceFileId: string; templateVersionId: string; targetDataType: DataType; categoryId?: string; duplicateOverride?: boolean }) {
    const response = await httpClient.post<ApiResponse<DataJob>>('/api/v2/data/import-jobs', input); return response.data.data;
  },
  async getJob(id: string) { const response = await httpClient.get<ApiResponse<DataJob>>(`/api/v2/data/import-jobs/${id}`); return response.data.data; },
  async listJobs(params: { targetDataType?: DataType; templateVersionId?: string; status?: string; keyword?: string; page?: number; size?: number } = {}) {
    const response = await httpClient.get<ApiResponse<PageResponse<DataJob>>>('/api/v2/data/import-jobs', { params });
    return response.data.data;
  },
  async parse(id: string) { const response = await httpClient.post<ApiResponse<DataJob>>(`/api/v2/data/import-jobs/${id}/parse`); return response.data.data; },
  async reExtract(id: string) { const response = await httpClient.post<ApiResponse<DataJob>>(`/api/v2/data/import-jobs/${id}/re-extract`); return response.data.data; },
  async preview(id: string) { const response = await httpClient.get<ApiResponse<DataPreview>>(`/api/v2/data/import-jobs/${id}/preview`); return response.data.data; },
  async longTablePreview(id: string, limit = 20) { const response = await httpClient.get<ApiResponse<LongTablePreview>>(`/api/v2/data/import-jobs/${id}/long-table-preview`, { params: { limit } }); return response.data.data; },
  async getTrainingDatasetForJob(id: string) { const response = await httpClient.get<ApiResponse<TrainingDataset>>(`/api/v2/data/import-jobs/${id}/training-dataset`); return response.data.data; },
  async getTrainingDataset(id: string) { const response = await httpClient.get<ApiResponse<TrainingDataset>>(`/api/v2/data/training-datasets/${id}`); return response.data.data; },
  async approveTrainingDataset(id: string) { await httpClient.post(`/api/v2/data/training-datasets/${id}/approve`); },
  async rebuildTrainingDataset(id: string) { const response = await httpClient.post<ApiResponse<ProjectionSummary>>(`/api/v2/data/training-datasets/${id}/rebuild`); return response.data.data; },
  async saveSheets(id: string, items: Array<Pick<DataSheet, 'sheetId' | 'selected' | 'headerRows' | 'dataStartRow' | 'dataEndRow' | 'confirmationStatus'>>) { await httpClient.put(`/api/v2/data/import-jobs/${id}/sheets`, { items }); },
  async saveMappings(id: string, items: DataMapping[]) { await httpClient.put(`/api/v2/data/import-jobs/${id}/mappings`, { items }); },
  async resolveIssue(id: string, issueId: string, status: string) { await httpClient.put(`/api/v2/data/import-jobs/${id}/issues/${issueId}`, { status }); },
  async requestField(id: string, input: { fieldId?: string; displayName: string; valueType?: string; uiType?: string; groupCode?: string; description?: string }) { const response = await httpClient.post<ApiResponse<{ id: string; status: string }>>(`/api/v2/data/import-jobs/${id}/field-requests`, input); return response.data.data; },
  async commit(id: string) { const response = await httpClient.post<ApiResponse<DataJob>>(`/api/v2/data/import-jobs/${id}/commit`); return response.data.data; },
  async listAssets(params: { targetDataType?: DataType; categoryId?: string; status?: string; keyword?: string; page?: number; size?: number } = {}) { const response = await httpClient.get<ApiResponse<PageResponse<DataAsset>>>('/api/v2/data/assets', { params }); return response.data.data; },
  async listCategories() { const response = await httpClient.get<ApiResponse<DataCategory[]>>('/api/v2/data/categories'); return response.data.data; },
  async createCategory(input: { name: string; targetDataType?: DataType }) { const response = await httpClient.post<ApiResponse<DataCategory>>('/api/v2/data/categories', input); return response.data.data; },
  async renameCategory(id: string, name: string) { const response = await httpClient.put<ApiResponse<DataCategory>>(`/api/v2/data/categories/${id}`, { name }); return response.data.data; },
  async deleteCategory(id: string, replacementCategoryId?: string) { await httpClient.delete(`/api/v2/data/categories/${id}`, { params: replacementCategoryId ? { replacementCategoryId } : undefined }); },
  async assignCategory(assetId: string, categoryId: string) { await httpClient.put(`/api/v2/data/assets/${assetId}/category`, { categoryId }); },
  async exportAssets(input: { targetDataType: DataType; templateVersionId: string; assetIds: string[] }) {
    const response = await httpClient.post<Blob>('/api/v2/data/assets/export', input, { responseType: 'blob' });
    return response.data;
  },
  async getAsset(id: string) { const response = await httpClient.get<ApiResponse<DataAssetDetail>>(`/api/v2/data/assets/${id}`); return response.data.data; },
  async listRevisions(id: string) { const response = await httpClient.get<ApiResponse<DataRevision[]>>(`/api/v2/data/assets/${id}/revisions`); return response.data.data; },
  async listSources(id: string) { const response = await httpClient.get<ApiResponse<DataSourceAnchor[]>>(`/api/v2/data/assets/${id}/source`); return response.data.data; },
  async sourceBlob(fileId: string) { return fetchFileBlob(fileId); },
  async resolveSourceFile(assetId: string, revisionId?: string): Promise<DataSourceFile | undefined> {
    const [asset, sources] = await Promise.all([dataApi.getAsset(assetId), dataApi.listSources(assetId)]);
    const source = sources.find((item) => revisionId && item.revisionId === revisionId) || sources.find((item) => item.revisionId === asset.currentRevisionId) || sources[0];
    if (!source) return undefined;
    const job = asset.importJobId ? await dataApi.getJob(asset.importJobId) : undefined;
    return {
      fileId: source.fileId,
      fileName: job?.sourceFileName || `${asset.displayName || asset.assetKey || 'data-asset'}-原始文件`,
      revisionId: source.revisionId,
    };
  },
};
