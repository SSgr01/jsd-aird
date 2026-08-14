import type { ApiResponse, PageResponse } from '@/types/api';
import { httpClient } from '@/services/http/client';
import { fetchFileBlob } from '@/services/files';

export interface DataTemplateOption { templateId: string; versionId: string; templateCode: string; name: string; category?: string; versionNo: number; format: 'XLSX' | 'DOCX' }
export interface DataJob { id: string; sourceFileId: string; sourceSha256: string; sourceFileName: string; sourceFormat: string; templateVersionId: string; categoryId?: string; status: string; progress: number; currentStage?: string; parserVersion?: string; errorMessage?: string; createdAt: string; updatedAt: string; importContractVersion?: number; contractHash?: string; compatibilityStatus?: 'LEGACY' | 'EXACT' | 'COMPATIBLE' | 'REVIEW_REQUIRED' | 'INCOMPATIBLE' }
export interface DataSheet { id: string; sheetId: string; sheetName: string; sheetOrder: number; selected: boolean; headerRows: number[]; dataStartRow?: number; dataEndRow?: number; structure: Record<string, unknown>; confirmationStatus: string }
export interface DataMapping { id?: string; sheetId: string; sourceColumn: string; sourceHeader?: string; fieldCode?: string; fieldName?: string; action: string; valueType?: string; sourceUnit?: string; standardUnit?: string; detail?: Record<string, unknown>; status?: string }
export interface NormalizedValue { fieldCode?: string; bindingId?: string; valuePath?: string; labelPath?: string; rawValue?: unknown; normalizedValue?: unknown; correctedValue?: unknown; normalizedUnit?: string; valueSource?: string; calculationStatus?: string; formulaTrustStatus?: string }
export interface DataRow { id: string; sheetId: string; rowNumber: number; rawValues: Record<string, unknown>; normalizedValues: Record<string, NormalizedValue>; correctedValues: Record<string, NormalizedValue>; sourceMetadata?: Record<string, unknown>; status: string; excluded?: boolean; exclusionReason?: string }
export interface DataIssue { id: string; sheetId?: string; fieldCode?: string; severity: 'INFO' | 'WARNING' | 'BLOCKER'; issueType: string; rowNumber?: number; column?: string; address?: string; message: string; detail: { recordId?: string; [key: string]: unknown }; status: string }
export interface TemplateFieldDefinition { fieldCode: string; displayName: string; dataType: string; defaultUnit?: string; required: boolean; identity: boolean; aliases: string[]; dataPath: string }
export interface ImportBinding { bindingId: string; fieldCode: string; dataPath: string; mappingKind: string; parentBindingId?: string; repeatAxis?: string; recordHeight: number; recordWidth: number; recordStride: number; terminationRule?: Record<string, unknown>; locator?: Record<string, unknown>; required: boolean; identity: boolean; trainingEligible: boolean; valueSource?: string; valueType?: string; unit?: string; labelPath?: string; trainingRole?: 'FEATURE' | 'TARGET' | 'CONTEXT' | 'EXCLUDE'; ragEligible?: boolean }
export interface TemplateContract { importContractVersion?: number; layoutStructureVersion?: number; contractHash?: string; contract?: Record<string, unknown>; fields: TemplateFieldDefinition[]; bindings: ImportBinding[] }
export interface ProjectionSummary { datasetId?: string; status: string; recordCount: number; longValueCount: number; eligibleRecordCount: number }
export interface ComponentMatch { componentId: string; status: 'EXACT' | 'COMPATIBLE' | 'REVIEW_REQUIRED' | 'INCOMPATIBLE'; required: boolean; score: number; sheetId?: string; sheetName?: string; anchorCoverage?: number; geometryCompatible?: boolean; formulaRoleCompatible?: boolean; manuallyReanchored?: boolean; resolutionReasonCodes?: string[]; affectedFieldIds?: string[] }
export interface ComponentOverride { componentId: string; sheetId: string; sourceRange: string; reason: string; updatedAt?: string }
export interface LongTableRow { recordKey: string; dimensions: Record<string, unknown>; measures: Record<string, unknown>; source: Record<string, unknown>; trainingEligible: boolean; exclusionReason?: string }
export interface LongTablePreview { dataset: TrainingDataset; rows: LongTableRow[] }
export interface TrainingDataset { id: string; importJobId?: string; templateVersionId: string; projectionVersion: string; name: string; status: string; schema: Record<string, unknown>; qualitySummary: Record<string, unknown>; sourceRecordIds: string[]; recordCount: number; eligibleRecordCount: number }
export interface DataPreview { job: DataJob; sheets: DataSheet[]; mappings: DataMapping[]; rows: DataRow[]; issues: DataIssue[]; templateContract?: TemplateContract; projectionSummary?: ProjectionSummary; compatibilityReport?: { status?: string; componentMatches?: ComponentMatch[] }; componentOverrides?: ComponentOverride[] }
export interface DataCategory { id: string; name: string; description?: string; sortOrder: number; sourceCount: number }
export interface DataSourceFile { importJobId: string; fileObjectId: string; originalName: string; sourceFormat: string; templateVersionId: string; categoryId?: string; categoryName?: string; status: string; progress: number; createdAt: string; updatedAt: string; sheetCount: number; recordCount: number; fieldCount: number }
export interface DataWorkbookSheet { sheetId: string; sheetName: string; sheetOrder: number; selected: boolean; confirmationStatus: string }
export interface DataWorkbookFieldGroup { groupId: string; name: string; fieldCount: number }
export interface DataWorkbookRegion { regionId: string; name: string; structureType: string; sheetId?: string; sheetName?: string; range?: string; recordAxis?: string; fieldCount: number; recordCount: number; fieldGroups: DataWorkbookFieldGroup[] }
export interface DataWorkbookRecord { recordId: string; regionId: string; label: string; sequence: number; sheetId?: string; sheetName?: string; address?: string; excluded: boolean }
export interface DataWorkbookFieldDefinition { componentId: string; bindingId: string; parentBindingId?: string; fieldCode: string; displayName: string; description?: string; labelPath?: string; mappingKind?: string; repeatAxis?: string; valueType?: string; unit?: string; required: boolean; identity: boolean; groupPath?: string; sheetId?: string; sheetName?: string; sourceRange?: string }
export interface DataFieldValueView { recordId?: string; fieldCode: string; fieldName: string; labelPath?: string; bindingId: string; valuePath: string; valueSource: string; valueStatus: string; valueType?: string; unit?: string; required: boolean; identity: boolean; trainingEligible: boolean; ragEligible: boolean; sheetId?: string; sheetName?: string; rowNumber?: number; address?: string; rawValue?: unknown; normalizedValue?: unknown; correctedValue?: unknown; effectiveValue?: unknown; editable: boolean; excluded: boolean; exclusionReason?: string; componentId?: string; mappingKind?: string; repeatAxis?: string; parentBindingId?: string; groupPath?: string; recordKey?: string; dimensions?: Record<string, unknown>; recordGroupId?: string }
export interface DataWorkbookSnapshot { fileName: string; contentType?: string; sourceFileHash?: string; format: string; snapshot: Record<string, unknown>; sheets: DataWorkbookSheet[]; selectedSheetId?: string; editable: boolean; regions: DataWorkbookRegion[]; fieldDefinitions?: DataWorkbookFieldDefinition[]; records: DataWorkbookRecord[]; fields: DataFieldValueView[] }

export const dataApi = {
  async listTemplates() {
    const response = await httpClient.get<ApiResponse<DataTemplateOption[]>>('/api/v1/data/templates');
    return response.data.data;
  },
  async stageSource(file: File) {
    const body = new FormData(); body.append('file', file);
    const response = await httpClient.post<ApiResponse<{ fileId: string; sha256: string; status: string }>>('/api/v1/files/staged?kind=DATA_SOURCE', body);
    return response.data.data;
  },
  async createJob(input: { sourceFileId: string; templateVersionId: string; categoryId?: string; duplicateOverride?: boolean }) {
    const response = await httpClient.post<ApiResponse<DataJob>>('/api/v1/data/import-jobs', input); return response.data.data;
  },
  async getJob(id: string) { const response = await httpClient.get<ApiResponse<DataJob>>(`/api/v1/data/import-jobs/${id}`); return response.data.data; },
  async listJobs(params: { templateVersionId?: string; status?: string; keyword?: string; page?: number; size?: number } = {}) {
    const response = await httpClient.get<ApiResponse<PageResponse<DataJob>>>('/api/v1/data/import-jobs', { params });
    return response.data.data;
  },
  async parse(id: string) { const response = await httpClient.post<ApiResponse<DataJob>>(`/api/v1/data/import-jobs/${id}/parse`); return response.data.data; },
  async reExtract(id: string) { const response = await httpClient.post<ApiResponse<DataJob>>(`/api/v1/data/import-jobs/${id}/re-extract`); return response.data.data; },
  async preview(id: string) { const response = await httpClient.get<ApiResponse<DataPreview>>(`/api/v1/data/import-jobs/${id}/preview`); return response.data.data; },
  async getImportWorkbookSnapshot(id: string) { const response = await httpClient.get<ApiResponse<DataWorkbookSnapshot>>(`/api/v1/data/import-jobs/${id}/workbook-snapshot`); return response.data.data; },
  async longTablePreview(id: string, limit = 20) { const response = await httpClient.get<ApiResponse<LongTablePreview>>(`/api/v1/data/import-jobs/${id}/long-table-preview`, { params: { limit } }); return response.data.data; },
  async getTrainingDatasetForJob(id: string) { const response = await httpClient.get<ApiResponse<TrainingDataset>>(`/api/v1/data/import-jobs/${id}/training-dataset`); return response.data.data; },
  async getTrainingDataset(id: string) { const response = await httpClient.get<ApiResponse<TrainingDataset>>(`/api/v1/data/training-datasets/${id}`); return response.data.data; },
  async rebuildTrainingDataset(id: string) { const response = await httpClient.post<ApiResponse<ProjectionSummary>>(`/api/v1/data/training-datasets/${id}/rebuild`); return response.data.data; },
  async saveSheets(id: string, items: Array<Pick<DataSheet, 'sheetId' | 'selected' | 'headerRows' | 'dataStartRow' | 'dataEndRow' | 'confirmationStatus'>>) { await httpClient.put(`/api/v1/data/import-jobs/${id}/sheets`, { items }); },
  async saveMappings(id: string, items: DataMapping[]) { await httpClient.put(`/api/v1/data/import-jobs/${id}/mappings`, { items }); },
  async resolveIssue(id: string, issueId: string, status: 'IGNORED', reason: string) { await httpClient.put(`/api/v1/data/import-jobs/${id}/issues/${issueId}`, { status, reason }); },
  async correctValue(id: string, recordId: string, input: { bindingId: string; valuePath: string; correctedValue: unknown; reason: string }) { await httpClient.put(`/api/v1/data/import-jobs/${id}/records/${recordId}/values`, input); },
  async excludeRecord(id: string, recordId: string, excluded: boolean, reason?: string) { await httpClient.put(`/api/v1/data/import-jobs/${id}/records/${recordId}/exclusion`, { excluded, reason }); },
  async reanchorComponent(id: string, componentId: string, input: { sheetId: string; sourceRange: string; reason: string }) { const response = await httpClient.put<ApiResponse<DataJob>>(`/api/v1/data/import-jobs/${id}/components/${encodeURIComponent(componentId)}/anchor`, input); return response.data.data; },
  async requestField(id: string, input: { fieldId?: string; displayName: string; valueType?: string; uiType?: string; groupCode?: string; description?: string }) { const response = await httpClient.post<ApiResponse<{ id: string; status: string }>>(`/api/v1/data/import-jobs/${id}/field-requests`, input); return response.data.data; },
  async commit(id: string) { const response = await httpClient.post<ApiResponse<DataJob>>(`/api/v1/data/import-jobs/${id}/commit`); return response.data.data; },
  async listSourceFiles(params: { categoryId?: string; status?: string; keyword?: string; page?: number; size?: number } = {}) { const response = await httpClient.get<ApiResponse<PageResponse<DataSourceFile>>>('/api/v1/data/sources', { params }); return response.data.data; },
  async listCategories() { const response = await httpClient.get<ApiResponse<DataCategory[]>>('/api/v1/data/categories'); return response.data.data; },
  async createCategory(input: { name: string; description?: string }) { const response = await httpClient.post<ApiResponse<DataCategory>>('/api/v1/data/categories', input); return response.data.data; },
  async renameCategory(id: string, input: { name: string; description?: string }) { const response = await httpClient.put<ApiResponse<DataCategory>>(`/api/v1/data/categories/${id}`, input); return response.data.data; },
  async deleteCategory(id: string, replacementCategoryId?: string) { await httpClient.delete(`/api/v1/data/categories/${id}`, { params: replacementCategoryId ? { replacementCategoryId } : undefined }); },
  async assignSourceCategory(importJobId: string, categoryId: string) { await httpClient.put(`/api/v1/data/sources/${importJobId}/category`, { categoryId }); },
  async sourceBlob(fileId: string) { return fetchFileBlob(fileId); },
};
