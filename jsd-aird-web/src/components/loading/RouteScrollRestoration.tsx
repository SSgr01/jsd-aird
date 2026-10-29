import { useEffect, useLayoutEffect } from 'react';
import { useLocation } from 'react-router-dom';
import { listPositions as positions } from '@/services/query/session-view-state';
import { getSessionEpoch } from '@/services/http/auth-events';

export function RouteScrollRestoration() {
  const { pathname, search } = useLocation();
  const key = `${pathname}${search}`;
  useLayoutEffect(() => {
    const epoch = getSessionEpoch();
    const main = document.getElementById('main-content');
    const position = positions.get(key);
    // Restore after layout and query cache have painted. No animation on Back.
    if (position) {
      window.scrollTo(0, position.window);
      if (main) main.scrollTop = position.content;
    } else {
      window.scrollTo(0, 0);
      if (main) main.scrollTop = 0;
    }
    let latest = position ?? { window: 0, content: 0 };
    const record = () => { latest = { window: window.scrollY, content: main?.scrollTop ?? 0 }; };
    window.addEventListener('scroll', record, { passive: true });
    main?.addEventListener('scroll', record, { passive: true });
    return () => {
      window.removeEventListener('scroll', record);
      main?.removeEventListener('scroll', record);
      if (epoch === getSessionEpoch()) positions.set(key, latest);
      if (positions.size > 100) positions.delete(positions.keys().next().value!);
    };
  }, [key]);
  useEffect(() => {
    const previous = history.scrollRestoration;
    history.scrollRestoration = 'manual';
    return () => { history.scrollRestoration = previous; };
  }, []);
  return null;
}
