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
} from '@/features/template-workspace/types';
import { httpClient } from '@/services/http/client';

export interface CreateTemplateInput {
  name: string;
  purpose?: string;
  category?: string;
  format: TemplateFormat;
  importJobId?: string;
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
    modelStatus?: 'COMPLETED' | 'FAILED' | 'PARTIAL' | 'TRUNCATED' | 'NOT_CONFIGURED' | 'NOT_APPLICABLE';
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
  lastError?: string;
  createdAt: string;
  suggestionCount: number;
  pendingSuggestionCount: number;
  recognitionRunId?: string;
  recognitionRunStatus?: string;
  issues: TemplateImportIssue[];
}

export type RecognitionDecision = 'PENDING' | 'ACCEPTED' | 'REJECTED';

export interface RecognitionSuggestionPayload {
  fieldCode: string;
  standardFieldId?: string;
  standardFieldVersion?: number;
  standardFieldName?: string;
  fieldOrigin?: 'STANDARD' | 'TEMPLATE_LOCAL' | 'PENDING_STANDARD';
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
  locatorType:
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
  requiresStandardConfirmation?: boolean;
  candidateOnly?: boolean;
  physicalStructureOnly?: boolean;
  reviewRequired?: boolean;
  structureConflict?: boolean;
  canonicalStatus?: 'PROVISIONAL' | 'CONFIRMED';
  resolutionGroupId?: string;
  alternativeRole?: 'PHYSICAL' | 'MODEL' | 'HUMAN';
  structureStatus?: 'PROVISIONAL' | 'MODEL_ASSESSED' | 'CONFLICT' | 'UNRESOLVED' | 'CONFIRMED';
  runtimeInputOnly?: boolean;
  templateStatus?: 'RUNTIME_INPUT' | 'CONFIRMED';
  publishable?: boolean;
  pendingReason?: string;
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
    fieldOrigin?: 'STANDARD' | 'TEMPLATE_LOCAL' | 'PENDING_STANDARD';
    standardSelectionStatus?: 'MATCHED' | 'CONFIRMED' | 'CUSTOM' | 'REQUESTED';
    uiType?: 'TEXT' | 'SIGNATURE';
    columnOffset?: number;
    columnSpan?: number;
    physicalColumnRanges?: string[];
    mergeRange?: string;
    valueMode?: string;
  }>;
  tableModel?: Record<string, unknown>;
  matrixModel?: MatrixModel;
  recordProjection?: MatrixRecordProjection;
  columnSlots?: MatrixColumnSlot[];
  longTableModel?: LongTableModel;
  structureAlternatives?: Array<{
    suggestionId: string;
    source?: 'RULE' | 'MODEL' | 'PHYSICAL' | 'HUMAN';
    kind?: string;
    range?: string;
    decision?: RecognitionDecision | 'REJECTED_BY_RESOLUTION';
  }>;
  hasIndependentChildren?: boolean;
  reason?: string;
}

export type { LongTableModel, LongTableRecord, MatrixModel } from '@/features/template-workspace/types';

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

export interface RecognitionCall {
  id: string;
  recognitionRunId?: string;
  regionId: string;
  attempt: number;
  provider: string;
  model: string;
  promptVersion: string;
  status: string;
  httpStatus?: number;
  startedAt: string;
  finishedAt: string;
  durationMs: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  requestPayload: Record<string, unknown>;
  responsePayload: Record<string, unknown>;
  errorType?: string;
  errorMessage?: string;
  finishReason?: string;
  outcomeCode?: string;
  responseTruncated?: boolean;
  phase: string;
  parentCallId?: string;
  payloadAvailable: boolean;
}

export type RecognitionReviewStatus = 'PENDING' | 'CONFIRMED' | 'CONFLICT' | 'IGNORED';
export type RecognitionConfidenceLevel = 'HIGH' | 'MEDIUM' | 'LOW';
export type RecognitionAction = 'CONFIRM' | 'IGNORE' | 'RESTORE';

export interface RecognitionActionInput {
  recognitionItemId: string;
  action: RecognitionAction;
  selectedSuggestionId?: string;
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
  kind: 'SCALAR' | 'ROW_TABLE' | 'COLUMN_TABLE' | 'MATRIX' | 'FREE_TEXT';
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
  async list(params?: { keyword?: string; format?: TemplateFormat; status?: TemplateStatus }) {
    const response = await httpClient.get<ApiResponse<PageResponse<TemplateListItem>>>(
      '/api/v2/templates',
      { params },
    );
    return response.data.data;
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

  async createImport(fileId: string, format: TemplateFormat) {
    const response = await httpClient.post<ApiResponse<TemplateImportJob>>(
      '/api/v2/template-imports',
      { fileId, format },
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

  async listRecognitionCalls(importJobId: string) {
    const response = await httpClient.get<ApiResponse<RecognitionCall[]>>(
      `/api/v2/template-imports/${importJobId}/recognition-calls`,
    );
    return response.data.data;
  },

  async deleteImport(importJobId: string) {
    await httpClient.delete(`/api/v2/template-imports/${importJobId}`);
  },

  async deleteCategory(category: string) {
    await httpClient.delete(`/api/v2/template-categories/${encodeURIComponent(category)}`);
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
