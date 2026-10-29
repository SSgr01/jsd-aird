import { QueryCache, QueryClient } from '@tanstack/react-query';
import { HttpError } from '@/services/http/errors';
import { getSessionEpoch } from '@/services/http/auth-events';
import { publishCacheInvalidation } from './query-sync';
import { boundInactiveCache } from './cache-capacity';

function queryKind(key: readonly unknown[]) {
  const params = key[5];
  return params && typeof params === 'object' && 'kind' in params ? params.kind : undefined;
}

export function retryRead(failures: number, error: unknown) {
  return failures < 1 && error instanceof HttpError && (error.status === undefined || error.status >= 500);
}

export function createQueryClient() {
  const client = new QueryClient({
    queryCache: new QueryCache({ onError: (error, failed) => {
      if (!(error instanceof HttpError) || error.status !== 403) return;
      const domain = failed.queryKey[4];
      // Preserve a terminal error on active observers, but remove every cached
      // row and cancel sibling requests that could otherwise refill the domain.
      const kind = queryKind(failed.queryKey);
      const queries = client.getQueryCache().findAll({ predicate: query => query.queryKey[4] === domain && queryKind(query.queryKey) === kind });
      for (const query of queries) {
        if (query !== failed) void query.cancel();
        query.setState({ data: undefined, error, status: 'error', fetchStatus: 'idle' });
      }
    } }),
    defaultOptions: {
      queries: { staleTime: 30_000, gcTime: 600_000, retry: retryRead, retryDelay: (_count, error) => error instanceof HttpError ? error.retryAfterMs ?? 1000 : 1000, refetchOnMount: 'always', refetchOnWindowFocus: true, refetchOnReconnect: true },
      mutations: { retry: false },
    },
  });
  boundInactiveCache(client);
  client.getQueryCache().subscribe(event => {
    if (event.type === 'removed' && client === queryClient && client.getQueryCache().getAll().length === 0) cancelPendingRefresh();
  });
  return client;
}

export const queryClient = createQueryClient();

export function clearSessionQueries() {
  cancelPendingRefresh();
  // cancelQueries synchronously cancels retryers before clear removes their data.
  void queryClient.cancelQueries();
  queryClient.clear();
}

function cancelPendingRefresh() {
  if (refreshTimer) clearTimeout(refreshTimer);
  refreshTimer = undefined;
  refreshDomains.clear();
  refreshResolvers.splice(0).forEach(resolve => resolve());
}

let refreshTimer: ReturnType<typeof setTimeout> | undefined;
let firstRefreshAt = 0;
const refreshDomains = new Set<string>();
const refreshResolvers: Array<() => void> = [];

/** All variants (including dashboard totals and category counts) in this domain. */
export async function invalidateDomains(domains: readonly string[], broadcast = true) {
  if (!domains.length) return;
  if (broadcast) publishCacheInvalidation(domains);
  const epoch = getSessionEpoch();
  const predicate = (query: { queryKey: readonly unknown[] }) => domains.includes(String(query.queryKey[4]));
  // invalidateQueries alone keeps an in-flight FIRST fetch when it has no data.
  // Explicit cancellation prevents that pre-write snapshot becoming authoritative.
  await queryClient.cancelQueries({ predicate });
  if (epoch !== getSessionEpoch()) return;
  // Known-invalid inactive pages must not resurrect deleted/moved rows on Back.
  queryClient.removeQueries({ predicate, type: 'inactive' });
  domains.forEach(domain => refreshDomains.add(domain));
  if (!refreshTimer) firstRefreshAt = Date.now();
  else clearTimeout(refreshTimer);
  await new Promise<void>(resolve => {
    refreshResolvers.push(resolve);
    refreshTimer = setTimeout(() => {
      refreshTimer = undefined;
      const selected = [...refreshDomains]; refreshDomains.clear();
      const complete = refreshResolvers.splice(0);
      if (epoch !== getSessionEpoch()) { complete.forEach(done => done()); return; }
      void queryClient.invalidateQueries({ predicate: query => selected.includes(String(query.queryKey[4])) })
        .finally(() => complete.forEach(done => done()));
    }, Math.max(0, Math.min(100, 500 - (Date.now() - firstRefreshAt))));
  });
}

export function clearDomain(domain: string, error: HttpError) {
  const predicate = (query: { queryKey: readonly unknown[] }) => query.queryKey[4] === domain;
  void queryClient.cancelQueries({ predicate });
  queryClient.getQueryCache().findAll({ predicate }).forEach(query => query.setState({ data: undefined, error, status: 'error', fetchStatus: 'idle' }));
  // A denied write need not revoke read access. Revalidate active readers once;
  // a denied read settles as an error without creating a retry loop.
  void queryClient.invalidateQueries({ predicate });
}

export function primaryDomainForRequest(url: string): string | undefined {
  if (/\/auth\//.test(url)) return undefined;
  if (/\/experiments|\/experiment-categories/.test(url)) return 'experiments';
  if (/\/tasks/.test(url)) return 'tasks';
  if (/\/projects|\/project-stages|\/stages/.test(url)) return 'projects';
  if (/\/knowledge|\/kb\//.test(url)) return 'knowledge';
  if (/\/data\//.test(url)) return 'data';
  if (/\/inventory\//.test(url)) return 'inventory';
  return undefined;
}

export function domainsForRequest(url: string): string[] {
  if (/\/auth\//.test(url)) return [];
  if (/\/project|\/tasks|\/stages/.test(url)) return ['projects', 'tasks', 'experiments', 'knowledge', 'data'];
  if (/\/experiment/.test(url)) return ['experiments', 'projects', 'data'];
  if (/\/knowledge|\/kb\//.test(url)) return ['knowledge'];
  if (/\/data\//.test(url)) return ['data', 'experiments'];
  if (/\/inventory\//.test(url)) return ['inventory'];
  if (/\/template/.test(url)) return ['data', 'experiments'];
  return [];
}
