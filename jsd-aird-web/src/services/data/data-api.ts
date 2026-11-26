import type { WorkbookTemplateInstance } from '@/features/template-workspace/workbook-instance';
import type { ApiResponse, PageResponse } from '@/types/api';
import { httpClient, LARGE_PAYLOAD_TIMEOUT, LARGE_WRITE_TIMEOUT } from '@/services/http/client';
import { sharedAbortableRead } from '@/services/http/inflight-reads';
import { fetchFileBlob, fetchFilePreviewBlob } from '@/services/files';
import type { ProjectRelationTarget, RelatedProjectView } from '@/services/project/project-resource-api';

export interface DataTemplateOption {
  templateId: string;
  versionId: string;
  templateCode: string;
  name: string;
  category?: string;
  versionNo: number;
  format: 'XLSX' | 'DOCX';
  importContractVersion?: number;
  templateUsage?: 'GENERAL_DATA' | 'EXPERIMENT_DATA';
  experimentImportReady?: boolean;
  recordMode?: 'SINGLE_FILE' | 'BY_IDENTITY';
  identityTypes?: string[];
  identityCount?: number;
}
export interface DataJob { revision: string; id: string; sourceFileId: string; sourceSha256: string; sourceFileName: string; sourceFormat: string; templateVersionId?: string; categoryId?: string; status: string; progress: number; currentStage?: string; parserVersion?: string; errorMessage?: string; createdAt: string; updatedAt: string; importContractVersion?: number; contractHash?: string; compatibilityStatus?: 'LEGACY' | 'EXACT' | 'COMPATIBLE' | 'REVIEW_REQUIRED' | 'INCOMPATIBLE'; importPurpose?: 'DATA_ONLY' | 'EXPERIMENT_DRAFT'; targetExperimentCategoryId?: string }
export interface DataSheet { id: string; sheetId: string; sheetName: string; sheetOrder: number; selected: boolean; headerRows: number[]; dataStartRow?: number; dataEndRow?: number; structure: Record<string, unknown>; confirmationStatus: string }
export interface DataMapping { id?: string; sheetId: string; sourceColumn: string; sourceHeader?: string; fieldCode?: string; fieldName?: string; action: string; valueType?: string; sourceUnit?: string; standardUnit?: string; detail?: Record<string, unknown>; status?: string }
export interface NormalizedValue { fieldCode?: string; bindingId?: string; valuePath?: string; labelPath?: string; rawValue?: unknown; normalizedValue?: unknown; correctedValue?: unknown; normalizedUnit?: string; valueSource?: string; calculationStatus?: string; formulaTrustStatus?: string }
export interface DataRow { id: string; sheetId: string; rowNumber: number; rawValues: Record<string, unknown>; normalizedValues: Record<string, NormalizedValue>; correctedValues: Record<string, NormalizedValue>; sourceMetadata?: Record<string, unknown>; status: string; excluded?: boolean; exclusionReason?: string }
export interface DataIssue { id: string; sheetId?: string; fieldCode?: string; severity: 'INFO' | 'WARNING' | 'BLOCKER'; issueType: string; rowNumber?: number; column?: string; address?: string; message: string; detail: { recordId?: string; [key: string]: unknown }; status: string }
export interface TemplateFieldDefinition { fieldCode: string; displayName: string; dataType: string; defaultUnit?: string; required: boolean; identity: boolean; aliases: string[]; dataPath: string }
export interface ImportBinding { bindingId: string; fieldCode: string; dataPath: string; mappingKind: string; parentBindingId?: string; repeatAxis?: string; recordHeight: number; recordWidth: number; recordStride: number; terminationRule?: Record<string, unknown>; locator?: Record<string, unknown>; required: boolean; identity: boolean; trainingEligible: boolean; valueSource?: string; valueType?: string; unit?: string; labelPath?: string; trainingRole?: 'FEATURE' | 'TARGET' | 'CONTEXT' | 'EXCLUDE'; ragEligible?: boolean }
export interface TemplateContract { importContractVersion?: number; layoutStructureVersion?: number; contractHash?: string; contract?: Record<string, unknown>; fields: TemplateFieldDefinition[]; bindings: ImportBinding[] }
export interface ProjectionSummary { datasetId?: string; status: string; recordCount: number; longValueCount: number; eligibleRecordCount: number }
export interface FieldResolution { bindingId: string; fieldCode: string; displayName: string; componentId: string; status: 'ORIGINAL' | 'RELOCATED' | 'MISSING_OPTIONAL' | 'MISSING_REQUIRED' | 'AMBIGUOUS'; required: boolean; sheetId?: string; originalLabelRange?: string; resolvedLabelRange?: string; candidateAddresses?: string[]; reasonCode?: string }
export interface ComponentMatch { componentId: string; status: 'EXACT' | 'COMPATIBLE' | 'REVIEW_REQUIRED' | 'INCOMPATIBLE'; required: boolean; score: number; sheetId?: string; sheetName?: string; anchorCoverage?: number; geometryCompatible?: boolean; formulaRoleCompatible?: boolean; manuallyReanchored?: boolean; resolutionReasonCodes?: string[]; affectedFieldIds?: string[]; fieldResolutions?: FieldResolution[] }
export interface ComponentOverride { componentId: string; sheetId: string; sourceRange: string; reason: string; updatedAt?: string }
export interface DataPreview { job: DataJob; sheets: DataSheet[]; mappings: DataMapping[]; rows: DataRow[]; issues: DataIssue[]; templateContract?: TemplateContract; projectionSummary?: ProjectionSummary; compatibilityReport?: { status?: string; componentMatches?: ComponentMatch[]; fieldResolutions?: FieldResolution[] }; componentOverrides?: ComponentOverride[] }
export interface DataCategory {
  revision: string; id: string; name: string; description?: string; sortOrder: number; sourceCount: number; allowedActions?: string[] }
export interface DataSourceFacets { categories: DataCategory[]; totalSourceCount: number; uncategorizedSourceCount: number }
export interface DataSourceFile { revision: string; importJobId: string; fileObjectId?: string; originalName: string; sourceFormat: string; templateVersionId?: string; categoryId?: string; categoryName?: string; status: string; progress: number; createdAt: string; updatedAt: string; relatedProjects?: RelatedProjectView[]; recognitionMode: 'FREEFORM' | 'TEMPLATE_GUIDED'; formalStatus: DataPublication['status'] | 'SUPERSEDED' | 'INVALIDATED' | 'NOT_CONFIRMED'; experimentId?: string; experimentVersionId?: string }
export interface DataWorkbookSheet { sheetId: string; sheetName: string; sheetOrder: number; selected: boolean; confirmationStatus: string }
export interface DataWorkbookFieldGroup { groupId: string; name: string; fieldCount: number }
export interface DataWorkbookRegion { regionId: string; name: string; structureType: string; sheetId?: string; sheetName?: string; range?: string; recordAxis?: string; fieldCount: number; recordCount: number; fieldGroups: DataWorkbookFieldGroup[] }
export interface DataWorkbookRecord { recordId: string; regionId: string; label: string; sequence: number; sheetId?: string; sheetName?: string; address?: string; excluded: boolean }
export interface DataWorkbookFieldDefinition { componentId: string; bindingId: string; parentBindingId?: string; fieldCode: string; displayName: string; description?: string; labelPath?: string; mappingKind?: string; repeatAxis?: string; valueType?: string; unit?: string; required: boolean; identity: boolean; groupPath?: string; sheetId?: string; sheetName?: string; sourceRange?: string }
export interface DataFieldValueView { recordId?: string; fieldCode: string; fieldName: string; labelPath?: string; bindingId: string; valuePath: string; valueSource: string; valueStatus: string; valueType?: string; unit?: string; required: boolean; identity: boolean; trainingEligible: boolean; ragEligible: boolean; sheetId?: string; sheetName?: string; rowNumber?: number; address?: string; sourceLocation?: string; rawValue?: unknown; normalizedValue?: unknown; correctedValue?: unknown; effectiveValue?: unknown; editable: boolean; excluded: boolean; exclusionReason?: string; componentId?: string; mappingKind?: string; repeatAxis?: string; parentBindingId?: string; groupPath?: string; recordKey?: string; dimensions?: Record<string, unknown>; recordGroupId?: string }
export interface DataWorkbookSnapshot { fieldResultsSource?: 'SNAPSHOT' | 'STAGED'; templateInstance?: WorkbookTemplateInstance; fileName: string; contentType?: string; sourceFileHash?: string; format: string; snapshot: Record<string, unknown>; sheets: DataWorkbookSheet[]; selectedSheetId?: string; editable: boolean; regions: DataWorkbookRegion[]; fieldDefinitions?: DataWorkbookFieldDefinition[]; records: DataWorkbookRecord[]; fields: DataFieldValueView[] }
export interface DataPublicationSnapshot { id: string; versionNo: number; contentHash: string; confirmedSubmissionId?: string; confirmedBy: string; confirmedAt: string }
export interface DataPublicationEvent { id: string; snapshotId?: string; action: string; actorName: string; comment?: string; createdAt: string }
export interface DataPublication { revision: string; status: 'DRAFT' | 'CONFIRMED' | 'PENDING_REVIEW' | 'RETURNED' | 'APPROVED' | 'PUBLISHED' | 'DISABLED'; confirmedSnapshot?: DataPublicationSnapshot; publishedSnapshotId?: string; legacyPublished: boolean; publishedEnabled: boolean; reviewer?: { id: string; name: string }; versions: DataPublicationSnapshot[]; events: DataPublicationEvent[] }
export interface DataFormalRecord { revision: string; id: string; recordKey: string; name: string; status: 'ACTIVE' | 'DISABLED'; reason?: string; sheetName?: string; rowNumber?: number; values: Record<string, unknown>; locations?: Array<{ bindingId: string; valuePath: string; sheetName?: string; address?: string }> }
export interface ExperimentAssemblyCandidate { assemblyKey: string; parentAssemblyKey?: string; sourceIdentity?: string; sourceIdentityType: string; status: 'READY' | 'NEEDS_REVIEW' | 'BLOCKED' | 'RUNNING' | 'SYNCED' | 'ALREADY_CREATED' | 'FAILED' | 'SKIPPED'; sourceRecordKeys: string[]; sharedContextRecordKeys: string[]; conflicts: Array<{ code: string; message: string }>; warnings: Array<{ code: string; message: string }>; editModel: Record<string, unknown>; planHash: string; contentHash: string }
export interface ExperimentAssemblyPlan { importJobId: string; candidates: ExperimentAssemblyCandidate[]; readyCount: number; reviewCount: number; blockedCount: number; sourceRecordCount: number; sampleGroupCount: number; sourceGroupCount: number; sourceContextCount: number; sourceIdentityCount: number; logicalSampleCount: number }
export interface ExperimentAssemblyLink { assemblyKey: string; parentAssemblyKey?: string; status: string; resolutionAction?: string; resolutionReason?: string; experimentId?: string; experimentVersionId?: string; experimentNo?: string; planHash: string; contentHash: string; errorMessage?: string }
export interface ExperimentSyncStatus { plan: ExperimentAssemblyPlan | null; links: ExperimentAssemblyLink[]; preparationStatus?: string; experiments?: import('@/services/experiments/experiment-status').LinkedExperimentStatus[] }

export interface ExperimentConversion {
  id: string; batchId: string; sourceFileId: string; sourceName: string; sourceSha256: string;
  templateVersionId: string; categoryId?: string; kind: 'test' | 'synthesis';
  status: 'QUEUED' | 'WAITING_REVIEW' | 'FAILED'; importJobId?: string;
  resultArtifactId?: string; planArtifactId?: string; reportArtifactId?: string;
  report?: { status: string; human_review: string; unplaced?: unknown[]; source_conflicts?: unknown[]; limitations?: string };
  errorMessage?: string; createdAt: string; updatedAt: string;
}

export const dataApi = {
  async createConversion(input: { batchId: string; sourceFileId: string; templateVersionId: string; categoryId?: string }) {
    const response = await httpClient.post<ApiResponse<ExperimentConversion>>('/api/v1/data/experiment-conversions', input); return response.data.data;
  },
  async conversions(batchId?: string) {
    const response = await httpClient.get<ApiResponse<ExperimentConversion[]>>('/api/v1/data/experiment-conversions', { params: { batchId } }); return response.data.data;
  },
  async startConversions(batchId: string, retryFailed = false) {
    const response = await httpClient.post<{ tasks: Array<{ conversion_id: string; session_id: string; accepted: boolean }> }>('/agent/business/conversion-start', { batch_id: batchId, retry_failed: retryFailed }, { timeout: 120000 }); return response.data;
  },
  async publication(id: string) { const response = await httpClient.get<ApiResponse<DataPublication>>(`/api/v1/data/sources/${id}/publication`); return response.data.data; },
  async publicationAction(id: string, action: 'confirm' | 'submit' | 'withdraw' | 'revise' | 'publish' | 'disable' | 'restore', revision: string, reviewerId?: string) { const response = await httpClient.post<ApiResponse<DataPublication>>(`/api/v1/data/sources/${id}/publication/${action}`, action === 'submit' ? { reviewerId, revision } : { revision }); return response.data.data; },
  async dataReviewers(id: string) { const response = await httpClient.get<ApiResponse<Array<{ id: string; name: string }>>>(`/api/v1/data/sources/${id}/publication/reviewers`); return response.data.data; },
  async review(id: string, snapshotId: string, approved: boolean, revision: string, comment?: string) { const response = await httpClient.post<ApiResponse<DataPublication>>(`/api/v1/data/sources/${id}/publication/approve`, { snapshotId, approved, comment, revision }); return response.data.data; },
  async publicationSnapshot(id: string, snapshotId: string) { const response = await httpClient.get<ApiResponse<Record<string, unknown>>>(`/api/v1/data/sources/${id}/publication/snapshots/${snapshotId}`); return response.data.data; },
  async formalRecords(id: string) { const response = await httpClient.get<ApiResponse<DataFormalRecord[]>>(`/api/v1/data/sources/${id}/records`); return response.data.data; },
  async governRecord(id: string, recordId: string, input: { revision: string; name: string; status: string; reason?: string }) { await httpClient.put(`/api/v1/data/sources/${id}/records/${recordId}/governance`, input); },
  async exportRecords(id: string) { const response = await httpClient.get<Blob>(`/api/v1/data/sources/${id}/records/export`, { responseType: 'blob' }); return response.data; },
  async listTemplates(signal?: AbortSignal) {
    const response = await httpClient.get<ApiResponse<DataTemplateOption[]>>('/api/v1/data/templates', { signal });
    return response.data.data;
  },
  async stageSource(file: File, onProgress?: (percent: number) => void) {
    const body = new FormData(); body.append('file', file);
    const response = await httpClient.post<ApiResponse<{ fileId: string; sha256: string; status: string }>>(
      '/api/v1/files/staged?kind=DATA_SOURCE',
      body,
      {
        onUploadProgress: (event) => {
          const total = event.total || file.size;
          if (total > 0) onProgress?.(Math.min(100, Math.round((event.loaded / total) * 100)));
        },
      },
    );
    return response.data.data;
  },
  async createJob(input: { sourceFileId: string; templateVersionId: string; categoryId?: string; duplicateOverride?: boolean; projectRelations?: ProjectRelationTarget[]; importPurpose?: 'DATA_ONLY' | 'EXPERIMENT_DRAFT'; targetExperimentCategoryId?: string }) {
    const response = await httpClient.post<ApiResponse<DataJob>>('/api/v1/data/import-jobs', input); return response.data.data;
  },
  async createExperimentJob(input: { sourceFileId: string; templateVersionId: string; categoryId?: string; duplicateOverride?: boolean; projectRelations?: ProjectRelationTarget[]; importPurpose: 'EXPERIMENT_DRAFT'; targetExperimentCategoryId?: string }) {
    const response = await httpClient.post<ApiResponse<DataJob>>('/api/v1/data/experiment-import-jobs', input); return response.data.data;
  },
  async getJob(id: string) { const response = await httpClient.get<ApiResponse<DataJob>>(`/api/v1/data/import-jobs/${id}`); return response.data.data; },
  async listJobs(params: { templateVersionId?: string; status?: string; keyword?: string; page?: number; size?: number } = {}, signal?: AbortSignal) {
    const response = await httpClient.get<ApiResponse<PageResponse<DataJob>>>('/api/v1/data/import-jobs', { params, signal });
    return response.data.data;
  },
  async listExperimentJobs(params: { templateVersionId?: string; status?: string; keyword?: string; page?: number; size?: number } = {}) {
    const response = await httpClient.get<ApiResponse<PageResponse<DataJob>>>('/api/v1/data/experiment-import-jobs', { params });
    return response.data.data;
  },
  async retryExperimentJob(id: string, revision: string) {
    const response = await httpClient.post<ApiResponse<DataJob>>(`/api/v1/data/experiment-import-jobs/${id}/retry`, { revision });
    return response.data.data;
  },
  async reprocessJob(id: string, revision: string) {
    const response = await httpClient.post<ApiResponse<DataJob>>(`/api/v1/data/import-jobs/${id}/retry`, { revision });
    return response.data.data;
  },
  async hideJob(id: string, revision: string) { await httpClient.delete(`/api/v1/data/import-jobs/${id}`, { params: { revision } }); },
  async deleteExperimentJob(id: string, revision: string) {
    await httpClient.delete(`/api/v1/data/experiment-import-jobs/${id}`, { params: { revision } });
  },
  async parse(id: string, revision: string) { const response = await httpClient.post<ApiResponse<DataJob>>(`/api/v1/data/import-jobs/${id}/parse`, { revision }); return response.data.data; },
  async reExtract(id: string, revision: string) { const response = await httpClient.post<ApiResponse<DataJob>>(`/api/v1/data/import-jobs/${id}/re-extract`, { revision }, { timeout: LARGE_WRITE_TIMEOUT }); return response.data.data; },
  preview(id: string, experiment = false, view: 'full' | 'summary' = 'full', signal?: AbortSignal) {
    return sharedAbortableRead(`import-preview:${experiment}:${id}:${view}`, async requestSignal => {
      const response = await httpClient.get<ApiResponse<DataPreview>>(`/api/v1/data/${experiment ? 'experiment-import-jobs' : 'import-jobs'}/${id}/preview`, { params: { view }, signal: requestSignal, timeout: LARGE_PAYLOAD_TIMEOUT });
      return response.data.data;
    }, signal).then(value => structuredClone(value));
  },
  async saveWorkbookDraft(id: string, input: { revision: string; snapshot: Record<string, unknown>; templateInstance: WorkbookTemplateInstance; structureOperations: unknown[] }, experiment = false) { const response = await httpClient.put<ApiResponse<{ job: DataJob; workbook?: DataWorkbookSnapshot | null }>>(`/api/v1/data/${experiment ? 'experiment-import-jobs' : 'import-jobs'}/${id}/workbook-draft`, input, { timeout: LARGE_WRITE_TIMEOUT }); const result = response.data.data;
    if (result.workbook) {
      const sheets = Object.values((result.workbook.snapshot.sheets ?? {}) as Record<string, { custom?: { templateInstance?: WorkbookTemplateInstance } }>);
      result.workbook.templateInstance = sheets.map(sheet => sheet.custom?.templateInstance).find(instance => instance?.version === 2);
      if (result.workbook.fieldResultsSource === 'SNAPSHOT' && result.workbook.templateInstance) {
        const { buildImportWorkbookView } = await import('@/features/data-workbench/import-workbook-loader');
        const view = await buildImportWorkbookView(result.workbook.snapshot, result.workbook.templateInstance);
        result.workbook = { ...result.workbook, regions: view.regions, records: view.records,
          fields: view.fields, fieldDefinitions: view.fieldDefinitions };
      }
    }
    return result; },
  async getImportWorkbookSnapshot(id: string, experiment = false, signal?: AbortSignal) {
    const text = await sharedAbortableRead(`import-workbook:${experiment}:${id}`, async requestSignal => {
      const response = await httpClient.get<string>(`/api/v1/data/${experiment ? 'experiment-import-jobs' : 'import-jobs'}/${id}/workbook-snapshot`, {
        params: { view: 'workspace' }, responseType: 'text', transformResponse: value => value, signal: requestSignal, timeout: LARGE_PAYLOAD_TIMEOUT,
      });
      return response.data;
    }, signal);
    const { decodeImportWorkbook } = await import('@/features/data-workbench/import-workbook-loader');
    return decodeImportWorkbook(text, signal);
  },
  async importFieldResults(id: string, input: { revision: string; sheetId?: string; recordId?: string }, experiment = false, signal?: AbortSignal) {
    const response = await httpClient.get<ApiResponse<{ revision: string; fields: DataFieldValueView[] }>>(`/api/v1/data/${experiment ? 'experiment-import-jobs' : 'import-jobs'}/${id}/field-results`, { params: input, signal, timeout: LARGE_PAYLOAD_TIMEOUT });
    return response.data.data;
  },
  async saveSheets(id: string, revision: string, items: Array<Pick<DataSheet, 'sheetId' | 'selected' | 'headerRows' | 'dataStartRow' | 'dataEndRow' | 'confirmationStatus'>>) { const response = await httpClient.put<ApiResponse<DataJob>>(`/api/v1/data/import-jobs/${id}/sheets`, { revision, items }); return response.data.data; },
  async saveMappings(id: string, revision: string, items: DataMapping[]) { const response = await httpClient.put<ApiResponse<DataJob>>(`/api/v1/data/import-jobs/${id}/mappings`, { revision, items }, { timeout: LARGE_WRITE_TIMEOUT }); return response.data.data; },
  async resolveIssue(id: string, revision: string, issueId: string, status: 'IGNORED', reason: string) { const response = await httpClient.put<ApiResponse<DataJob>>(`/api/v1/data/import-jobs/${id}/issues/${issueId}`, { revision, status, reason }); return response.data.data; },
  async correctValue(id: string, recordId: string, input: { revision: string; bindingId: string; valuePath: string; correctedValue: unknown; reason: string }) { const response = await httpClient.put<ApiResponse<DataJob>>(`/api/v1/data/import-jobs/${id}/records/${recordId}/values`, input); return response.data.data; },
  async excludeRecord(id: string, revision: string, recordId: string, excluded: boolean, reason?: string) { const response = await httpClient.put<ApiResponse<DataJob>>(`/api/v1/data/import-jobs/${id}/records/${recordId}/exclusion`, { revision, excluded, reason }); return response.data.data; },
  async reanchorComponent(id: string, componentId: string, input: { revision: string; sheetId: string; sourceRange: string; reason: string }) { const response = await httpClient.put<ApiResponse<DataJob>>(`/api/v1/data/import-jobs/${id}/components/${encodeURIComponent(componentId)}/anchor`, input); return response.data.data; },
  async requestField(id: string, input: { fieldId?: string; displayName: string; valueType?: string; uiType?: string; groupCode?: string; description?: string }) { const response = await httpClient.post<ApiResponse<{ id: string; status: string }>>(`/api/v1/data/import-jobs/${id}/field-requests`, input); return response.data.data; },
  async commit(id: string, experiment = false, revision?: string) { const response = await httpClient.post<ApiResponse<DataJob>>(`/api/v1/data/${experiment ? 'experiment-import-jobs' : 'import-jobs'}/${id}/commit`, revision ? { revision } : undefined, { timeout: LARGE_WRITE_TIMEOUT }); return response.data.data; },
  async experimentPlan(id: string) { const response = await httpClient.get<ApiResponse<ExperimentAssemblyPlan>>(`/api/v1/data/experiment-import-jobs/${id}/experiments`); return response.data.data; },
  async syncExperiments(id: string, input: { categoryId?: string; assemblyKeys?: string[] }) { const response = await httpClient.post<ApiResponse<{ acceptedCount: number; jobIds: string[] }>>(`/api/v1/data/experiment-import-jobs/${id}/experiment-sync`, input); return response.data.data; },
  async experimentSyncStatus(id: string, view: 'full' | 'summary' = 'full') { const response = await httpClient.get<ApiResponse<ExperimentSyncStatus>>(`/api/v1/data/experiment-import-jobs/${id}/experiment-sync-status`, { params: { view } }); return response.data.data; },
  async listSourceFiles(params: { categoryId?: string; uncategorized?: boolean; status?: string; keyword?: string; projectId?: string; page?: number; size?: number } = {}, signal?: AbortSignal) { const response = await httpClient.get<ApiResponse<PageResponse<DataSourceFile>>>('/api/v1/data/sources', { params, signal }); return response.data.data; },
  async listCategories(signal?: AbortSignal) { const response = await httpClient.get<ApiResponse<DataCategory[]>>('/api/v1/data/categories', { signal }); return response.data.data; },
  async sourceFacets(signal?: AbortSignal) { const response = await httpClient.get<ApiResponse<DataSourceFacets>>('/api/v1/data/sources/facets', { signal }); return response.data.data; },

  async createCategory(input: { name: string; description?: string }) { const response = await httpClient.post<ApiResponse<DataCategory>>('/api/v1/data/categories', input); return response.data.data; },
  async renameCategory(id: string, input: { revision: string; name: string; description?: string }) { const response = await httpClient.put<ApiResponse<DataCategory>>(`/api/v1/data/categories/${id}`, input); return response.data.data; },
  async deleteCategory(id: string, revision: string, replacementCategoryId?: string) { await httpClient.delete(`/api/v1/data/categories/${id}`, { params: { replacementCategoryId, revision } }); },
  async assignSourceCategory(importJobId: string, revision: string, categoryId?: string) { await httpClient.put(`/api/v1/data/sources/${importJobId}/category`, { categoryId: categoryId || null, revision }); },
  async sourceBlob(fileId: string) { return fetchFileBlob(fileId); },
  async sourcePreviewBlob(fileId: string) { return fetchFilePreviewBlob(fileId); },
};
