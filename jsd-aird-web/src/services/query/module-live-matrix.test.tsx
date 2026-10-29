import { act, renderHook } from '@testing-library/react';
import { focusManager, onlineManager } from '@tanstack/react-query';
import { authApi } from '@/services/auth/auth-api';
import { useAuthStore } from '@/stores/auth-store';
import { testUser } from '@/test/auth-user';
import { businessKey, useBusinessQuery } from './use-business-query';
import { createQueryClient, queryClient } from './query-client';

const modules = ['projects', 'tasks', 'experiments', 'knowledge', 'data', 'inventory'];
const tick = async (ms = 10) => {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
};
beforeEach(() => {
  vi.useFakeTimers();
  vi.spyOn(Math, 'random').mockReturnValue(0.99999);
  queryClient.clear();
  queryClient.setDefaultOptions(createQueryClient().getDefaultOptions());
  queryClient.mount();
  focusManager.setFocused(true);
  onlineManager.setOnline(true);
  useAuthStore.setState({ user: null, status: 'unknown' });
  useAuthStore.setState({ user: testUser, status: 'authenticated' });
  vi.spyOn(authApi, 'me').mockResolvedValue(testUser);
});
afterEach(() => {
  queryClient.unmount();
  queryClient.clear();
  focusManager.setFocused(undefined);
  onlineManager.setOnline(true);
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe.each(modules)('%s visibility/reconnect matrix', (domain) => {
  it('shares reads, discovers another user change in 30s, pauses when hidden and resumes online', async () => {
    let revision = 1;
    const read = vi.fn(() => Promise.resolve({ revision }));
    const first = renderHook(() => useBusinessQuery(domain, {}, read));
    const second = renderHook(() => useBusinessQuery(domain, {}, read));
    await tick();
    expect(read).toHaveBeenCalledOnce();
    revision = 2;
    await tick(29_000);
    expect(first.result.current.data?.revision).toBe(1);
    await tick(1_010);
    expect(first.result.current.data?.revision).toBe(2);
    expect(read).toHaveBeenCalledTimes(2);
    act(() => focusManager.setFocused(false));
    revision = 3;
    await tick(90_000);
    expect(read).toHaveBeenCalledTimes(2);
    act(() => focusManager.setFocused(true));
    await tick();
    expect(second.result.current.data?.revision).toBe(3);
    act(() => onlineManager.setOnline(false));
    revision = 4;
    await tick(30_010);
    expect(first.result.current.data?.revision).toBe(3);
    act(() => onlineManager.setOnline(true));
    await tick();
    expect(first.result.current.data?.revision).toBe(4);
    expect(read).toHaveBeenCalledTimes(4);
    first.unmount();
    second.unmount();
  });
  it('retains old rows while a slow refresh is running and collects an unused cache in ten minutes', async () => {
    let complete!: (value: string) => void;
    const read = vi
      .fn()
      .mockResolvedValueOnce('old')
      .mockImplementation(
        () =>
          new Promise<string>((resolve) => {
            complete = resolve;
          }),
      );
    const view = renderHook(() => useBusinessQuery(domain, {}, read));
    await tick();
    await tick(90_010);
    expect(read).toHaveBeenCalledTimes(2);
    expect(view.result.current.data).toBe('old');
    act(() => complete('new'));
    await tick();
    expect(view.result.current.data).toBe('new');
    view.unmount();
    await tick(599_000);
    expect(queryClient.getQueryData(businessKey(testUser, domain, {}))).toBe('new');
    await tick(1_010);
    expect(queryClient.getQueryData(businessKey(testUser, domain, {}))).toBeUndefined();
  });
});
