import type { ApiResponse } from '@/types/api';
import { ensureCsrfToken, httpClient, refreshCsrfToken } from '@/services/http/client';
import { generateUUID } from '@/utils/uuid';

export class AssistantRequestError extends Error {
  public constructor(
    message: string,
    public readonly code: string,
    public readonly status?: number,
    public readonly traceId?: string,
  ) {
    super(message);
    this.name = 'AssistantRequestError';
  }
}

export interface AssistantCitation {
  sourceType: string;
  chunkId: string;
  documentId?: string;
  versionId?: string;
  fileObjectId?: string;
  importJobId?: string;
  rowNumber?: number;
  title: string;
  originalName: string;
  pageNo?: number;
  section?: string;
  snippet: string;
  retrievalScore: number;
  rrfScore: number;
  rerankScore: number;
  sourceLocator?: string;
}

export interface AssistantResponse {
  conversationId: string;
  answer: string;
  citations: AssistantCitation[];
  warnings: string[];
  usedWebSearch: boolean;
  traceId: string;
  usage: { inputTokens: number; outputTokens: number; totalTokens: number };
  retrievalTrace?: { fallbacks?: string[]; strategy?: string; rerankerStatus?: string };
}

export interface AiScope {
  id: string;
  scopeType: string;
  externalId: string;
  name: string;
  status: string;
  metadata?: Record<string, unknown>;
}

export interface ConversationView {
  conversationId: string;
  messages: Array<{ role: string; content: string; citations?: AssistantCitation[]; warnings?: string[] }>;
}

export interface ConversationMeta {
  id: string;
  title: string;
  summary?: string;
  titleSource?: string;
  scopeSnapshot?: string[];
}

export interface FileSearchResult {
  files: Array<{
    fileObjectId: string;
    logicalDocumentId?: string;
    fileVersionId: string;
    sourceModule: 'KNOWLEDGE' | 'DATA_CENTER';
    title: string;
    originalName: string;
    contentType: string;
    size: number;
    version: number;
    tags: string[];
    updatedAt: string;
    matchType: 'EXACT_FILENAME' | 'EXACT_IDENTIFIER' | 'CONTENT' | 'FULL_TEXT';
    matchedFields: string[];
    matchedTerms: string[];
    hits: Array<{ id: string; snippet: string; score: number; anchor: { pageNo?: number; sheetName?: string; cellRange?: string; paragraphId?: string; bbox?: number[]; startTimeMs?: number; endTimeMs?: number; section?: string; rowNumber?: number; columnName?: string } }>;
  }>;
}

export function parseAssistantSseData(data: string): unknown {
  try {
    return JSON.parse(data);
  } catch {
    // Keep compatibility with older servers that sent String warnings as
    // unquoted SSE data.  A malformed stage payload must not break the stream.
    return data;
  }
}

export const assistantApi = {
  async ask(question: string, conversationId?: string) {
    const response = await httpClient.post<ApiResponse<AssistantResponse>>('/api/v1/assistant/qa', { question, conversationId });
    return response.data.data;
  },
  async scopes() {
    const response = await httpClient.get<ApiResponse<AiScope[]>>('/api/v1/assistant/scopes');
    return response.data.data;
  },
  async conversation(id: string) {
    const response = await httpClient.get<ApiResponse<ConversationView>>(`/api/v1/assistant/conversations/${id}`);
    return response.data.data;
  },
  async conversations() {
    const response = await httpClient.get<ApiResponse<ConversationMeta[]>>('/api/v1/assistant/conversations');
    return response.data.data;
  },
  async renameConversation(id: string, title: string) {
    await httpClient.patch(`/api/v1/assistant/conversations/${id}`, { title });
  },
  async deleteConversation(id: string) {
    await httpClient.delete(`/api/v1/assistant/conversations/${id}`);
  },
  async fileSearch(input: { query: string; aiOnly?: boolean; limit?: number; scopeIds?: string[]; scopeTypes?: string[]; knowledgeCategoryIds?: string[]; dataCategoryIds?: string[] }) {
    const response = await httpClient.post<ApiResponse<FileSearchResult>>('/api/v1/search/files', input);
    return response.data.data;
  },
  async stream(
    question: string,
    conversationId: string | undefined,
    scopeIds: string[],
    scopeTypes: string[],
    knowledgeCategoryIds: string[] = [],
    dataCategoryIds: string[] = [],
    onToken: (token: string) => void,
    onDone: (response: AssistantResponse) => void,
    onStage?: (event: string, data: unknown) => void,
  ) {
    const requestStream = async (csrfToken: string) => fetch(`${import.meta.env.VITE_API_BASE_URL || ''}/api/v1/assistant/qa/stream`, {
      method: 'POST',
      credentials: 'include',
      headers: {
        Accept: 'text/event-stream',
        'Content-Type': 'application/json',
        'X-Request-Id': generateUUID(),
        'X-XSRF-TOKEN': csrfToken,
      },
      body: JSON.stringify({ question, conversationId, scopeIds, scopeTypes, knowledgeCategoryIds, dataCategoryIds }),
    });
    let csrfToken = await ensureCsrfToken();
    let response = await requestStream(csrfToken);
    if (!response.ok || !response.body) {
      let message = `流式问答失败（${response.status}）`;
      let code = 'AI_REQUEST_FAILED';
      let traceId: string | undefined;
      try {
        const payload = JSON.parse(await response.text()) as { message?: string; code?: string; traceId?: string };
        if (payload.message) message = payload.message;
        if (payload.code) code = payload.code;
        traceId = payload.traceId;
      } catch { /* 保留 HTTP 状态提示 */ }
      if (response.status === 403 && code === 'CSRF_TOKEN_INVALID') {
        csrfToken = await refreshCsrfToken();
        response = await requestStream(csrfToken);
        if (response.ok && response.body) {
          // Continue with the retried stream below.
        } else {
          throw new AssistantRequestError('页面安全令牌已失效，请刷新页面后重试', 'CSRF_TOKEN_INVALID', response.status, traceId);
        }
      } else {
        throw new AssistantRequestError(message, code, response.status, traceId);
      }
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let event = 'message';
    const consume = (chunk: string) => {
      buffer += chunk;
      const events = buffer.split(/\r?\n\r?\n/);
      buffer = events.pop() || '';
      for (const raw of events) {
        let data = '';
        for (const line of raw.split(/\r?\n/)) {
          if (line.startsWith('event:')) event = line.slice(6).trim();
          if (line.startsWith('data:')) data += line.slice(5).trim();
        }
        if (!data) continue;
        const parsed = parseAssistantSseData(data);
        if (event === 'error') {
          const errorPayload = parsed && typeof parsed === 'object' ? parsed as {
            message?: unknown;
            code?: unknown;
            requestId?: unknown;
            traceId?: unknown;
          } : {};
          throw new AssistantRequestError(
            typeof errorPayload.message === 'string' ? errorPayload.message : 'AI 问答失败',
            typeof errorPayload.code === 'string' ? errorPayload.code : 'AI_REQUEST_FAILED',
            undefined,
            typeof errorPayload.traceId === 'string'
              ? errorPayload.traceId
              : typeof errorPayload.requestId === 'string' ? errorPayload.requestId : undefined,
          );
        }
        if (event === 'token') {
          const token = typeof parsed === 'string'
            ? parsed
            : parsed && typeof parsed === 'object' && 'delta' in parsed && typeof parsed.delta === 'string'
              ? parsed.delta
              : '';
          if (token) onToken(token);
        }
        if (event === 'done') onDone(parsed as AssistantResponse);
        if (event !== 'token' && event !== 'done') onStage?.(event, parsed);
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
