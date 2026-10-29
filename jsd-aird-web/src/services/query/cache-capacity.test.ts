import { QueryObserver } from '@tanstack/react-query';
import { createQueryClient } from './query-client';
import { boundInactiveCache, pollInterval } from './cache-capacity';
import { HttpError, retryAfterMillis } from '@/services/http/errors';

it('evicts oldest inactive entries while retaining an active view', async () => {
  const client = createQueryClient(); const stop = boundInactiveCache(client, 2, 1000);
  client.setQueryData(['active'], 'draft view');
  const view = new QueryObserver(client, { queryKey: ['active'], enabled: false });
  const unsubscribe = view.subscribe(() => undefined);
  client.setQueryData(['one'], 'one'); client.setQueryData(['two'], 'two'); client.setQueryData(['three'], 'three');
  await Promise.resolve();
  expect(client.getQueryData(['active'])).toBe('draft view');
  expect(client.getQueryData(['one'])).toBeUndefined(); expect(client.getQueryData(['three'])).toBe('three');
  unsubscribe(); stop(); client.clear();
});
it('enforces serialized byte budget independently from entry count', async () => {
  const client = createQueryClient(); const stop = boundInactiveCache(client, 100, 10);
  client.setQueryData(['big'], 'x'.repeat(20)); await Promise.resolve();
  expect(client.getQueryData(['big'])).toBeUndefined(); stop(); client.clear();
});
it('includes inactive paused queries in the capacity limit during an outage', async () => {
  const client = createQueryClient(); const stop = boundInactiveCache(client, 1, 1000);
  client.setQueryData(['paused'], 'old');
  client.getQueryCache().find({ queryKey: ['paused'] })?.setState({ fetchStatus: 'paused' });
  client.setQueryData(['new'], 'latest');
  await Promise.resolve();
  expect(client.getQueryData(['paused'])).toBeUndefined();
  expect(client.getQueryData(['new'])).toBe('latest');
  stop(); client.clear();
});
it('spreads first polling and backs off failures, then recovers to 30s', () => {
  const query = { state: { dataUpdatedAt: 1, dataUpdateCount: 1, status: 'success' as 'success' | 'error', errorUpdatedAt: 0, error: null as Error | null } };
  const first = pollInterval(query); expect(first).toBeGreaterThanOrEqual(25000); expect(first).toBeLessThanOrEqual(30000);
  query.state.status = 'error'; query.state.error = new HttpError('busy', 'SERVER_BUSY', 503); query.state.errorUpdatedAt++;
  expect(pollInterval(query)).toBe(60000); query.state.errorUpdatedAt++; expect(pollInterval(query)).toBe(120000);
  query.state.errorUpdatedAt++; expect(pollInterval(query)).toBe(300000);
  query.state.dataUpdatedAt++; query.state.dataUpdateCount++; query.state.status = 'success'; query.state.error = null;
  expect(pollInterval(query)).toBe(30000);
});
it('parses Retry-After seconds/date and rejects malformed values', () => {
  expect(retryAfterMillis('2')).toBe(2000); expect(retryAfterMillis('garbage')).toBeUndefined();
  expect(retryAfterMillis(new Date(5000).toUTCString(), 1000)).toBe(4000);
  expect(retryAfterMillis('7200')).toBe(7_200_000);
});
it('uses last observed time when evicting an old query that was recently revisited', async () => {
  const client = createQueryClient(); const stop = boundInactiveCache(client, 1, 1000);
  const now = vi.spyOn(Date, 'now').mockReturnValue(100);
  let unsubscribe: () => void = () => undefined;
  try {
    client.setQueryData(['revisited'], 'keep');
    now.mockReturnValue(150); client.setQueryData(['other'], 'evict');
    const observer = new QueryObserver(client, { queryKey: ['revisited'], enabled: false });
    unsubscribe = observer.subscribe(() => undefined);
    now.mockReturnValue(200); unsubscribe();
    await Promise.resolve();
    expect(client.getQueryData(['revisited'])).toBe('keep');
    expect(client.getQueryData(['other'])).toBeUndefined();
  } finally { unsubscribe(); stop(); client.clear(); now.mockRestore(); }
});
