import { useEffect } from 'react';

export const SIDEBAR_IDLE_MS = 30_000;

/** Navigation activity keeps the menu open; working in the page lets it rest. */
export function useSidebarAutoCollapse(collapsed: boolean, onCollapse: (collapsed: boolean) => void) {
  useEffect(() => {
    if (collapsed) return;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const paused = () => document.hidden || document.body.classList.contains('workspace-is-maximized');
    const schedule = () => {
      clearTimeout(timer);
      if (paused()) return;
      timer = setTimeout(() => {
        if (paused()) return;
        const focusedInMenu = document.activeElement?.closest('.ant-pro-sider');
        if (focusedInMenu) {
          focusedInMenu.querySelector<HTMLButtonElement>('[data-sidebar-toggle]')?.focus();
        }
        onCollapse(true);
      }, SIDEBAR_IDLE_MS);
    };
    const activity = (event: Event) => {
      if (event.target instanceof Element && event.target.closest('.ant-pro-sider')) schedule();
    };
    const events = ['pointerdown', 'pointermove', 'keydown', 'wheel', 'focusin'] as const;
    events.forEach(type => document.addEventListener(type, activity, { capture: true, passive: true }));
    document.addEventListener('visibilitychange', schedule);
    const observer = new MutationObserver(schedule);
    observer.observe(document.body, { attributes: true, attributeFilter: ['class'] });
    schedule();
    return () => {
      clearTimeout(timer);
      observer.disconnect();
      document.removeEventListener('visibilitychange', schedule);
      events.forEach(type => document.removeEventListener(type, activity, true));
    };
  }, [collapsed, onCollapse]);
}
