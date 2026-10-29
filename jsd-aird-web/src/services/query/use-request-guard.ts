import { useCallback, useEffect, useRef } from 'react';
import { getSessionEpoch } from '@/services/http/auth-events';

/** Ownership for direct reads that do not use the business query cache. */
export function useRequestGuard(scope: string) {
  const active = useRef<AbortController>();
  const currentScope = useRef(scope);
  currentScope.current = scope;
  useEffect(() => () => active.current?.abort(), [scope]);
  return useCallback(() => {
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
}
