import { describe, expect, it, vi } from 'vitest';

import { notifyAuthRequired, subscribeAuthRequired } from '@/services/http/auth-events';
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
});
