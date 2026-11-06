import { describe, expect, it, vi } from 'vitest';

import { confirmSessionReplacement, isSessionReplacementPending, notifyAuthRequired, subscribeAuthRequired, subscribeSessionReplacement } from '@/services/http/auth-events';
import { useAuthStore } from '@/stores/auth-store';

describe('auth events', () => {
  it('notifies subscribers when authentication is required', () => {
    const listener = vi.fn();
    const unsubscribe = subscribeAuthRequired(listener);

    notifyAuthRequired();
    unsubscribe();
    notifyAuthRequired();

    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('clears the authenticated store when authentication is required', () => {
    useAuthStore.setState({ status: 'authenticated', user: null });

    notifyAuthRequired();

    expect(useAuthStore.getState()).toMatchObject({ status: 'anonymous', user: null });
  });

  it('defers logout until the replacement dialog is confirmed', () => {
    useAuthStore.setState({ status: 'authenticated', user: null });
    const replacementListener = vi.fn();
    const unsubscribe = subscribeSessionReplacement(replacementListener);

    notifyAuthRequired('SESSION_REPLACED');
    notifyAuthRequired('AUTH_REQUIRED');
    notifyAuthRequired('SESSION_REPLACED');

    expect(isSessionReplacementPending()).toBe(true);
    expect(replacementListener).toHaveBeenCalledTimes(1);
    expect(useAuthStore.getState().status).toBe('authenticated');

    confirmSessionReplacement();
    expect(isSessionReplacementPending()).toBe(false);
    expect(useAuthStore.getState().status).toBe('anonymous');
    expect(replacementListener).toHaveBeenCalledTimes(2);
    unsubscribe();
    notifyAuthRequired('AUTH_REQUIRED');
    expect(useAuthStore.getState().status).toBe('anonymous');
  });
});
