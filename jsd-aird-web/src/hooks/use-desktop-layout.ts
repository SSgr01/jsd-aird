import { useSyncExternalStore } from 'react';

const desktopQuery = '(min-width: 1024px)';
const subscribe = (listener: () => void) => {
  const query = window.matchMedia(desktopQuery);
  query.addEventListener('change', listener);
  return () => query.removeEventListener('change', listener);
};
const getSnapshot = () => window.matchMedia(desktopQuery).matches;

/** Match the shared desktop CSS exactly, including when resizing inside Ant's lg range. */
export function useDesktopLayout() {
  return useSyncExternalStore(subscribe, getSnapshot, () => false);
}
