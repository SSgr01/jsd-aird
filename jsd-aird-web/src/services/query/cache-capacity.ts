import type { QueryClient, Query } from '@tanstack/react-query';

/** Serialized data estimate; this is deliberately not a claim about JS heap size. */
export function boundInactiveCache(client: QueryClient, maxEntries = 100, maxBytes = 16 * 1024 * 1024) {
  const sizes = new WeakMap<Query, { data: unknown; bytes: number }>();
  const lastUsed = new WeakMap<object, number>();
  let pending = false;
  return client.getQueryCache().subscribe(event => {
    if (event.type === 'observerAdded' || event.type === 'observerRemoved') lastUsed.set(event.query, Date.now());
    if (pending) return;
    pending = true;
    queueMicrotask(() => {
      // Keep the flag set while removal emits its own cache events.
      try {
        const inactive = client.getQueryCache().findAll({ type: 'inactive' })
          .filter(query => query.getObserversCount() === 0)
          .sort((a, b) => (lastUsed.get(a) ?? a.state.dataUpdatedAt) - (lastUsed.get(b) ?? b.state.dataUpdatedAt));
        let bytes = 0;
        for (const query of inactive) {
          let size = sizes.get(query);
          if (!size || size.data !== query.state.data) {
            let estimate = 0;
            try { estimate = new TextEncoder().encode(JSON.stringify(query.state.data) ?? '').byteLength; }
            catch { estimate = maxBytes + 1; }
            size = { data: query.state.data, bytes: estimate };
            sizes.set(query, size);
          }
          bytes += size.bytes;
        }
        let count = inactive.length;
        for (const query of inactive) {
          if (count <= maxEntries && bytes <= maxBytes) break;
          if (query.getObserversCount() !== 0) continue;
          bytes -= sizes.get(query)?.bytes ?? 0;
          count--;
          client.getQueryCache().remove(query);
        }
      } finally { pending = false; }
    });
  });
}

const polling = new WeakMap<object, { jitter: number; failure: number; failedAt: number; success: number }>();
export function pollInterval(query: { state: Pick<Query['state'], 'dataUpdatedAt' | 'dataUpdateCount' | 'status' | 'errorUpdatedAt' | 'error'> }): number | false {
  let state = polling.get(query);
  if (!state) {
    state = { jitter: 25_000 + Math.floor(Math.random() * 5001), failure: 0, failedAt: 0, success: 0 };
    polling.set(query, state);
  }
  if (query.state.dataUpdateCount !== state.success) {
    state.success = query.state.dataUpdateCount;
    state.failure = 0;
  }
  if (query.state.status === 'error') {
    if (query.state.errorUpdatedAt !== state.failedAt) {
      state.failedAt = query.state.errorUpdatedAt;
      state.failure++;
    }
    const error = query.state.error as { retryAfterMs?: number; status?: number } | null;
    if (error?.status === 401 || error?.status === 403) return false;
    return Math.max([60_000, 120_000, 300_000][Math.min(2, state.failure - 1)] ?? 60_000, error?.retryAfterMs ?? 0);
  }
  return query.state.dataUpdateCount > 1 ? 30_000 : state.jitter;
}
