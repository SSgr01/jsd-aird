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
    const tableSelector = '.pm-table-scroll, .cm-table-wrap, .pm-unified-list-page .ant-table-content, .catalog-list-panel .ant-table-content, .quality-grid-wrap';
    const restored = new WeakSet<Element>();
    const regions = () => Array.from(main?.querySelectorAll<HTMLElement>(tableSelector) ?? []);
    const restoreTables = () => {
      regions().forEach((region, index) => {
        const saved = position?.tables?.[index];
        if (!saved || restored.has(region)) return;
        // Wait for cached/asynchronous rows to give the scroll owner its height.
        if (region.scrollHeight - region.clientHeight < saved.top || region.scrollWidth - region.clientWidth < saved.left) return;
        region.scrollTop = saved.top; region.scrollLeft = saved.left;
        restored.add(region);
      });
    };
    restoreTables();
    const observer = new MutationObserver(restoreTables);
    if (main) observer.observe(main, { childList: true, subtree: true });
    const record = (event: Event) => {
      latest = { ...latest, window: window.scrollY, content: main?.scrollTop ?? 0 };
      const index = regions().indexOf(event.target as HTMLElement);
      if (index >= 0) {
        const region = event.target as HTMLElement;
        latest.tables = { ...latest.tables, [index]: { top: region.scrollTop, left: region.scrollLeft } };
      }
    };
    window.addEventListener('scroll', record, { passive: true });
    main?.addEventListener('scroll', record, { passive: true, capture: true });
    return () => {
      window.removeEventListener('scroll', record);
      main?.removeEventListener('scroll', record, true);
      observer.disconnect();
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
