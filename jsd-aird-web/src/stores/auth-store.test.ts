import { beforeEach, describe, expect, it, vi } from 'vitest';

import { authApi } from '@/services/auth/auth-api';
import { HttpError } from '@/services/http/errors';
import { useAuthStore } from '@/stores/auth-store';

describe('auth store session loading', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    useAuthStore.setState({ status: 'unknown', user: null });
  });

  it('keeps service failures out of the anonymous state', async () => {
    vi.spyOn(authApi, 'me').mockRejectedValueOnce(new Error('network unavailable'));

    await useAuthStore.getState().load();

    expect(useAuthStore.getState().status).toBe('error');
  });

  it('marks the session anonymous for an authentication failure', async () => {
    vi.spyOn(authApi, 'me').mockRejectedValueOnce(new HttpError('请先登录', 'AUTH_REQUIRED', 401));

    await useAuthStore.getState().load();

    expect(useAuthStore.getState()).toMatchObject({ status: 'anonymous', user: null });
  });
});
