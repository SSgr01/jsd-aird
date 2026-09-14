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

  it('notifies the auth store after a CSRF refresh still leaves the session invalid', async () => {
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
    ).rejects.toMatchObject({ code: 'AUTH_REQUIRED', status: 403 });

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(httpMocks.notifyAuthRequired).toHaveBeenCalledTimes(1);
  });
});
