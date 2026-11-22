import { act, renderHook } from '@testing-library/react';
import { usePolling } from './use-polling';

afterEach(() => vi.useRealTimers());

it('does not overlap slow refreshes and schedules the next refresh after completion', async () => {
  vi.useFakeTimers();
  let resolve!: () => void;
  const refresh = vi.fn(() => new Promise<void>((done) => { resolve = done; }));
  const { unmount } = renderHook(() => usePolling(refresh, true, 1000));
  await act(() => vi.advanceTimersByTimeAsync(10000));
  expect(refresh).toHaveBeenCalledTimes(1);
  await act(() => { resolve(); return Promise.resolve(); });
  await act(() => vi.advanceTimersByTimeAsync(999)); expect(refresh).toHaveBeenCalledTimes(1);
  await act(() => vi.advanceTimersByTimeAsync(1)); expect(refresh).toHaveBeenCalledTimes(2);
  unmount(); await act(() => { resolve(); return Promise.resolve(); });
  await vi.advanceTimersByTimeAsync(10000); expect(refresh).toHaveBeenCalledTimes(2);
});

it('continues after a transient rejection and stops when disabled', async () => {
  vi.useFakeTimers();
  const refresh = vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue(undefined);
  const { rerender } = renderHook(({ enabled }) => usePolling(refresh, enabled, 1000), { initialProps: { enabled: true } });
  await act(() => vi.advanceTimersByTimeAsync(2000)); expect(refresh).toHaveBeenCalledTimes(2);
  rerender({ enabled: false });
  await act(() => vi.advanceTimersByTimeAsync(10000)); expect(refresh).toHaveBeenCalledTimes(2);
});
