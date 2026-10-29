import { AxiosError, type InternalAxiosRequestConfig } from 'axios';
import { beforeEach, describe, expect, it, vi } from 'vitest';

function response(config: InternalAxiosRequestConfig, data: unknown, status = 200) {
  return { config, data, status, statusText: '', headers: {} };
}

function csrfFailure(config: InternalAxiosRequestConfig, code = 'CSRF_TOKEN_INVALID') {
  return new AxiosError('Forbidden', 'ERR_BAD_REQUEST', config, undefined,
    response(config, { code, message: 'Forbidden' }, 403));
}

function adapt(handler: (config: InternalAxiosRequestConfig) => ReturnType<typeof response>) {
  return (config: InternalAxiosRequestConfig) => Promise.resolve().then(() => handler(config));
}

describe('HTTP CSRF request lifecycle', () => {
  beforeEach(() => { vi.resetModules(); });
  it('does not retry or invalidate domains when admission rejected a write', async () => {
    const { httpClient } = await import('./client');
    const queries = await import('@/services/query/query-client');
    const invalidate = vi.spyOn(queries, 'invalidateDomains');
    const key = ['business', 'org', 'user', 1, 'projects', { page: 1 }];
    queries.queryClient.setQueryData(key, ['unchanged']);
    const adapter = vi.fn(adapt(config => {
      const rejected = { ...response(config, { code: 'SERVER_BUSY', message: 'busy' }, 503), headers: { 'retry-after': '2' } };
      throw new AxiosError('busy', 'ERR_BAD_RESPONSE', config, undefined, rejected);
    }));
    httpClient.defaults.adapter = adapter;
    try {
      await expect(httpClient.post('/api/v1/projects', {}, { headers: { 'X-XSRF-TOKEN': 'explicit' } }))
        .rejects.toMatchObject({ code: 'SERVER_BUSY', status: 503, retryAfterMs: 2000 });
      expect(adapter).toHaveBeenCalledTimes(1);
      expect(invalidate).not.toHaveBeenCalled();
      expect(queries.queryClient.getQueryData(key)).toEqual(['unchanged']);
    } finally { queries.queryClient.clear(); invalidate.mockRestore(); }
  });

  it('reuses a server-issued token across sequential writes', async () => {
    const { httpClient } = await import('./client');
    const csrf = vi.fn();
    httpClient.defaults.adapter = adapt((config) => {
      if (config.url?.endsWith('/csrf')) {
        csrf();
        return response(config, { data: { token: 'token-1' } });
      }
      expect(config.headers.get('X-XSRF-TOKEN')).toBe('token-1');
      return response(config, {});
    });
    await httpClient.post('/save', {});
    await httpClient.patch('/update', {});
    await httpClient.delete('/remove');
    expect(csrf).toHaveBeenCalledTimes(1);
  });

  it('shares initial token acquisition between concurrent writes and SSE', async () => {
    const { httpClient, ensureCsrfToken } = await import('./client');
    const csrf = vi.fn();
    httpClient.defaults.adapter = adapt((config) => {
      if (config.url?.endsWith('/csrf')) {
        csrf();
        return response(config, { data: { token: 'shared' } });
      }
      expect(config.headers.get('X-XSRF-TOKEN')).toBe('shared');
      return response(config, {});
    });
    const [, , token] = await Promise.all([
      httpClient.post('/first', {}), httpClient.post('/second', {}), ensureCsrfToken(),
    ]);
    expect(token).toBe('shared');
    expect(csrf).toHaveBeenCalledTimes(1);
  });

  it('refreshes an expired token once and retries concurrent rejected writes', async () => {
    const { httpClient, ensureCsrfToken } = await import('./client');
    let tokens = 0;
    const accepted = vi.fn();
    httpClient.defaults.adapter = adapt((config) => {
      if (config.url?.endsWith('/csrf')) {
        tokens += 1;
        return response(config, { data: { token: `token-${tokens}` } });
      }
      if (config.headers.get('X-XSRF-TOKEN') === 'token-1') throw csrfFailure(config);
      expect(config.headers.get('X-XSRF-TOKEN')).toBe('token-2');
      accepted();
      return response(config, {});
    });
    await ensureCsrfToken();
    await Promise.all([httpClient.post('/first', {}), httpClient.post('/second', {})]);
    expect(tokens).toBe(2);
    expect(accepted).toHaveBeenCalledTimes(2);
  });

  it('preserves explicitly supplied tokens without an extra request', async () => {
    const { httpClient } = await import('./client');
    const adapter = vi.fn(adapt((config: InternalAxiosRequestConfig) => {
      expect(config.headers.get('X-XSRF-TOKEN')).toBe('explicit');
      return response(config, {});
    }));
    httpClient.defaults.adapter = adapter;
    await httpClient.post('/save', {}, { headers: { 'X-XSRF-TOKEN': 'explicit' } });
    expect(adapter).toHaveBeenCalledTimes(1);
  });

  it('does not retry a permission denial', async () => {
    const { httpClient } = await import('./client');
    const adapter = vi.fn(adapt((config: InternalAxiosRequestConfig) => {
      throw csrfFailure(config, 'PERMISSION_DENIED');
    }));
    httpClient.defaults.adapter = adapter;
    await expect(httpClient.post('/save', {}, { headers: { 'X-XSRF-TOKEN': 'explicit' } }))
      .rejects.toMatchObject({ code: 'PERMISSION_DENIED' });
    expect(adapter).toHaveBeenCalledTimes(1);
  });

  it('stops after one retry if the refreshed token is rejected', async () => {
    const { httpClient } = await import('./client');
    const writes = vi.fn();
    httpClient.defaults.adapter = adapt((config) => {
      if (config.url?.endsWith('/csrf')) return response(config, { data: { token: 'invalid' } });
      writes();
      throw csrfFailure(config);
    });
    await expect(httpClient.post('/save', {})).rejects.toMatchObject({ code: 'CSRF_TOKEN_INVALID' });
    expect(writes).toHaveBeenCalledTimes(2);
  });

  it('does not send an unprotected write if token acquisition fails', async () => {
    const { httpClient } = await import('./client');
    const adapter = vi.fn(adapt((config) => response(config, { data: {} })));
    httpClient.defaults.adapter = adapter;
    await expect(httpClient.post('/save', {})).rejects.toThrow('CSRF 安全令牌获取失败');
    expect(adapter).toHaveBeenCalledTimes(1);
  });

  it('clears cached resource variants when a write is forbidden', async () => {
    const { httpClient } = await import('./client');
    const { queryClient } = await import('@/services/query/query-client');
    const key = ['business', 'org', 'user', 1, 'projects', { page: 1 }];
    queryClient.setQueryData(key, ['private']);
    httpClient.defaults.adapter = adapt(config => { throw csrfFailure(config, 'PERMISSION_DENIED'); });
    await expect(httpClient.post('/api/v1/projects/update', {}, {headers:{'X-XSRF-TOKEN':'explicit'}})).rejects.toMatchObject({status:403});
    await vi.waitFor(() => expect(queryClient.getQueryData(key)).toBeUndefined());
    queryClient.clear();
  });

  it('ignores an old request 401 after identity changes', async () => {
    const { httpClient } = await import('./client');
    const events = await import('./auth-events');
    const required = vi.fn();
    const unsubscribe = events.subscribeAuthRequired(required);
    let reject!: (error: Error) => void;
    let requestConfig: InternalAxiosRequestConfig | undefined;
    httpClient.defaults.adapter = config => new Promise((_resolve, fail) => { requestConfig=config; reject=fail; });
    const pending = expect(httpClient.get('/api/v1/projects')).rejects.toMatchObject({code:'ERR_CANCELED'});
    await vi.waitFor(() => expect(requestConfig).toBeDefined());
    events.advanceSessionEpoch();
    reject(new AxiosError('Unauthorized','ERR_BAD_REQUEST',requestConfig,undefined,response(requestConfig!,{},401)));
    await pending;
    expect(required).not.toHaveBeenCalled();
    unsubscribe();
  });
});
