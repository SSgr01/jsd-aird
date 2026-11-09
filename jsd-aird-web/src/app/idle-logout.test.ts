import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { installIdleLogout } from '@/app/idle-logout';
import { authApi } from '@/services/auth/auth-api';
import { useAuthStore } from '@/stores/auth-store';
import { testUser } from '@/test/auth-user';

describe('idle logout', () => {
  let dispose: () => void;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-11-05T12:00:00Z'));
    useAuthStore.setState({ user: null, status: 'anonymous' });
    localStorage.setItem('aird-last-user-activity', String(Date.now() - 31 * 60_000));
    vi.spyOn(authApi, 'logout').mockResolvedValue();
    vi.spyOn(authApi, 'sessionPolicy').mockResolvedValue({ idleTimeoutMillis: 30 * 60_000 });
    vi.spyOn(authApi, 'activity').mockResolvedValue();
  });

  afterEach(() => {
    dispose?.();
    window.history.replaceState(null, '', '/');
    localStorage.removeItem('aird-last-user-activity');
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('keeps a fresh login even when stored activity is old', async () => {
    dispose = installIdleLogout();
    await Promise.resolve();
    useAuthStore.setState({ user: testUser, status: 'authenticated' });

    expect(authApi.logout).not.toHaveBeenCalled();
    expect(Number(localStorage.getItem('aird-last-user-activity'))).toBe(Date.now());
    expect(useAuthStore.getState().status).toBe('authenticated');
  });

  it('expires an idle session restored on page load', async () => {
    useAuthStore.setState({ user: null, status: 'unknown' });
    dispose = installIdleLogout();
    useAuthStore.setState({ user: testUser, status: 'authenticated' });
    await Promise.resolve();

    expect(authApi.logout).toHaveBeenCalledOnce();
    expect(useAuthStore.getState().status).toBe('anonymous');
  });

  it('keeps a fresh login from the login page before initial session loading', async () => {
    window.history.replaceState(null, '', '/login');
    useAuthStore.setState({ user: null, status: 'unknown' });
    dispose = installIdleLogout();
    useAuthStore.setState({ user: testUser, status: 'authenticated' });
    await Promise.resolve();

    expect(authApi.logout).not.toHaveBeenCalled();
    expect(useAuthStore.getState().status).toBe('authenticated');
  });

  it('uses the server-configured timeout and checks when a background tab resumes', async () => {
    vi.mocked(authApi.sessionPolicy).mockResolvedValue({ idleTimeoutMillis: 5 * 60_000 });
    localStorage.setItem('aird-last-user-activity', String(Date.now()));
    useAuthStore.setState({ user: null, status: 'unknown' });
    dispose = installIdleLogout();
    await Promise.resolve();
    useAuthStore.setState({ user: testUser, status: 'authenticated' });
    vi.setSystemTime(Date.now() + 5 * 60_000);
    window.dispatchEvent(new Event('focus'));
    expect(authApi.logout).toHaveBeenCalledOnce();
  });

  it('logs out after the configured duration without input', async () => {
    localStorage.setItem('aird-last-user-activity', String(Date.now()));
    useAuthStore.setState({ user: null, status: 'unknown' });
    dispose = installIdleLogout();
    await Promise.resolve();
    useAuthStore.setState({ user: testUser, status: 'authenticated' });

    await vi.advanceTimersByTimeAsync(30 * 60_000);

    expect(authApi.logout).toHaveBeenCalledOnce();
    expect(useAuthStore.getState().status).toBe('anonymous');
  });

  it('renews a short configured session before its idle limit', async () => {
    vi.mocked(authApi.sessionPolicy).mockResolvedValue({ idleTimeoutMillis: 20_000 });
    localStorage.setItem('aird-last-user-activity', String(Date.now()));
    useAuthStore.setState({ user: null, status: 'unknown' });
    dispose = installIdleLogout();
    await Promise.resolve();
    useAuthStore.setState({ user: testUser, status: 'authenticated' });
    window.dispatchEvent(new Event('keydown'));
    await Promise.resolve();
    await Promise.resolve();
    await vi.advanceTimersByTimeAsync(12_000);
    window.dispatchEvent(new Event('keydown'));
    expect(authApi.activity).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(19_999);
    expect(authApi.logout).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(authApi.logout).toHaveBeenCalledOnce();
  });

  it('shares activity across tabs and throttles server renewal', async () => {
    localStorage.setItem('aird-last-user-activity', String(Date.now()));
    useAuthStore.setState({ user: null, status: 'unknown' });
    dispose = installIdleLogout();
    await Promise.resolve();
    useAuthStore.setState({ user: testUser, status: 'authenticated' });
    window.dispatchEvent(new Event('keydown'));
    window.dispatchEvent(new Event('wheel'));
    expect(authApi.activity).toHaveBeenCalledOnce();
    await Promise.resolve();
    await Promise.resolve();
    vi.setSystemTime(Date.now() + 20 * 60_000);
    window.dispatchEvent(new StorageEvent('storage', {
      key: 'aird-last-user-activity', newValue: String(Date.now()),
    }));
    vi.setSystemTime(Date.now() + 20 * 60_000);
    window.dispatchEvent(new Event('focus'));
    expect(authApi.logout).not.toHaveBeenCalled();
    window.dispatchEvent(new Event('pointerdown'));
    expect(authApi.activity).toHaveBeenCalledTimes(2);
  });

  it('renews once at the end of a throttle window after its last input', async () => {
    localStorage.setItem('aird-last-user-activity', String(Date.now()));
    useAuthStore.setState({ user: null, status: 'unknown' });
    dispose = installIdleLogout();
    await Promise.resolve();
    useAuthStore.setState({ user: testUser, status: 'authenticated' });
    window.dispatchEvent(new Event('keydown'));
    await Promise.resolve();
    await vi.advanceTimersByTimeAsync(29_000);
    window.dispatchEvent(new Event('keydown'));
    expect(authApi.activity).toHaveBeenCalledOnce();
    await vi.advanceTimersByTimeAsync(1_000);
    expect(authApi.activity).toHaveBeenCalledTimes(2);
  });
});
