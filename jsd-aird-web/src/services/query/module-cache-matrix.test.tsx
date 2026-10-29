import { act, renderHook, waitFor } from '@testing-library/react';
import { AxiosError, type InternalAxiosRequestConfig } from 'axios';
import { httpClient } from '@/services/http/client';
import { useAuthStore } from '@/stores/auth-store';
import { testUser } from '@/test/auth-user';
import { businessKey, useBusinessQuery } from './use-business-query';
import { createQueryClient, queryClient } from './query-client';

const modules = [
  ['projects', '/api/v1/projects'],
  ['tasks', '/api/v1/tasks'],
  ['experiments', '/api/v1/experiments'],
  ['knowledge', '/api/v1/knowledge/documents'],
  ['data', '/api/v1/data/sources'],
  ['inventory', '/api/v1/inventory/balances'],
] as const;
interface Page {
  revision: number;
  owner: string;
  total: number;
}
const adapter = httpClient.defaults.adapter;
let revision: number;
let reads: number;
let writes: number;
let hold: boolean;
let pending: Array<{ release: () => void; signal?: AbortSignal }>;
let writeError: number | 'timeout' | undefined;
let commit: boolean;
let deniedRead: number | undefined;
const headers = { 'X-XSRF-TOKEN': 'isolated-fixture' };
function response(config: InternalAxiosRequestConfig, value: unknown, status = 200) {
  return { config, data: { data: value }, headers: {}, status, statusText: '' };
}
function read(url: string, signal: AbortSignal) {
  return httpClient.get<{ data: Page }>(url, { signal }).then((result) => result.data.data);
}
beforeEach(() => {
  queryClient.clear();
  const defaults = createQueryClient().getDefaultOptions();
  queryClient.setDefaultOptions({ ...defaults, queries: { ...defaults.queries, retryDelay: 0 } });
  useAuthStore.setState({ user: null, status: 'unknown' });
  useAuthStore.setState({ user: testUser, status: 'authenticated' });
  revision = 1;
  reads = 0;
  writes = 0;
  hold = false;
  pending = [];
  writeError = undefined;
  commit = false;
  deniedRead = undefined;
  httpClient.defaults.adapter = (config) => {
    if (config.method === 'get') {
      reads++;
      if (deniedRead)
        return Promise.reject(
          new AxiosError(
            'denied',
            'ERR_BAD_RESPONSE',
            config,
            undefined,
            response(config, {}, deniedRead),
          ),
        );
      const value = {
        revision,
        owner: useAuthStore.getState().user?.userId ?? '',
        total: revision,
      };
      if (hold) {
        hold = false;
        return new Promise((resolve) =>
          pending.push({
            release: () => resolve(response(config, value)),
            signal: config.signal as AbortSignal,
          }),
        );
      }
      return Promise.resolve(response(config, value));
    }
    writes++;
    if (!writeError || commit) revision++;
    if (writeError === 'timeout')
      return Promise.reject(new AxiosError('timeout', 'ECONNABORTED', config));
    if (writeError)
      return Promise.reject(
        new AxiosError(
          'write failed',
          'ERR_BAD_RESPONSE',
          config,
          undefined,
          response(config, {}, writeError),
        ),
      );
    return Promise.resolve(response(config, { revision }));
  };
});
afterEach(() => {
  queryClient.clear();
  pending.forEach((request) => request.release());
  httpClient.defaults.adapter = adapter;
});

describe.each(modules)('%s cache and exception matrix', (domain, url) => {
  it('cancels the first stale GET and refreshes lists, filters and counts after a write', async () => {
    const inactive = businessKey(testUser, domain, { page: 2, category: 'old' });
    queryClient.setQueryData(inactive, { revision: 1 });
    hold = true;
    const list = renderHook(() =>
      useBusinessQuery(domain, { page: 1 }, (signal) => read(url, signal)),
    );
    const counts = renderHook(() =>
      useBusinessQuery(domain, { kind: 'counts' }, (signal) => read(url, signal)),
    );
    await waitFor(() => expect(pending).toHaveLength(1));
    await act(async () => {
      await httpClient.post(`${url}/fixture-save`, { revision: 1 }, { headers });
    });
    await waitFor(() => expect(list.result.current.data?.revision).toBe(2));
    await waitFor(() => expect(counts.result.current.data?.revision).toBe(2));
    await act(async () => {
      pending[0]!.release();
      await Promise.resolve();
    });
    expect(list.result.current.data?.revision).toBe(2);
    expect(pending[0]?.signal?.aborted).toBe(true);
    expect(queryClient.getQueryData(inactive)).toBeUndefined();
    expect(writes).toBe(1);
  });

  it.each([409, 500, 'timeout'] as const)(
    'reconciles %s by reading and never repeats the write',
    async (status) => {
      const list = renderHook(() => useBusinessQuery(domain, {}, (signal) => read(url, signal)));
      await waitFor(() => expect(list.result.current.data?.revision).toBe(1));
      writeError = status;
      commit = true;
      await act(async () => {
        await expect(httpClient.post(`${url}/fixture-save`, {}, { headers })).rejects.toThrow();
      });
      await waitFor(() => expect(list.result.current.data?.revision).toBe(2));
      expect(writes).toBe(1);
    },
  );

  it.each([400, 422])(
    'does not discard valid cache or retry rejected %s writes',
    async (status) => {
      const list = renderHook(() => useBusinessQuery(domain, {}, (signal) => read(url, signal)));
      await waitFor(() => expect(list.result.current.data?.revision).toBe(1));
      writeError = status;
      await act(async () => {
        await expect(httpClient.post(`${url}/fixture-save`, {}, { headers })).rejects.toMatchObject(
          { status },
        );
      });
      expect(list.result.current.data?.revision).toBe(1);
      expect(writes).toBe(1);
      expect(reads).toBe(1);
    },
  );

  it('removes forbidden domain rows without discarding another readable module', async () => {
    const other = businessKey(testUser, 'unrelated-module', {});
    queryClient.setQueryData(other, { readable: true });
    const list = renderHook(() => useBusinessQuery(domain, {}, (signal) => read(url, signal)));
    await waitFor(() => expect(list.result.current.data?.revision).toBe(1));
    deniedRead = 403;
    await act(async () => {
      await list.result.current.refetch();
    });
    await waitFor(() => expect(list.result.current.data).toBeUndefined());
    expect(queryClient.getQueryData(other)).toEqual({ readable: true });
    expect(useAuthStore.getState().status).toBe('authenticated');
  });

  it.each([404, 429, 500, 503])(
    'retains cached rows after a %s read and bounds retries',
    async (status) => {
      const list = renderHook(() => useBusinessQuery(domain, {}, (signal) => read(url, signal)));
      await waitFor(() => expect(list.result.current.data?.revision).toBe(1));
      const before = reads;
      deniedRead = status;
      await act(async () => {
        await list.result.current.refetch();
      });
      await waitFor(() =>
        expect(queryClient.getQueryState(businessKey(testUser, domain, {}))?.status).toBe('error'),
      );
      expect(list.result.current.data?.revision).toBe(1);
      expect(reads - before).toBe(status >= 500 ? 2 : 1);
      expect(useAuthStore.getState().status).toBe('authenticated');
    },
  );

  it.each(['user', 'organization', 'permission'] as const)(
    'clears old %s identity cache and rejects its late response',
    async (kind) => {
      const original = businessKey(testUser, domain, {});
      hold = true;
      const list = renderHook(() => useBusinessQuery(domain, {}, (signal) => read(url, signal)));
      await waitFor(() => expect(pending).toHaveLength(1));
      const next = {
        ...testUser,
        userId: kind === 'user' ? 'another-user' : testUser.userId,
        organizationId: kind === 'organization' ? 'another-org' : testUser.organizationId,
        authVersion: kind === 'permission' ? testUser.authVersion + 1 : testUser.authVersion,
      };
      act(() => useAuthStore.setState({ user: next }));
      await waitFor(() => expect(list.result.current.data?.owner).toBe(next.userId));
      await act(async () => {
        pending[0]!.release();
        await Promise.resolve();
      });
      expect(queryClient.getQueryData(original)).toBeUndefined();
      expect(list.result.current.data?.owner).toBe(next.userId);
      expect(pending[0]?.signal?.aborted).toBe(true);
    },
  );
});
