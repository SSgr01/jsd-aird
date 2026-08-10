import type { ApiResponse } from '@/types/api';
import { httpClient } from '@/services/http/client';

export interface AssistantCitation {
  sourceType: string;
  chunkId: string;
  documentId?: string;
  versionId?: string;
  dataAssetId?: string;
  revisionId?: string;
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
  async stream(
    question: string,
    conversationId: string | undefined,
    scopeIds: string[],
    scopeTypes: string[],
    onToken: (token: string) => void,
    onDone: (response: AssistantResponse) => void,
    onStage?: (event: string, data: unknown) => void,
  ) {
    const response = await fetch(`${import.meta.env.VITE_API_BASE_URL || ''}/api/v1/assistant/qa/stream`, {
      method: 'POST',
      headers: {
        Accept: 'text/event-stream',
        'Content-Type': 'application/json',
        'X-Request-Id': crypto.randomUUID(),
        'X-Organization-Id': '00000000-0000-0000-0000-000000000001',
        'X-User-Id': '00000000-0000-0000-0000-000000000002',
        'X-Username': 'developer',
      },
      body: JSON.stringify({ question, conversationId, scopeIds, scopeTypes }),
    });
    if (!response.ok || !response.body) throw new Error(`流式问答失败（${response.status}）`);
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
        const parsed: unknown = JSON.parse(data);
        if (event === 'token') onToken(typeof parsed === 'string' ? parsed : String(parsed));
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
