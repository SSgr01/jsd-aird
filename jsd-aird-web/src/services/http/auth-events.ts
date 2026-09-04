type AuthRequiredListener = () => void;

const listeners = new Set<AuthRequiredListener>();

export function subscribeAuthRequired(listener: AuthRequiredListener) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function notifyAuthRequired() {
  listeners.forEach((listener) => listener());
}
