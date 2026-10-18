import type { ApiResponse, PageResponse } from '@/types/api';
import { appEnv } from '@/app/config/env';
import { httpClient } from '@/services/http/client';
import { notifyAuthRequired } from '@/services/http/auth-events';
import { downloadBlob } from '@/services/files/file-api';
import { generateUUID } from '@/utils/uuid';

export interface SpectrumFieldDefinition {
  key: string;
  label: string;
}
export interface SpectrumCategory {
  id: string;
  code: string;
  name: string;
  description?: string;
  analysisHint?: string;
  fields: SpectrumFieldDefinition[];
  sortOrder: number;
  systemCategory: boolean;
  chartCount: number;
  allowedActions?: string[];
}
export interface SpectrumChart {
  id: string;
  categoryId: string;
  categoryCode: string;
  categoryName: string;
  fileObjectId: string;
  title: string;
  originalName: string;
  contentType: string;
  size: number;
  sha256: string;
  sampleName?: string;
  batchNo?: string;
  testConditions?: string;
  metadata: Record<string, unknown>;
  pageCount: number;
  status: string;
  createdAt: string;
  updatedAt: string;
  allowedActions?: string[];
}
export interface SpectrumUploadAttempt {
  requestId: string;
  categoryId?: string;
  chartId?: string;
  fileObjectId?: string;
  originalName: string;
  size: number;
  status: 'SUCCEEDED' | 'FAILED' | 'DUPLICATE' | 'CANCELLED';
  errorCode?: string;
  errorMessage?: string;
  uploadContext: Record<string, unknown>;
  parentRequestId?: string;
  createdAt: string;
}
export interface SpectrumPage {
  pageNo: number;
  pageCount: number;
}
export interface SpectrumCitation {
  chartId: string;
  category: string;
  page: number;
  title: string;
  region?: string;
}
export type SpectrumAnswerIntent =
  | 'ATTRIBUTION'
  | 'COMPARISON'
  | 'FEATURE_INTERPRETATION'
  | 'VALIDATION'
  | 'OVERVIEW';
export type SpectrumAnalysisIntent =
  | 'AUTO'
  | 'OVERVIEW'
  | 'PEAK_INTERPRETATION'
  | 'SAMPLE_COMPARISON'
  | 'CONCENTRATION_CHECK'
  | 'PURITY_CHECK'
  | 'IDENTIFICATION'
  | 'REFERENCE_MAPPING'
  | 'METHOD_VALIDATION'
  | 'ANOMALY_DIAGNOSIS';
export interface SpectrumAnswerV3 {
  summary?: string;
  confirmed: string[];
  metadata?: string[];
  notConfirmed: string[];
  reasonableInference: string[];
  missingEvidence: string[];
  nextStep: string[];
}
export interface SpectrumPublicAssessmentItem {
  label: string;
  value: string;
  source?: string;
}
export interface SpectrumPublicAssessment {
  title: string;
  items: SpectrumPublicAssessmentItem[];
}
export interface SpectrumDetailsV3 {
  confirmed: string[];
  metadata?: string[];
  reasonableInference: string[];
  limitations: string[];
}
export interface SpectrumResultPresentation {
  version: number;
  primaryIntent: SpectrumAnswerIntent;
  conclusion: string;
  keyFindings: string[];
  validationSteps: string[];
  detailSectionKeys: string[];
}
export interface SpectrumResult {
  schemaVersion?: number;
  analysisStatus?: 'SUCCEEDED' | 'PARTIAL' | 'FAILED';
  errorMessage?: string;
  answer?: SpectrumAnswerV3;
  assessment?: SpectrumPublicAssessment;
  details?: SpectrumDetailsV3;
  answerMarkdown?: string;
  observations?: unknown[];
  comparisons?: unknown[];
  peakMappings?: unknown[];
  candidateInterpretations?: unknown[];
  unmatchedFeatures?: unknown[];
  overlapCandidates?: unknown[];
  conflicts?: unknown[];
  suggestedValidationExperiments?: unknown[];
  evidence?: unknown[];
  confidence?: unknown;
  uncertainty?: unknown;
  testConditionLimitations?: unknown[];
  aiReviewFocus?: unknown[];
  evidenceSufficiency?: string;
  referenceAvailability?: {
    hasSinglePeakReferences?: boolean;
    referenceChartIds?: string[];
    statement?: string;
  };
  conclusionBoundary?: string;
  presentation?: SpectrumResultPresentation;
  [key: string]: unknown;
}
export interface SpectrumMessage {
  id: string;
  analysisRunId?: string;
  role: 'USER' | 'ASSISTANT';
  content: string;
  citations: SpectrumCitation[];
  result: SpectrumResult;
  warnings: string[];
  createdAt: string;
}
export interface SpectrumSession {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
  messages: SpectrumMessage[];
  chartIds: string[];
  pageSelections: Record<string, number[]>;
}
export interface SpectrumMessageReplacement {
  replaceFromMessageId: string;
  expectedLastMessageId: string;
}
export interface SpectrumAnalysis {
  id: string;
  sessionId: string;
  mode: string;
  question: string;
  chartIds: string[];
  pageSelections: Record<string, number[]>;
  categories: string[];
  analysisIntent?: SpectrumAnalysisIntent;
  status: string;
  progress: number;
  currentStage?: string;
  result: SpectrumResult;
  warnings: string[];
  errorMessage?: string;
  createdAt: string;
  startedAt?: string;
  completedAt?: string;
}

const normalizePageSelections = (value: Record<string, number[]> | undefined) => value || {};

export const spectrumApi = {
  async categories() {
    const response =
      await httpClient.get<ApiResponse<SpectrumCategory[]>>('/api/v1/spc/categories');
    return response.data.data;
  },
  async createCategory(input: {
    code?: string;
    name: string;
    description?: string;
    analysisHint?: string;
    fields?: SpectrumFieldDefinition[];
  }) {
    const response = await httpClient.post<ApiResponse<SpectrumCategory>>(
      '/api/v1/spc/categories',
      input,
    );
    return response.data.data;
  },
  async updateCategory(
    id: string,
    input: {
      name: string;
      description?: string;
      analysisHint?: string;
      fields?: SpectrumFieldDefinition[];
    },
  ) {
    const response = await httpClient.put<ApiResponse<SpectrumCategory>>(
      `/api/v1/spc/categories/${id}`,
      input,
    );
    return response.data.data;
  },
  async assignChartCategory(chartId: string, categoryId: string) {
    const response = await httpClient.put<ApiResponse<SpectrumChart>>(
      `/api/v1/spc/charts/${chartId}/category`,
      { categoryId },
    );
    return response.data.data;
  },
  async deleteCategory(id: string) {
    await httpClient.delete(`/api/v1/spc/categories/${id}`);
  },
  async listCharts(
    params: {
      keyword?: string;
      categoryId?: string;
      status?: string;
      page?: number;
      size?: number;
    } = {},
  ) {
    const response = await httpClient.get<ApiResponse<PageResponse<SpectrumChart>>>(
      '/api/v1/spc/charts',
      { params },
    );
    return response.data.data;
  },
  async createChart(input: {
    requestId: string;
    fileId: string;
    title?: string;
    categoryId: string;
    sampleName?: string;
    batchNo?: string;
    testConditions?: string;
    metadata?: Record<string, unknown>;
    originalName: string;
    size: number;
    duplicateOverride?: boolean;
    uploadContext?: Record<string, unknown>;
    parentRequestId?: string;
  }) {
    const response = await httpClient.post<ApiResponse<SpectrumChart>>('/api/v1/spc/charts', input);
    return response.data.data;
  },
  async chart(id: string) {
    const response = await httpClient.get<ApiResponse<SpectrumChart>>(`/api/v1/spc/charts/${id}`);
    return response.data.data;
  },
  async updateChart(
    id: string,
    input: {
      title: string;
      categoryId?: string;
      sampleName?: string;
      batchNo?: string;
      testConditions?: string;
      metadata?: Record<string, unknown>;
    },
  ) {
    const response = await httpClient.put<ApiResponse<SpectrumChart>>(
      `/api/v1/spc/charts/${id}`,
      input,
    );
    return response.data.data;
  },
  async deleteChart(id: string) {
    await httpClient.delete(`/api/v1/spc/charts/${id}`);
  },
  async uploadAttempts(limit = 100) {
    const response = await httpClient.get<ApiResponse<SpectrumUploadAttempt[]>>(
      '/api/v1/spc/uploads/attempts', { params: { limit } },
    );
    return response.data.data;
  },
  async recordUploadAttempt(input: {
    requestId: string;
    categoryId?: string;
    fileObjectId?: string;
    originalName: string;
    size: number;
    status: 'FAILED' | 'DUPLICATE' | 'CANCELLED';
    errorCode?: string;
    errorMessage?: string;
    uploadContext?: Record<string, unknown>;
    parentRequestId?: string;
  }) {
    await httpClient.post('/api/v1/spc/uploads/attempts', input);
  },
  async hideUploadAttempt(requestId: string) {
    await httpClient.delete(`/api/v1/spc/uploads/attempts/${requestId}`);
  },
  async exportCharts(chartIds: string[]) {
    const response = await httpClient.post<Blob>('/api/v1/spc/charts/export', { chartIds }, {
      responseType: 'blob', headers: { Accept: '*/*' }, timeout: 0,
    });
    const encodedName = /filename\*=UTF-8''([^;]+)/i.exec(String(response.headers['content-disposition'] || ''))?.[1];
    downloadBlob(response.data, encodedName ? decodeURIComponent(encodedName) : '图谱文件.zip');
  },
  async pages(id: string) {
    const response = await httpClient.get<ApiResponse<SpectrumPage[]>>(
      `/api/v1/spc/charts/${id}/pages`,
    );
    return response.data.data;
  },
  async contentBlob(id: string) {
    const response = await httpClient.get<Blob>(`/api/v1/spc/charts/${id}/content`, {
      responseType: 'blob',
    });
    return response.data;
  },
  async previewBlob(id: string) {
    const response = await httpClient.get<Blob>(`/api/v1/spc/charts/${id}/preview`, {
      responseType: 'blob',
    });
    return response.data;
  },
  contentUrl(id: string) {
    return `/api/v1/spc/charts/${id}/content`;
  },
  async sessions(limit = 50) {
    const response = await httpClient.get<ApiResponse<SpectrumSession[]>>(
      '/api/v1/spc/chat/sessions',
      { params: { limit } },
    );
    return response.data.data;
  },
  async createSession() {
    const response = await httpClient.post<ApiResponse<SpectrumSession>>(
      '/api/v1/spc/chat/sessions',
    );
    return response.data.data;
  },
  async session(id: string) {
    const response = await httpClient.get<ApiResponse<SpectrumSession>>(
      `/api/v1/spc/chat/sessions/${id}`,
    );
    return response.data.data;
  },
  async renameSession(id: string, title: string) {
    await httpClient.patch(`/api/v1/spc/chat/sessions/${id}`, { title });
  },
  async deleteSession(id: string) {
    await httpClient.delete(`/api/v1/spc/chat/sessions/${id}`);
  },
  async submitChat(input: {
    sessionId?: string;
    question: string;
    chartIds?: string[];
    pageSelections?: Record<string, number[]>;
    analysisIntent?: SpectrumAnalysisIntent;
    scenarioTemplate?: string;
    replaceFromMessageId?: string;
    expectedLastMessageId?: string;
  }) {
    const response = await httpClient.post<
      ApiResponse<{ sessionId: string; messageId: string; analysisRunId: string; status: string }>
    >('/api/v1/spc/chat/messages', {
      ...input,
      pageSelections: normalizePageSelections(input.pageSelections),
    });
    return response.data.data;
  },
  async analysis(id: string) {
    const response = await httpClient.get<ApiResponse<SpectrumAnalysis>>(
      `/api/v1/spc/analysis-runs/${id}`,
    );
    return response.data.data;
  },
  async streamAnalysis(
    id: string,
    onEvent: (event: string, data: unknown, eventId?: string) => void,
    lastEventId = 0,
  ) {
    const headers: Record<string, string> = {
      Accept: 'text/event-stream',
      'X-Request-Id': generateUUID(),
    };
    if (lastEventId > 0) headers['Last-Event-ID'] = String(lastEventId);
    const response = await fetch(`${appEnv.apiBaseUrl}/api/v1/spc/analysis-runs/${id}/events`, {
      credentials: 'include',
      headers,
    });
    if (response.status === 401) notifyAuthRequired();
    if (!response.ok || !response.body) throw new Error(`图谱进度流连接失败（${response.status}）`);
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let event = 'message';
    const consume = (chunk: string) => {
      buffer += chunk;
      const frames = buffer.split(/\r?\n\r?\n/);
      buffer = frames.pop() || '';
      for (const frame of frames) {
        let data = '';
        let eventId: string | undefined;
        for (const line of frame.split(/\r?\n/)) {
          if (line.startsWith('event:')) event = line.slice(6).trim();
          if (line.startsWith('id:')) eventId = line.slice(3).trim();
          if (line.startsWith('data:')) data += line.slice(5).trim();
        }
        if (data) {
          let parsed: unknown = data;
          try {
            parsed = JSON.parse(data);
          } catch {
            /* Spring may send a plain string */
          }
          if (typeof parsed === 'string' && parsed.trim().startsWith('{')) {
            try {
              parsed = JSON.parse(parsed);
            } catch {
              /* keep the string */
            }
          }
          onEvent(event, parsed, eventId);
        }
        event = 'message';
      }
    };
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      consume(decoder.decode(value, { stream: true }));
    }
    consume(decoder.decode());
  },
};
