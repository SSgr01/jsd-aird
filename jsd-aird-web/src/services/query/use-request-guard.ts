import { useCallback, useEffect, useMemo, useRef } from 'react';
import { getSessionEpoch } from '@/services/http/auth-events';

/** Ownership for direct reads that do not use the business query cache. */
export function useRequestGuard(scope: string) {
  const active = useRef<AbortController>();
  const currentScope = useRef(scope);
  currentScope.current = scope;
  useEffect(() => () => active.current?.abort(), [scope]);
  const begin = useCallback(() => {
    active.current?.abort();
    const controller = new AbortController();
    active.current = controller;
    const owner = currentScope.current;
    const epoch = getSessionEpoch();
    return {
      signal: controller.signal,
      current: () =>
        active.current === controller &&
        !controller.signal.aborted &&
        owner === currentScope.current &&
        epoch === getSessionEpoch(),
    };
  }, []);
  return useMemo(() => Object.assign(begin, { invalidate: () => active.current?.abort() }), [begin]);
}

/** The same ownership guard for reads and writes whose API does not accept AbortSignal. */
export function useLatestRequest(scope: string) {
  const request = useRequestGuard(scope);
  const owner = useMemo(() => ({ active: true }), [scope]);
  useEffect(() => {
    owner.active = true;
    return () => { owner.active = false; };
  }, [owner]);
  return useMemo(() => ({
    begin: () => { const current = request(); return () => owner.active && current.current(); },
    invalidate: request.invalidate,
    isActive: () => owner.active,
  }), [owner, request]);
}
