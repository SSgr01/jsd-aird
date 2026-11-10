import { useAuthStore } from '@/stores/auth-store';
import { isSessionReplacementPending } from '@/services/http/auth-events';
import { authApi } from '@/services/auth/auth-api';

const activityKey = 'aird-last-user-activity';
const activityEvents = ['pointerdown', 'keydown', 'wheel', 'touchstart'] as const;
const touchInterval = 30_000;

export function installIdleLogout() {
  let idleLimit: number | null = null;
  let lastActivity = Date.now();
  if (window.location.pathname !== '/login') {
    try {
      const stored = Number(localStorage.getItem(activityKey));
      if (stored > 0 && stored <= Date.now()) lastActivity = stored;
    } catch { /* Storage may be unavailable. */ }
  }

  let loggingOut = false;
  let lastTouchAt = 0;
  let touchPending = false;
  let retryTimer: number | undefined;
  let expiryTimer: number | undefined;
  let renewTimer: number | undefined;
  let disposed = false;
  const authenticated = () => useAuthStore.getState().status === 'authenticated';
  const scheduleCheck = () => {
    window.clearTimeout(expiryTimer);
    if (idleLimit === null || !authenticated() || disposed) return;
    expiryTimer = window.setTimeout(check, Math.max(1, idleLimit - (Date.now() - lastActivity)));
  };
  const check = () => {
    if (!authenticated() || loggingOut || isSessionReplacementPending() || idleLimit === null) return;
    if (Date.now() - lastActivity < idleLimit) { scheduleCheck(); return; }
    loggingOut = true;
    void useAuthStore.getState().logout().catch(() => { /* Local session is already cleared. */ }).finally(() => { loggingOut = false; });
  };
  const loadPolicy = () => {
    void authApi.sessionPolicy().then(({ idleTimeoutMillis }) => {
      if (disposed || !Number.isFinite(idleTimeoutMillis) || idleTimeoutMillis <= 0) throw new Error('Invalid session idle timeout');
      idleLimit = idleTimeoutMillis;
      check();
    }).catch(() => {
      if (!disposed) retryTimer = window.setTimeout(loadPolicy, touchInterval);
    });
  };
  loadPolicy();
  const recordActivity = () => {
    lastActivity = Date.now();
    try { localStorage.setItem(activityKey, String(lastActivity)); } catch { /* Keep this tab's timer. */ }
    scheduleCheck();
  };
  const renew = () => {
    if (!authenticated() || loggingOut || isSessionReplacementPending() || touchPending) return;
    touchPending = true;
    lastTouchAt = Date.now();
    void authApi.activity().then(() => {
      touchPending = false;
      if (lastActivity > lastTouchAt) scheduleRenew();
    }).catch(() => {
      touchPending = false;
      lastTouchAt = 0;
    });
  };
  const scheduleRenew = () => {
    if (!authenticated() || idleLimit === null || loggingOut || touchPending) return;
    window.clearTimeout(renewTimer);
    const delay = Math.min(touchInterval, idleLimit / 2) - (Date.now() - lastTouchAt);
    if (delay <= 0) renew();
    else renewTimer = window.setTimeout(() => { check(); renew(); }, delay);
  };
  const activity = () => {
    if (!authenticated() || idleLimit === null) return;
    check();
    if (loggingOut || !authenticated()) return;
    recordActivity();
    scheduleRenew();
  };
  const unsubscribe = useAuthStore.subscribe((state, previous) => {
    if (state.status === 'authenticated' && previous.status !== 'authenticated') {
      if (idleLimit === null) {
        window.clearTimeout(retryTimer);
        loadPolicy();
      }
      if (previous.status === 'anonymous' || window.location.pathname === '/login') recordActivity();
      else check();
    } else if (state.status !== 'authenticated' && previous.status === 'authenticated') {
      window.clearTimeout(expiryTimer);
      window.clearTimeout(renewTimer);
    }
  });
  for (const event of activityEvents) window.addEventListener(event, activity, { passive: true });
  const onStorage = (event: StorageEvent) => {
    if (event.key !== activityKey) return;
    const timestamp = Number(event.newValue);
    if (timestamp > lastActivity && timestamp <= Date.now()) {
      lastActivity = timestamp;
      scheduleCheck();
    }
  };
  window.addEventListener('storage', onStorage);
  window.addEventListener('focus', check);
  document.addEventListener('visibilitychange', check);
  return () => {
    disposed = true;
    window.clearTimeout(expiryTimer);
    window.clearTimeout(renewTimer);
    window.clearTimeout(retryTimer);
    unsubscribe();
    for (const event of activityEvents) window.removeEventListener(event, activity);
    window.removeEventListener('storage', onStorage);
    window.removeEventListener('focus', check);
    document.removeEventListener('visibilitychange', check);
  };
}
