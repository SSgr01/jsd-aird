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
  snapshotFileId: string;
  snapshotHash: string;
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
}

export interface SaveTemplateDraftResult {
  lockVersion: number;
  workspaceHash: string;
  reconciliationRequired: boolean;
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
    modelStatus?: 'COMPLETED' | 'FAILED' | 'PARTIAL' | 'NOT_CONFIGURED' | 'NOT_APPLICABLE';
    suggestionCount?: number;
    qualityIssueCount?: number;
    autoFixedCount?: number;
  };
  lastError?: string;
  createdAt: string;
  suggestionCount: number;
  pendingSuggestionCount: number;
  issues: TemplateImportIssue[];
}

export type RecognitionDecision = 'PENDING' | 'ACCEPTED' | 'REJECTED';

export interface RecognitionSuggestionPayload {
  fieldCode: string;
  fieldId?: string;
  bindingId?: string;
  relationId?: string;
  fieldName: string;
  dataPath: string;
  valueType: 'string' | 'number' | 'integer' | 'boolean' | 'date' | 'datetime' | 'time' | 'duration' | 'array';
  required: boolean;
  role: 'FIELD' | 'REPEAT_REGION' | 'CONDITIONAL';
  locatorType: 'CELL_RANGE' | 'TABLE_REGION' | 'MATRIX_REGION' | 'TEXT_QUOTE';
  locator: Record<string, unknown>;
  kind?: 'SCALAR' | 'ROW_TABLE' | 'MATRIX';
  groupName?: string;
  unit?: string;
  interpretation?: string;
  editability?: Editability;
  valueSource?: ValueSource;
  condition?: string;
  blockId?: string;
  parentBlockId?: string;
  columns?: Array<{
    code: string;
    name: string;
    valueType?: string;
    unit?: string;
    labelRange?: string;
    valueRange?: string;
    editability?: Editability;
    valueSource?: ValueSource;
    condition?: string;
  }>;
  tableModel?: Record<string, unknown>;
  matrixModel?: Record<string, unknown>;
  reason?: string;
}

export interface RecognitionSuggestion {
  id: string;
  importJobId: string;
  source: 'RULE' | 'MODEL' | 'HUMAN';
  suggestionType: string;
  payload: RecognitionSuggestionPayload;
  confidence: number;
  evidence: Array<Record<string, unknown>>;
  decision: RecognitionDecision;
  provider?: string;
  model?: string;
  promptVersion?: string;
  createdAt: string;
}

export type RecognitionReviewStatus = 'PENDING' | 'CONFIRMED' | 'CONFLICT' | 'IGNORED';
export type RecognitionConfidenceLevel = 'HIGH' | 'MEDIUM' | 'LOW';
export type RecognitionAction = 'CONFIRM' | 'IGNORE' | 'RESTORE';

export interface RecognitionActionInput {
  recognitionItemId: string;
  action: RecognitionAction;
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
  fieldName: string;
  description: string;
  groupName: string;
  kind: 'SCALAR' | 'ROW_TABLE' | 'MATRIX';
  valueType: string;
  sheetId: string;
  sheetName: string;
  labelAddress: string;
  address: string;
  confidence: number;
  confidenceLevel: RecognitionConfidenceLevel;
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
  };
}

export const templateApi = {
  async list(params?: {
    keyword?: string;
    format?: TemplateFormat;
    status?: TemplateStatus;
  }) {
    const response = await httpClient.get<ApiResponse<PageResponse<TemplateListItem>>>(
      '/api/v2/templates',
      { params },
    );
    return response.data.data;
  },

  async create(input: CreateTemplateInput) {
    const response = await httpClient.post<ApiResponse<TemplateWorkspace>>('/api/v2/templates', input);
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

  async listRecognitionSuggestions(importJobId: string) {
    const response = await httpClient.get<ApiResponse<RecognitionSuggestion[]>>(
      `/api/v2/template-imports/${importJobId}/suggestions`,
    );
    return response.data.data;
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
