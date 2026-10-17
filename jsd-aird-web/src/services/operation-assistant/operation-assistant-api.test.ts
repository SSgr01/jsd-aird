import {
  createOperationSseParser,
  operationAssistantApi,
  OperationRequestError,
} from './operation-assistant-api';

const auth = vi.hoisted(() => ({ notify: vi.fn(), csrf: vi.fn(), refresh: vi.fn() }));
vi.mock('@/services/http/client', () => ({
  ensureCsrfToken: auth.csrf,
  refreshCsrfToken: auth.refresh,
}));
vi.mock('@/services/http/auth-events', () => ({ notifyAuthRequired: auth.notify }));

const answer = {
  answer: '按步骤操作',
  links: [],
  sources: [],
  manualVersion: 'v1',
  requestId: 'req-1',
};

describe('operation assistant streaming API', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    auth.csrf.mockResolvedValue('csrf-1');
    auth.refresh.mockResolvedValue('csrf-2');
  });
  afterEach(() => vi.unstubAllGlobals());

  it('parses split CRLF frames, comments, multiline JSON and the trailing terminal frame', () => {
    const received = vi.fn();
    const parser = createOperationSseParser(received);
    parser.push(': heartbeat\r\nevent: delta\r\ndata: {"text":"实验');
    parser.push(
      '导入",\r\ndata: "replace":true}\r\n\r\nevent: done\ndata: ' + JSON.stringify(answer),
    );
    parser.finish();
    expect(received).toHaveBeenNthCalledWith(1, 'delta', { text: '实验导入', replace: true });
    expect(received).toHaveBeenNthCalledWith(2, 'done', answer);
  });

  it('refreshes a rejected CSRF token once without treating it as a logout', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ code: 'CSRF_TOKEN_INVALID' }), { status: 403 }),
      )
      .mockResolvedValueOnce(new Response('event: done\ndata: ' + JSON.stringify(answer) + '\n\n'));
    vi.stubGlobal('fetch', fetchMock);
    const events = vi.fn();
    await operationAssistantApi.stream(
      { question: '怎样导入？', history: [] },
      new AbortController().signal,
      events,
    );
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(auth.refresh).toHaveBeenCalledTimes(1);
    expect(auth.notify).not.toHaveBeenCalled();
    expect(events).toHaveBeenCalledWith({ event: 'done', data: answer });
  });

  it('reports a stream that closes without a terminal response instead of saving an incomplete answer', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          new Response('event: delta\ndata: {"text":"部分回答","replace":true}\n\n'),
        ),
    );
    await expect(
      operationAssistantApi.stream(
        { question: '问题', history: [] },
        new AbortController().signal,
        vi.fn(),
      ),
    ).rejects.toMatchObject({ code: 'STREAM_INTERRUPTED' });
  });

  it('preserves authorized handbook fallback on model failure', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          'event: error\ndata: ' +
            JSON.stringify({
              code: 'AI_MODEL_NOT_CONFIGURED',
              message: '模型未配置',
              fallback: answer,
            }) +
            '\n\n',
        ),
      ),
    );
    await expect(
      operationAssistantApi.stream(
        { question: '问题', history: [] },
        new AbortController().signal,
        vi.fn(),
      ),
    ).rejects.toEqual(
      expect.objectContaining({ code: 'AI_MODEL_NOT_CONFIGURED', fallback: answer }),
    );
  });

  it('only expires login on an authentication failure', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          new Response(JSON.stringify({ code: 'AUTH_REQUIRED' }), { status: 401 }),
        ),
    );
    await expect(
      operationAssistantApi.stream(
        { question: '问题', history: [] },
        new AbortController().signal,
        vi.fn(),
      ),
    ).rejects.toBeInstanceOf(OperationRequestError);
    expect(auth.notify).toHaveBeenCalledTimes(1);
  });

  it('keeps the backend trace id when an HTTP error happens before the stream starts', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({ code: 'INTERNAL_ERROR', message: '服务器内部错误', traceId: 'req-500' }),
          { status: 500, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    );
    await expect(
      operationAssistantApi.stream(
        { question: '怎样导入实验记录？', history: [] },
        new AbortController().signal,
        vi.fn(),
      ),
    ).rejects.toMatchObject({
      code: 'INTERNAL_ERROR',
      message: '服务器内部错误',
      requestId: 'req-500',
    });
  });
});
