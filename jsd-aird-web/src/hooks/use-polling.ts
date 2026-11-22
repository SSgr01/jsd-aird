import { useEffect, useRef } from 'react';

/** Wait for a refresh to settle before scheduling the next one, even on slow connections. */
export function usePolling(refresh: () => Promise<unknown>, enabled: boolean, delay: number, key = '') {
  const latest = useRef(refresh); latest.current = refresh;
  useEffect(() => {
    if (!enabled) return;
    let active = true;
    let timer: ReturnType<typeof setTimeout>;
    const schedule = () => { timer = setTimeout(() => void tick(), delay); };
    const tick = async () => {
      try { await latest.current(); }
      catch { /* Each page owns its refresh error state; polling continues after a transient failure. */ }
      finally { if (active) schedule(); }
    };
    schedule();
    return () => { active = false; clearTimeout(timer); };
  }, [enabled, delay, key]);
}
