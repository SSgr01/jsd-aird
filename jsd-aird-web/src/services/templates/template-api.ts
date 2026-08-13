import type { ApiResponse, PageResponse } from '@/types/api';
import type {
  BusinessBlock,
  TemplateFormat,
  Editability,
  TemplateListItem,
  TemplateStatus,
  TemplateVersionHistoryItem,
  TemplateWorkspace,
  ValueSource,
  WorkbookStructureOperation,
  MappingKind,
  MatrixColumnSlot,
  MatrixModel,
  MatrixRecordProjection,
  RepeatAxis,
  StaticRegion,
  LongTableModel,
  DocumentStructure,
  TemplateBinding,
} from '@/features/template-workspace/types';
import { httpClient } from '@/services/http/client';

export interface CreateTemplateInput {
  name: string;
  category?: string;
  format: TemplateFormat;
  importJobId?: string;
}

export interface TemplateCategory {
  id: string;
  name: string;
  description?: string;
  sortOrder: number;
  templateCount: number;
}

export interface TemplateFacetSummary {
  totalCount: number;
  uncategorizedCount: number;
  categoryCounts: Array<{ categoryId: string; count: number }>;
}

export interface SaveTemplateDraftInput {
  lockVersion: number;
  baseWorkspaceHash: string;
  schema: Record<string, unknown>;
  mapping: TemplateWorkspace['mapping'];
  data: Record<string, unknown>;
  snapshotFileId?: string;
  snapshotHash?: string;
  editorAppVersion: string;
  pluginManifest: string;
  snapshotFormatVersion: number;
  clientCommandSummary: string;
  idempotencyKey: string;
  bindingValues: Array<{
    dataPath: string;
    dataValue: unknown;
    editorValue: unknown;
  }>;
  recognitionActions: RecognitionActionInput[];
  qualityActions: QualityActionInput[];
  structureOperations: WorkbookStructureOperation[];
  wordPatchBaseHash?: string;
  wordPatch?: Array<Record<string, unknown>>;
}

export interface SaveTemplateDraftResult {
  lockVersion: number;
  workspaceHash: string;
  reconciliationRequired: boolean;
  schema?: Record<string, unknown>;
  mapping?: TemplateBinding[];
  wordDocument?: TemplateWorkspace['wordDocument'];
  documentStructure?: TemplateWorkspace['documentStructure'];
}

export interface TemplateImportIssue {
  severity: 'INFO' | 'WARNING' | 'BLOCKER';
  code: string;
  message: string;
  location: Record<string, unknown>;
  resolution: string;
}

export interface TemplateImportRecognitionSummary {
  parseStatus?: string;
  runStatus?: string;
  modelStatus?: string;
  recognitionStatus?: string;
  reviewResolutionStatus?: string;
  canonicalStatus?: string;
  publicationReadiness?: string;
  coverage?: {
    status?: string;
    physicalRegionCount?: number;
    expectedRegionCount?: number;
    coveredRegionCount?: number;
    unresolvedRegionCount?: number;
    coverageRatio?: number;
    issues?: string[];
  };
  counts?: {
    rawSuggestions?: number;
    pendingSuggestions?: number;
    pendingFields?: number;
    reviewableFields?: number;
    structureCandidates?: number;
    structureConflictGroups?: number;
    qualityIssues?: number;
  };
}

export interface TemplateImportJob {
  id: string;
  sourceFileId: string;
  sourceFileName: string;
  format: TemplateFormat;
  status: string;
  progress: number;
  currentStage?: string;
  structureSummary: Record<string, unknown>;
  result: {
    initialEditorSnapshot?: Record<string, unknown>;
    modelStatus?:
      'COMPLETED' | 'FAILED' | 'PARTIAL' | 'TRUNCATED' | 'NOT_CONFIGURED' | 'NOT_APPLICABLE';
    recognitionStatus?: 'COMPLETE' | 'REVIEW_REQUIRED' | 'NO_PHYSICAL_TABLE';
    recognitionCoverage?: {
      status?: string;
      physicalRegionCount?: number;
      expectedRegionCount?: number;
      coveredRegionCount?: number;
      unresolvedRegionCount?: number;
      coverageRatio?: number;
      issues?: string[];
    };
    suggestionCount?: number;
    qualityIssueCount?: number;
    autoFixedCount?: number;
  };
  recognitionSummary?: TemplateImportRecognitionSummary;
  lastError?: string;
  createdAt: string;
  suggestionCount: number;
  pendingSuggestionCount: number;
  retryCount: number;
  recognitionRunId?: string;
  recognitionRunStatus?: string;
  generatedTemplateVersionId?: string;
  workspaceHash?: string;
  issues: TemplateImportIssue[];
  categoryId?: string;
  categoryName?: string;
  sourceSha256?: string;
  duplicateOverride: boolean;
  duplicateSourceJobId?: string;
}

export type RecognitionDecision = 'PENDING' | 'ACCEPTED' | 'REJECTED';

export interface RecognitionSuggestionPayload {
  fieldCode: string;
  standardFieldId?: string;
  standardFieldVersion?: number;
  standardFieldName?: string;
  fieldOrigin?: 'STANDARD' | 'TEMPLATE_LOCAL' | 'ORDER_LOCAL' | 'PENDING_STANDARD';
  standardSelectionStatus?: 'MATCHED' | 'CONFIRMED' | 'CUSTOM' | 'REQUESTED';
  uiType?: 'TEXT' | 'SIGNATURE';
  fieldId?: string;
  bindingId?: string;
  relationId?: string;
  fieldName: string;
  dataPath: string;
  valueType:
    | 'string'
    | 'number'
    | 'integer'
    | 'boolean'
    | 'date'
    | 'datetime'
    | 'time'
    | 'duration'
    | 'array';
  required: boolean;
  role: 'FIELD' | 'REPEAT_REGION' | 'CONDITIONAL';
  locatorType?:
    | 'CELL_RANGE'
    | 'TABLE_REGION'
    | 'MATRIX_REGION'
    | 'INLINE_TEXT'
    | 'MERGED_VALUE'
    | 'VERTICAL_LABEL_VALUE'
    | 'HORIZONTAL_LABEL_VALUE'
    | 'TEXT_QUOTE';
  locator: Record<string, unknown>;
  kind?: 'SCALAR' | 'ROW_TABLE' | 'COLUMN_TABLE' | 'MATRIX' | 'FREE_TEXT';
  groupName?: string;
  unit?: string;
  interpretation?: string;
  editability?: Editability;
  valueSource?: ValueSource;
  condition?: string;
  dictionaryVersion?: number;
  standardMatchStatus?: 'MATCHED' | 'UNMATCHED' | 'CONFIRMED';
  standardRequired?: boolean;
  requiresStandardConfirmation?: boolean;
  candidateOnly?: boolean;
  physicalStructureOnly?: boolean;
  reviewRequired?: boolean;
  structureConflict?: boolean;
  canonicalStatus?: 'PROVISIONAL' | 'CONFIRMED';
  resolutionGroupId?: string;
  resolutionAlternativeId?: string;
  resolutionStatus?: 'PENDING' | 'AUTO_RESOLVED';
  resolutionReason?: string;
  structureResolution?: Record<string, unknown>;
  alternativeRole?: 'PHYSICAL' | 'MODEL' | 'HUMAN';
  structureStatus?: 'PROVISIONAL' | 'MODEL_ASSESSED' | 'CONFLICT' | 'UNRESOLVED' | 'CONFIRMED';
  runtimeInputOnly?: boolean;
  suppressed?: boolean;
  templateStatus?: 'RUNTIME_INPUT' | 'CONFIRMED';
  publishable?: boolean;
  pendingReason?: string;
  reasonCode?: string;
  recognitionOrigin?: string;
  activeGenerationId?: string;
  labelPath?: string;
  autoAccept?: boolean;
  nameSource?: 'MODEL' | 'ROW_ATTRIBUTE_FALLBACK' | 'PHYSICAL_HEADER_FALLBACK'
    | 'GENERATED_PLACEHOLDER' | 'RUNTIME_SLOT';
  semanticFallback?: boolean;
  protocolRecovery?: string;
  blockType?: string;
  blockName?: string;
  mappingKind?: MappingKind;
  parentSuggestionId?: string;
  parentRelationId?: string;
  parentFieldId?: string;
  parentBindingId?: string;
  suggestionLevel?: 'ROOT' | 'CHILD' | 'SCALAR';
  repeatAxis?: RepeatAxis;
  recordHeight?: number;
  recordWidth?: number;
  recordStride?: number;
  terminationRule?: Record<string, unknown>;
  semanticConflict?: boolean;
  conflictCode?: string;
  conflictMessage?: string;
  semanticAlternatives?: Array<{ fieldCode: string; name: string }>;
  blockId?: string;
  regionId?: string;
  parentBlockId?: string;
  columns?: Array<{
    code: string;
    bindingId?: string;
    relationId?: string;
    fieldId?: string;
    fieldCode?: string;
    dataPath?: string;
    name: string;
    valueType?: string;
    unit?: string;
    labelRange?: string;
    valueRange?: string;
    editability?: Editability;
    valueSource?: ValueSource;
    condition?: string;
    required?: boolean;
    dataStartRow?: number;
    semanticConflict?: boolean;
    conflictCode?: string;
    conflictMessage?: string;
    semanticAlternatives?: Array<{ fieldCode: string; name: string }>;
    dictionaryVersion?: number;
    standardMatchStatus?: 'MATCHED' | 'UNMATCHED' | 'CONFIRMED';
    requiresStandardConfirmation?: boolean;
    standardFieldId?: string;
    standardFieldVersion?: number;
    standardFieldName?: string;
    fieldOrigin?: 'STANDARD' | 'TEMPLATE_LOCAL' | 'ORDER_LOCAL' | 'PENDING_STANDARD';
    standardSelectionStatus?: 'MATCHED' | 'CONFIRMED' | 'CUSTOM' | 'REQUESTED';
    uiType?: 'TEXT' | 'SIGNATURE';
    columnOffset?: number;
    columnSpan?: number;
    physicalColumnRanges?: string[];
    mergeRange?: string;
    valueMode?: string;
    nameSource?: 'MODEL' | 'ROW_ATTRIBUTE_FALLBACK' | 'PHYSICAL_HEADER_FALLBACK'
      | 'GENERATED_PLACEHOLDER' | 'RUNTIME_SLOT';
    semanticFallback?: boolean;
    reviewRequired?: boolean;
  }>;
  tableModel?: Record<string, unknown>;
  matrixModel?: MatrixModel;
  recordProjection?: MatrixRecordProjection;
  columnSlots?: MatrixColumnSlot[];
  longTableModel?: LongTableModel;
  structureAlternatives?: Array<{
    alternativeId?: string;
    suggestionId?: string;
    source?: 'RULE' | 'MODEL' | 'PHYSICAL' | 'HUMAN';
    regions?: Array<{
      suggestionId: string;
      source?: 'RULE' | 'MODEL' | 'PHYSICAL' | 'HUMAN';
      kind?: string;
      range?: string;
      decision?: RecognitionDecision | 'REJECTED_BY_RESOLUTION';
    }>;
    // Legacy singleton fields remain readable while old runs are retained.
    kind?: string;
    range?: string;
    decision?: RecognitionDecision | 'REJECTED_BY_RESOLUTION';
  }>;
  structureAlternativeSets?: Array<Record<string, unknown>>;
  resolution?: Record<string, unknown>;
  hasIndependentChildren?: boolean;
  reason?: string;
}

export type {
  LongTableModel,
  LongTableRecord,
  MatrixModel,
} from '@/features/template-workspace/types';

export interface RecognitionSuggestion {
  id: string;
  importJobId: string;
  recognitionRunId?: string;
  source: 'RULE' | 'MODEL' | 'PHYSICAL' | 'HUMAN';
  suggestionType: string;
  payload: RecognitionSuggestionPayload;
  confidence: number;
  evidence: Array<Record<string, unknown>>;
  decision: RecognitionDecision;
  provider?: string;
  model?: string;
  promptVersion?: string;
  filterReasonCode?: string;
  filterDetail?: string;
  createdAt: string;
}

export type RecognitionReviewStatus = 'PENDING' | 'CONFIRMED' | 'CONFLICT' | 'IGNORED';
export type RecognitionConfidenceLevel = 'HIGH' | 'MEDIUM' | 'LOW';
export type RecognitionAction = 'CONFIRM' | 'IGNORE' | 'RESTORE';

export interface RecognitionActionInput {
  recognitionItemId: string;
  action: RecognitionAction;
  selectedSuggestionId?: string;
  selectedAlternativeId?: string;
}

export type QualityAction = 'APPLY' | 'IGNORE' | 'ROLLBACK';

export interface QualityActionInput {
  issueId: string;
  action: QualityAction;
}

export interface TemplateQualityIssue {
  id: string;
  issueType: string;
  severity: 'INFO' | 'WARNING' | 'BLOCKER';
  confidence: number;
  sheetId: string;
  sheetName: string;
  address: string;
  title: string;
  description: string;
  businessImpact: string;
  autoFixable: boolean;
  status: 'DETECTED' | 'AUTO_APPLIED' | 'CONFIRMED' | 'IGNORED' | 'ROLLED_BACK' | 'FAILED';
  suggestedPatch: Record<string, unknown>;
  inversePatch: Record<string, unknown>;
  evidence: Array<Record<string, unknown>>;
}

export interface RecognitionReviewItem {
  id: string;
  suggestionIds: string[];
  fieldId?: string;
  parentSuggestionId?: string;
  parentFieldId?: string;
  child?: boolean;
  fieldName: string;
  description: string;
  groupName: string;
  kind: 'SCALAR' | 'FORM_REGION' | 'ROW_TABLE' | 'COLUMN_TABLE' | 'MATRIX' | 'TABLE_REGION' | 'FREE_TEXT';
  valueType: string;
  sheetId: string;
  sheetName: string;
  labelAddress: string;
  address: string;
  confidence: number;
  confidenceLevel: RecognitionConfidenceLevel;
  source?: 'RULE' | 'MODEL' | 'PHYSICAL' | 'HUMAN';
  reasonCode?: string;
  reasonDetail?: string;
  status: RecognitionReviewStatus;
  conflictReason?: string;
  payload: RecognitionSuggestionPayload;
}

export interface RecognitionRegionAlternative {
  alternativeId: string;
  source?: 'RULE' | 'MODEL' | 'PHYSICAL' | 'HUMAN';
  regions: Array<{
    suggestionId: string;
    source?: 'RULE' | 'MODEL' | 'PHYSICAL' | 'HUMAN';
    kind?: string;
    range?: string;
    decision?: RecognitionDecision | 'REJECTED_BY_RESOLUTION';
    recordAxis?: string;
    headerRange?: string;
    dataRange?: string;
    rowHeaderRange?: string;
    columnHeaderRange?: string;
    crossDataRange?: string;
  }>;
  recordAxis?: string;
  headerRange?: string;
  dataRange?: string;
  rowHeaderRange?: string;
  columnHeaderRange?: string;
  crossDataRange?: string;
}

export interface RecognitionRegionNode {
  regionId: string;
  blockId?: string;
  kind: string;
  sheetId?: string;
  sheetName?: string;
  range?: string;
  fieldName: string;
  status: string;
  canonicalStatus?: string;
  structureStatus?: string;
  resolutionGroupId?: string;
  reviewRequired?: boolean;
  alternatives: RecognitionRegionAlternative[];
  fields: Array<RecognitionReviewItem & { attributes?: Record<string, unknown> }>;
  runtimeSlots: MatrixColumnSlot[];
  recordSlots?: Array<{
    slotId: string;
    recordKey?: string;
    order?: number;
    range: string;
    identityAddress?: string;
    templateStatus?: string;
    role?: string;
  }>;
  staticContents?: Array<{
    address?: string;
    text?: string;
    role?: string;
  }>;
  structures?: Record<string, unknown>;
  auditSuggestions: RecognitionReviewItem[];
}

export interface RecognitionReviewStatistics {
  regionCount: number;
  structureAlternativeCount: number;
  structureConflictGroups: number;
  fieldCount: number;
  pendingFieldCount: number;
  auditSuggestionCount: number;
  runtimeSlotCount: number;
}

export interface RecognitionReview {
  recognitionRunId?: string;
  runStatus: string;
  summary: {
    total: number;
    confirmed: number;
    pending: number;
    lowConfidence: number;
    conflict: number;
    ignored: number;
    scalar: number;
    rowTable: number;
    matrix: number;
    qualityIssueCount: number;
    autoFixedCount: number;
    blockingIssueCount: number;
  };
  groups: string[];
  items: RecognitionReviewItem[];
  qualityIssues: TemplateQualityIssue[];
  semanticModel?: {
    recognitionProtocolVersion?: number;
    businessBlocks?: BusinessBlock[];
    semanticAnnotations?: Array<Record<string, unknown>>;
    staticRegions?: StaticRegion[];
    diagnostics?: Array<{
      stage: string;
      reasonCode: string;
      message: string;
      detail?: Record<string, unknown>;
    }>;
  };
  staticRegions?: StaticRegion[];
  diagnostics?: Array<{
    stage: string;
    reasonCode: string;
    message: string;
    detail?: Record<string, unknown>;
  }>;
  recognitionStatus?: string;
  regions?: RecognitionRegionNode[];
  statistics?: RecognitionReviewStatistics;
  recognitionCoverage?: {
    status?: string;
    physicalRegionCount?: number;
    expectedRegionCount?: number;
    coveredRegionCount?: number;
    unresolvedRegionCount?: number;
    coverageRatio?: number;
    issues?: string[];
    regions?: Array<Record<string, unknown>>;
  };
}

export interface StandardFieldOption {
  id: string;
  fieldCode: string;
  version: number;
  displayName: string;
  valueType: string;
  uiType?: 'TEXT' | 'SIGNATURE';
  groupCode?: string;
  groupName?: string;
  defaultUnit?: string;
  description?: string;
}

export interface StandardFieldRequest {
  id: string;
  templateVersionId?: string;
  fieldId?: string;
  displayName: string;
  valueType: string;
  uiType?: 'TEXT' | 'SIGNATURE';
  groupCode?: string;
  description?: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED';
  proposedFieldCode?: string;
  reviewComment?: string;
  createdAt: string;
  reviewedAt?: string;
}

export const templateApi = {
  async list(params?: {
    keyword?: string; categoryId?: string; uncategorized?: boolean; format?: TemplateFormat; status?: TemplateStatus;
    createdBy?: string; updatedFrom?: string; updatedTo?: string;
    sortBy?: 'UPDATED_AT' | 'CREATED_AT' | 'NAME'; sortDirection?: 'ASC' | 'DESC';
    page?: number; size?: number;
  }) {
    const response = await httpClient.get<ApiResponse<PageResponse<TemplateListItem>>>(
      '/api/v2/templates',
      { params },
    );
    return response.data.data;
  },

  async filterOptions() {
    const response = await httpClient.get<ApiResponse<Array<{ id: string; displayName: string }>>>(
      '/api/v2/templates/filter-options',
    );
    return response.data.data;
  },

  async listFacets(params?: {
    keyword?: string; format?: TemplateFormat; status?: TemplateStatus; createdBy?: string;
    updatedFrom?: string; updatedTo?: string;
  }) {
    const response = await httpClient.get<ApiResponse<TemplateFacetSummary>>(
      '/api/v2/templates/facets',
      { params },
    );
    return response.data.data;
  },

  async renameTemplate(templateId: string, name: string) {
    await httpClient.patch(`/api/v2/templates/${templateId}`, { name });
  },

  async copyTemplate(versionId: string, input: { name?: string; categoryId?: string }) {
    const response = await httpClient.post<ApiResponse<TemplateWorkspace>>(
      `/api/v2/template-versions/${versionId}/copies`, input,
    );
    return response.data.data;
  },

  async rollback(versionId: string) {
    const response = await httpClient.post<ApiResponse<TemplateWorkspace>>(
      `/api/v2/template-versions/${versionId}/rollback`,
    );
    return response.data.data;
  },

  async batchActions(input: {
    action: 'COPY' | 'MOVE' | 'DELETE_DRAFT' | 'RETIRE'; categoryId?: string;
    items: Array<{ templateId: string; versionId?: string; name?: string }>;
  }) {
    const response = await httpClient.post<ApiResponse<Array<{
      templateId: string; versionId?: string; success: boolean; reason?: string;
    }>>>('/api/v2/templates/batch-actions', input);
    return response.data.data;
  },

  async exportCsv(params: Record<string, string | number | boolean | string[] | undefined>) {
    const response = await httpClient.get<Blob>('/api/v2/templates/export.csv', {
      params, responseType: 'blob',
    });
    const url = URL.createObjectURL(response.data);
    const anchor = document.createElement('a');
    anchor.href = url; anchor.download = 'templates.csv'; anchor.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 0);
  },

  async create(input: CreateTemplateInput) {
    const response = await httpClient.post<ApiResponse<TemplateWorkspace>>(
      '/api/v2/templates',
      input,
    );
    return response.data.data;
  },

  async getEditModel(versionId: string) {
    const response = await httpClient.get<ApiResponse<TemplateWorkspace>>(
      `/api/v2/template-versions/${versionId}/edit-model`,
    );
    return response.data.data;
  },

  async getRecognitionReview(versionId: string) {
    const response = await httpClient.get<ApiResponse<RecognitionReview>>(
      `/api/v2/template-versions/${versionId}/recognition-review`,
    );
    return response.data.data;
  },

  async searchStandardFields(params: { keyword?: string; valueType?: string } = {}) {
    const response = await httpClient.get<ApiResponse<StandardFieldOption[]>>(
      '/api/v2/standard-fields',
      { params },
    );
    return response.data.data;
  },

  async requestStandardField(input: {
    templateVersionId: string;
    fieldId?: string;
    displayName: string;
    valueType: string;
    uiType?: 'TEXT' | 'SIGNATURE';
    groupCode?: string;
    description?: string;
  }) {
    const response = await httpClient.post<ApiResponse<StandardFieldRequest>>(
      '/api/v2/standard-field-requests',
      input,
    );
    return response.data.data;
  },

  async restartRecognition(
    versionId: string,
    request: {
      scope: 'WORKBOOK' | 'REGION';
      sheetId?: string;
      address?: string;
      snapshotFragment?: Record<string, unknown>;
    } = { scope: 'WORKBOOK' },
  ) {
    const response = await httpClient.post<ApiResponse<TemplateImportJob>>(
      `/api/v2/template-versions/${versionId}/recognition-runs`,
      request,
    );
    return response.data.data;
  },

  async listVersions(templateId: string) {
    const response = await httpClient.get<ApiResponse<TemplateVersionHistoryItem[]>>(
      `/api/v2/templates/${templateId}/versions`,
    );
    return response.data.data;
  },

  async saveDraft(versionId: string, input: SaveTemplateDraftInput) {
    const response = await httpClient.put<ApiResponse<SaveTemplateDraftResult>>(
      `/api/v2/template-versions/${versionId}/draft`,
      input,
      // Saving may synchronously run one REGION_FIELDS batch after a user
      // confirms a structure. Keep the normal API timeout short, but allow
      // this operation to wait for the model and its bounded retries.
      { timeout: 180_000 },
    );
    return response.data.data;
  },

  async publish(versionId: string) {
    await httpClient.post(`/api/v2/template-versions/${versionId}/publish`);
  },

  async downloadWordDocument(versionId: string) {
    const response = await httpClient.get<Blob>(
      `/api/v2/template-versions/${versionId}/word-document`,
      { responseType: 'blob' },
    );
    const url = URL.createObjectURL(response.data);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = 'word-template.docx';
    anchor.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 0);
  },

  async checkExport(versionId: string, format: TemplateFormat, state: 'DRAFT' | 'PUBLISHED' = 'DRAFT') {
    const response = await httpClient.get<ApiResponse<{ canDownload: boolean; warnings: Array<{ code: string; bindingId?: string; dataPath?: string; message: string }> }>>(
      `/api/v2/template-versions/${versionId}/export/check`, { params: { format, state } },
    );
    return response.data.data;
  },

  async exportOffice(versionId: string, format: TemplateFormat, state: 'DRAFT' | 'PUBLISHED' = 'DRAFT') {
    const response = await httpClient.get<Blob>(
      `/api/v2/template-versions/${versionId}/export`, { params: { format, state }, responseType: 'blob' },
    );
    const url = URL.createObjectURL(response.data);
    const anchor = document.createElement('a'); anchor.href = url;
    anchor.download = format === 'XLSX' ? `template-${state.toLowerCase()}.xlsx` : `template-${state.toLowerCase()}.docx`;
    anchor.click(); window.setTimeout(() => URL.revokeObjectURL(url), 0);
  },

  async downloadWordPreview(versionId: string) {
    const response = await httpClient.get<Blob>(
      `/api/v2/template-versions/${versionId}/word-preview`,
      { responseType: 'blob' },
    );
    return response.data;
  },

  async createRevision(versionId: string) {
    const response = await httpClient.post<ApiResponse<TemplateWorkspace>>(
      `/api/v2/template-versions/${versionId}/revisions`,
    );
    return response.data.data;
  },

  async deleteDraft(versionId: string) {
    await httpClient.delete(`/api/v2/template-versions/${versionId}`);
  },

  async retire(templateId: string) {
    await httpClient.post(`/api/v2/templates/${templateId}/retire`);
  },

  async stageSnapshot(snapshot: Record<string, unknown>, format: TemplateFormat) {
    const validObject = Boolean(snapshot) && typeof snapshot === 'object' && !Array.isArray(snapshot);
    const validWorkbook = format !== 'XLSX'
      || (validObject && snapshot.sheets && typeof snapshot.sheets === 'object'
        && !Array.isArray(snapshot.sheets) && Object.keys(snapshot.sheets).length > 0);
    const validDocument = format !== 'DOCX'
      || (validObject && snapshot.body && typeof snapshot.body === 'object'
        && !Array.isArray(snapshot.body)
        && typeof (snapshot.body as Record<string, unknown>).dataStream === 'string');
    if (!validObject || !validWorkbook || !validDocument) {
      throw new Error('编辑器快照无效，已取消保存，请刷新页面后重试');
    }
    const body = new FormData();
    body.append(
      'file',
      new Blob([JSON.stringify(snapshot)], { type: 'application/json' }),
      `${format.toLowerCase()}-univer-snapshot.json`,
    );
    const response = await httpClient.post<
      ApiResponse<{ fileId: string; sha256: string; status: 'STAGED' }>
    >('/api/v2/files/staged?kind=SNAPSHOT', body);
    return response.data.data;
  },

  async stageOfficeFile(file: File) {
    const body = new FormData();
    body.append('file', file);
    const response = await httpClient.post<
      ApiResponse<{ fileId: string; sha256: string; status: 'STAGED' }>
    >('/api/v2/files/staged?kind=TEMPLATE_SOURCE', body);
    return response.data.data;
  },

  async createImport(fileId: string, format: TemplateFormat, options?: {
    categoryId?: string; duplicateOverride?: boolean; operationSource?: string;
  }) {
    const response = await httpClient.post<ApiResponse<TemplateImportJob>>(
      '/api/v2/template-imports',
      { fileId, format, ...options },
    );
    return response.data.data;
  },

  async retryImport(
    importJobId: string,
    source: 'ORIGINAL_FILE' | 'CURRENT_DRAFT_SNAPSHOT',
    baseWorkspaceHash?: string,
  ) {
    const response = await httpClient.post<ApiResponse<TemplateImportJob>>(
      `/api/v2/template-imports/${importJobId}/retry`,
      {
        source,
        ...(baseWorkspaceHash ? { baseWorkspaceHash } : {}),
      },
    );
    return response.data.data;
  },

  async listImports() {
    const response = await httpClient.get<ApiResponse<TemplateImportJob[]>>(
      '/api/v2/template-imports',
    );
    return response.data.data;
  },

  async getImport(importJobId: string) {
    const response = await httpClient.get<ApiResponse<TemplateImportJob>>(
      `/api/v2/template-imports/${importJobId}`,
    );
    return response.data.data;
  },

  async getImportRenderContext(importJobId: string) {
    const response = await httpClient.get<
      ApiResponse<{
        importJobId: string;
        sourceFileId: string;
        ready: boolean;
        visualStatus: string;
        visualRender?: {
          status: string;
          sourceUrl?: string;
          objectKey?: string;
          size?: number;
          width?: number;
          height?: number;
          detail?: string;
        };
        snapshot: Record<string, unknown>;
      }>
    >(`/api/v2/template-imports/${importJobId}/render-context`);
    return response.data.data;
  },

  async getImportDocumentStructure(importJobId: string) {
    const response = await httpClient.get<ApiResponse<DocumentStructure>>(
      '/api/v2/template-imports/' + importJobId + '/document-structure',
    );
    return response.data.data;
  },

  async listRecognitionSuggestions(importJobId: string) {
    const response = await httpClient.get<ApiResponse<RecognitionSuggestion[]>>(
      `/api/v2/template-imports/${importJobId}/suggestions`,
    );
    return response.data.data;
  },


  async deleteImport(importJobId: string) {
    await httpClient.delete(`/api/v2/template-imports/${importJobId}`);
  },

  async listCategories() {
    const response = await httpClient.get<ApiResponse<TemplateCategory[]>>('/api/v2/template-categories');
    return response.data.data;
  },

  async createCategory(input: { name: string; description?: string | null }) {
    const response = await httpClient.post<ApiResponse<TemplateCategory>>('/api/v2/template-categories', input);
    return response.data.data;
  },

  async renameCategory(categoryId: string, input: { name: string; description?: string | null }) {
    const response = await httpClient.put<ApiResponse<TemplateCategory>>(`/api/v2/template-categories/${categoryId}`, input);
    return response.data.data;
  },

  async deleteCategory(categoryId: string, replacementCategoryId?: string) {
    await httpClient.delete(`/api/v2/template-categories/${categoryId}`, {
      params: replacementCategoryId ? { replacementCategoryId } : undefined,
    });
  },

  async assignTemplateCategory(templateId: string, categoryId?: string) {
    await httpClient.put(`/api/v2/templates/${templateId}/category`, { categoryId: categoryId || null });
  },

  async decideRecognitionSuggestion(
    importJobId: string,
    suggestionId: string,
    decision: Exclude<RecognitionDecision, 'PENDING'>,
  ) {
    const response = await httpClient.post<ApiResponse<RecognitionSuggestion>>(
      `/api/v2/template-imports/${importJobId}/suggestions/${suggestionId}/decision`,
      { decision },
    );
    return response.data.data;
  },

  async confirmAllRecognitionSuggestions(importJobId: string) {
    const response = await httpClient.post<ApiResponse<RecognitionSuggestion[]>>(
      `/api/v2/template-imports/${importJobId}/suggestions/confirm-all`,
    );
    return response.data.data;
  },

  async downloadSnapshot(fileId: string) {
    const response = await httpClient.get<Record<string, unknown>>(
      `/api/v2/files/${fileId}/content`,
    );
    return response.data;
  },
};
