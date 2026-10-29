import { useEffect, useRef, type PropsWithChildren } from 'react';
import { Link } from 'react-router-dom';
import { preloadRoute } from '@/routes/route-preload';
import { markPerformance } from '@/services/performance/metrics';

/** Used only for menu entries already filtered by permissions. */
export function PrefetchLink({ to, onClick, children }: PropsWithChildren<{ to: string; onClick?: () => void }>) {
  const timer = useRef<ReturnType<typeof setTimeout>>();
  const clear = () => clearTimeout(timer.current);
  const start = () => { clear(); timer.current = setTimeout(() => { void preloadRoute(to).catch(() => undefined); }, 150); };
  useEffect(() => clear, []);
  return <Link to={to} onMouseEnter={start} onMouseLeave={clear} onFocus={start} onBlur={clear} onClick={() => { clear(); markPerformance('route-start'); onClick?.(); }}>{children}</Link>;
}
