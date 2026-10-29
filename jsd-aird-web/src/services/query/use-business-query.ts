import { CancelledError, hashKey, useQuery } from '@tanstack/react-query';
import { useEffect } from 'react';
import { useAuthStore } from '@/stores/auth-store';
import { queryClient } from './query-client';
import { markRouteDataReady } from '@/services/performance/metrics';
import { HttpError } from '@/services/http/errors';
import { pollInterval } from './cache-capacity';

export function businessKey(user: { organizationId: string; userId: string; authVersion: number } | null, domain: string, params: unknown) {
  return ['business', user?.organizationId ?? '', user?.userId ?? '', user?.authVersion ?? 0, domain, params] as const;
}

function withoutPage(value: unknown): unknown {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return value;
  return Object.fromEntries(Object.entries(value).filter(([key]) => !['page', 'size'].includes(key)));
}

export function useBusinessQuery<T>(domain: string, params: Record<string, unknown>, read: (signal: AbortSignal) => Promise<T>, options: { dictionary?: boolean; enabled?: boolean; keepPage?: boolean; live?: boolean } = {}) {
  const user = useAuthStore((state) => state.user);
  const key = businessKey(user, domain, params);
  const result = useQuery({
    queryKey: key,
    queryFn: async ({ signal }) => {
      const current = await useAuthStore.getState().validate();
      if (signal.aborted || !user || (current && (current.organizationId !== user.organizationId || current.userId !== user.userId || current.authVersion !== user.authVersion))) throw new CancelledError();
      if (!current) {
        if (useAuthStore.getState().status !== 'authenticated') throw new CancelledError();
        throw new HttpError('暂时无法校验会话，请稍后重试', 'SESSION_VALIDATION_FAILED');
      }
      return read(signal);
    },
    enabled: Boolean(user) && options.enabled !== false,
    staleTime: options.dictionary ? 300_000 : 30_000,
    refetchOnMount: options.dictionary ? true : 'always',
    refetchInterval: (options.live ?? !options.dictionary) ? query => pollInterval(query) : false,
    refetchIntervalInBackground: false,
    placeholderData: options.keepPage ? (previous, previousQuery) => {
      const old = previousQuery?.queryKey;
      // Pagination may keep rows; filters and identity changes never do.
      return old && hashKey(old.slice(0, 5)) === hashKey(key.slice(0, 5)) && hashKey([withoutPage(old[5])]) === hashKey([withoutPage(params)]) ? previous : undefined;
    } : undefined,
  }, queryClient);
  useEffect(() => {
    if (result.data === undefined || result.isPlaceholderData || options.dictionary || params.kind) return;
    markRouteDataReady();
  }, [result.data, result.isPlaceholderData, options.dictionary, params.kind]);
  return result;
}
