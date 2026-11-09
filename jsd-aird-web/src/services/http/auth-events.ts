type AuthRequiredListener = () => void;

const listeners = new Set<AuthRequiredListener>();
const replacementListeners = new Set<() => void>();
let sessionEpoch = 0;
let replacementPending = false;
export function getSessionEpoch() { return sessionEpoch; }
export function advanceSessionEpoch() { sessionEpoch += 1; }
export function isSessionReplacementPending() { return replacementPending; }

export function subscribeSessionReplacement(listener: () => void) {
  replacementListeners.add(listener);
  return () => { replacementListeners.delete(listener); };
}

export function confirmSessionReplacement() {
  if (!replacementPending) return;
  replacementPending = false;
  replacementListeners.forEach((listener) => listener());
  listeners.forEach((listener) => listener());
}

export function subscribeAuthRequired(listener: AuthRequiredListener) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function notifyAuthRequired(code?: string) {
  if (code === 'SESSION_REPLACED') {
    if (!replacementPending) {
      replacementPending = true;
      replacementListeners.forEach((listener) => listener());
    }
    return;
  }
  if (replacementPending) return;
  listeners.forEach((listener) => listener());
}
