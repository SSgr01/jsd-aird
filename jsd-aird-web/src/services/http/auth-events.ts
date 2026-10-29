type AuthRequiredListener = () => void;

const listeners = new Set<AuthRequiredListener>();
let sessionEpoch = 0;
export function getSessionEpoch() { return sessionEpoch; }
export function advanceSessionEpoch() { sessionEpoch += 1; }

export function subscribeAuthRequired(listener: AuthRequiredListener) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function notifyAuthRequired() {
  listeners.forEach((listener) => listener());
}
