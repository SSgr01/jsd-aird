import { appEnv } from '@/app/config/env';
import { ensureCsrfToken, refreshCsrfToken } from '@/services/http/client';
import { notifyAuthRequired } from '@/services/http/auth-events';
import { generateUUID } from '@/utils/uuid';

export interface OperationAnswer {
  answer: string;
  links: Array<{ pageId: string; label: string; path: string }>;
  sources: Array<{ sectionId: string; title: string }>;
  manualVersion: string;
  requestId: string;
}

export interface OperationQuestion {
  question: string;
  currentPageId?: string;
  history: Array<{ role: 'USER' | 'ASSISTANT'; content: string }>;
}

export type OperationEvent =
  | { event: 'meta'; data: { manualVersion: string; requestId: string } }
  | { event: 'delta'; data: { text: string; replace: boolean } }
  | { event: 'done'; data: OperationAnswer };

export class OperationRequestError extends Error {
  constructor(
    message: string,
    public readonly code: string,
    public readonly requestId?: string,
    public readonly fallback?: OperationAnswer,
  ) {
    super(message);
    this.name = 'OperationRequestError';
  }
}

export function isOperationAnswer(value: unknown): value is OperationAnswer {
  if (!value || typeof value !== 'object') return false;
  const answer = value as OperationAnswer;
  return (
    typeof answer.answer === 'string' &&
    typeof answer.manualVersion === 'string' &&
    typeof answer.requestId === 'string' &&
    Array.isArray(answer.links) &&
    Array.isArray(answer.sources) &&
    answer.links.every(
      (link) =>
        link &&
        typeof link.pageId === 'string' &&
        typeof link.path === 'string' &&
        typeof link.label === 'string',
    ) &&
    answer.sources.every(
      (source) =>
        source && typeof source.sectionId === 'string' && typeof source.title === 'string',
    )
  );
}

/** Handles UTF-8 chunks, CRLF, comments, multiline data and an unterminated final frame. */
export function createOperationSseParser(onFrame: (event: string, data: unknown) => void) {
  let buffer = '';
  const frame = (raw: string) => {
    let event = 'message';
    const data: string[] = [];
    for (const line of raw.split(/\r?\n/)) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
    }
    if (!data.length) return;
    try {
      onFrame(event, JSON.parse(data.join('\n')) as unknown);
    } catch (error) {
      if (error instanceof SyntaxError)
        throw new OperationRequestError('回答格式无效，请重试。', 'STREAM_INVALID');
      throw error;
    }
  };
  return {
    push(chunk: string) {
      buffer += chunk;
      let boundary = /\r?\n\r?\n/.exec(buffer);
      while (boundary) {
        frame(buffer.slice(0, boundary.index));
        buffer = buffer.slice(boundary.index + boundary[0].length);
        boundary = /\r?\n\r?\n/.exec(buffer);
      }
    },
    finish() {
      if (buffer.trim()) frame(buffer);
      buffer = '';
    },
  };
}

function failure(payload: unknown, fallbackMessage: string, responseRequestId?: string | null) {
  const error =
    payload && typeof payload === 'object'
      ? (payload as {
          message?: string;
          code?: string;
          requestId?: string;
          traceId?: string;
          fallback?: unknown;
        })
      : {};
  return new OperationRequestError(
    typeof error.message === 'string' ? error.message : fallbackMessage,
    typeof error.code === 'string' ? error.code : 'REQUEST_FAILED',
    error.requestId || error.traceId || responseRequestId || undefined,
    isOperationAnswer(error.fallback) ? error.fallback : undefined,
  );
}

export const operationAssistantApi = {
  async stream(
    input: OperationQuestion,
    signal: AbortSignal,
    onEvent: (event: OperationEvent) => void,
  ) {
    const timeout = AbortSignal.timeout(150_000);
    const combined = AbortSignal.any([signal, timeout]);
    const request = (csrf: string) =>
      fetch(`${appEnv.apiBaseUrl}/api/v1/operation-assistant/qa/stream`, {
        method: 'POST',
        credentials: 'include',
        signal: combined,
        headers: {
          Accept: 'text/event-stream',
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': csrf,
          'X-Request-Id': generateUUID(),
        },
        body: JSON.stringify(input),
      });
    let response = await request(await ensureCsrfToken());
    for (let attempt = 0; !response.ok && attempt < 2; attempt += 1) {
      const payload: unknown = await response.json().catch(() => undefined);
      const error = failure(
        payload,
        `操作问答请求失败（${response.status}），请重试。`,
        response.headers.get('X-Request-Id'),
      );
      if (response.status === 401 || error.code === 'AUTH_REQUIRED') notifyAuthRequired();
      if (attempt === 0 && response.status === 403 && error.code === 'CSRF_TOKEN_INVALID') {
        response = await request(await refreshCsrfToken());
      } else throw error;
    }
    if (!response.body)
      throw new OperationRequestError('回答连接没有建立，请重试。', 'STREAM_EMPTY');
    let terminal = false;
    const parser = createOperationSseParser((event, data) => {
      if (terminal) return;
      if (event === 'error') {
        terminal = true;
        const error = failure(data, '回答未完成，请重试。');
        if (error.code === 'AUTH_REQUIRED') notifyAuthRequired();
        throw error;
      }
      if (event === 'done') {
        if (!isOperationAnswer(data))
          throw new OperationRequestError('回答格式无效，请重试。', 'STREAM_INVALID');
        terminal = true;
        onEvent({ event, data });
      } else if (
        event === 'delta' &&
        data &&
        typeof data === 'object' &&
        'text' in data &&
        typeof data.text === 'string'
      ) {
        onEvent({
          event,
          data: { text: data.text, replace: 'replace' in data && data.replace === true },
        });
      } else if (event === 'meta' && data && typeof data === 'object') {
        onEvent({ event, data: data as { manualVersion: string; requestId: string } });
      }
    });
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    try {
      while (!terminal) {
        const { value, done } = await reader.read();
        if (done) break;
        parser.push(decoder.decode(value, { stream: true }));
      }
      if (!terminal) {
        parser.push(decoder.decode());
        parser.finish();
      }
      if (!terminal)
        throw new OperationRequestError('回答连接已中断，请重试。', 'STREAM_INTERRUPTED');
    } finally {
      await reader.cancel().catch(() => undefined);
      reader.releaseLock();
    }
  },
};
