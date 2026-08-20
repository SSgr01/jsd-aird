import type { ApiResponse, PageResponse } from '@/types/api';
import { appEnv } from '@/app/config/env';
import { httpClient } from '@/services/http/client';
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
export interface SpectrumResult {
  analysisStatus?: 'SUCCEEDED' | 'PARTIAL' | 'FAILED';
  errorMessage?: string;
  answerMarkdown?: string;
  observations?: unknown[];
  comparisons?: unknown[];
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
}
export interface SpectrumAnalysis {
  id: string;
  sessionId: string;
  mode: string;
  question: string;
  chartIds: string[];
  pageSelections: Record<string, number[]>;
  categories: string[];
  scenarioTemplate?: string;
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
    fileId: string;
    title?: string;
    categoryId: string;
    sampleName?: string;
    batchNo?: string;
    testConditions?: string;
    metadata?: Record<string, unknown>;
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
    chartIds: string[];
    pageSelections?: Record<string, number[]>;
    scenarioTemplate?: string;
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
  async streamAnalysis(id: string, onEvent: (event: string, data: unknown) => void) {
    const response = await fetch(`${appEnv.apiBaseUrl}/api/v1/spc/analysis-runs/${id}/events`, {
      credentials: 'include',
      headers: {
        Accept: 'text/event-stream',
        'X-Request-Id': generateUUID(),
      },
    });
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
        for (const line of frame.split(/\r?\n/)) {
          if (line.startsWith('event:')) event = line.slice(6).trim();
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
          onEvent(event, parsed);
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
