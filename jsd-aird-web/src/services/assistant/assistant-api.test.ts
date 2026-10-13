import { beforeEach, describe, expect, it, vi } from 'vitest';

const httpMocks = vi.hoisted(() => ({
  ensureCsrfToken: vi.fn(),
  refreshCsrfToken: vi.fn(),
  httpClient: { get: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn() },
  notifyAuthRequired: vi.fn(),
}));

vi.mock('@/services/http/client', () => ({
  ensureCsrfToken: httpMocks.ensureCsrfToken,
  refreshCsrfToken: httpMocks.refreshCsrfToken,
  httpClient: httpMocks.httpClient,
}));

vi.mock('@/services/http/auth-events', () => ({
  notifyAuthRequired: httpMocks.notifyAuthRequired,
}));

import { assistantApi, parseAssistantSseData } from './assistant-api';

describe('parseAssistantSseData', () => {
  it('parses JSON string payloads emitted by the normalized backend', () => {
    expect(parseAssistantSseData('"重排服务不可用"')).toBe('重排服务不可用');
  });

  it('keeps legacy unquoted warning payloads as text', () => {
    expect(parseAssistantSseData('重排服务不可用，已使用 RRF 排序')).toBe(
      '重排服务不可用，已使用 RRF 排序',
    );
  });

  it('parses structured stage payloads', () => {
    expect(parseAssistantSseData('{"status":"ready"}')).toEqual({ status: 'ready' });
  });
});

describe('assistant stream authentication failures', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    httpMocks.ensureCsrfToken.mockResolvedValue('stale-csrf');
    httpMocks.refreshCsrfToken.mockResolvedValue('fresh-csrf');
    httpMocks.notifyAuthRequired.mockReset();
  });

  it('keeps CSRF failures separate from login-session failures', async () => {
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ code: 'CSRF_TOKEN_INVALID' }), { status: 403 }),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ code: 'CSRF_TOKEN_INVALID' }), { status: 403 }),
      );

    await expect(
      assistantApi.stream('测试', undefined, [], [], false, vi.fn(), vi.fn()),
    ).rejects.toMatchObject({ code: 'CSRF_TOKEN_INVALID', status: 403 });

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(httpMocks.notifyAuthRequired).not.toHaveBeenCalled();
  });

  it('sends message replacement guards with an edited question', async () => {
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ code: 'AI_REQUEST_FAILED' }), { status: 409 }),
      );

    await expect(
      assistantApi.stream(
        '修改后的问题',
        'conversation-1',
        [],
        [],
        false,
        vi.fn(),
        vi.fn(),
        undefined,
        { replaceFromMessageId: 'message-2', expectedLastMessageId: 'message-8' },
      ),
    ).rejects.toMatchObject({ status: 409 });

    const request = fetchMock.mock.calls.at(0)?.[1];
    if (!request) throw new Error('未发送流式问答请求');
    expect(
      JSON.parse(typeof request.body === 'string' ? request.body : '') as unknown,
    ).toMatchObject({
      question: '修改后的问题',
      conversationId: 'conversation-1',
      replaceFromMessageId: 'message-2',
      expectedLastMessageId: 'message-8',
    });
  });

  it('dispatches the prepared conversation context from the live SSE stream', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(
        [
          'event: context\ndata: {"conversationId":"conversation-1","userMessageId":"user-1"}',
          'event: token\ndata: {"delta":"回答增量"}',
          'event: done\ndata: {"conversationId":"conversation-1","userMessageId":"user-1","assistantMessageId":"assistant-1","answer":"回答增量","citations":[],"usedWebSearch":false,"traceId":"trace-1","usage":{"inputTokens":1,"outputTokens":1,"totalTokens":2}}',
        ].join('\n\n') + '\n\n',
        { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
      ),
    );
    const onToken = vi.fn();
    const onDone = vi.fn();
    const onEvent = vi.fn();

    await assistantApi.stream('问题', undefined, [], [], false, onToken, onDone, onEvent);

    expect(onEvent).toHaveBeenCalledWith('context', {
      conversationId: 'conversation-1',
      userMessageId: 'user-1',
    });
    expect(onToken).toHaveBeenCalledWith('回答增量');
    expect(onDone).toHaveBeenCalledWith(
      expect.objectContaining({ assistantMessageId: 'assistant-1' }),
    );
  });

  it('marks a live token as a full replacement when the server resynchronizes sanitized text', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(
        'event: token\ndata: {"delta":"修订后的完整回答","replace":true}\n\nevent: done\ndata: {"conversationId":"conversation-1","userMessageId":"user-1","assistantMessageId":"assistant-1","answer":"修订后的完整回答","citations":[],"usedWebSearch":false,"traceId":"trace-1","usage":{"inputTokens":1,"outputTokens":1,"totalTokens":2}}\n\n',
        { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
      ),
    );
    const onToken = vi.fn();

    await assistantApi.stream('问题', undefined, [], [], false, onToken, vi.fn());

    expect(onToken).toHaveBeenCalledWith('修订后的完整回答', true);
  });
});
