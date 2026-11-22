import { StrictMode } from 'react';
import { act, renderHook } from '@testing-library/react';
import { useLatestRequest } from '@/services/query/use-request-guard';

it('accepts only the latest response and invalidates reads before a mutation', () => {
  const { result } = renderHook(() => useLatestRequest('record'));
  const older = result.current.begin(), newer = result.current.begin();
  expect(older()).toBe(false); expect(newer()).toBe(true);
  result.current.invalidate(); expect(newer()).toBe(false);
  expect(result.current.begin()()).toBe(true);
});

it('rejects responses after A → B → A navigation and after unmount', () => {
  const { result, rerender, unmount } = renderHook(({ id }) => useLatestRequest(id), { initialProps: { id: 'A' } });
  const first = result.current.begin();
  rerender({ id: 'B' }); const second = result.current.begin();
  rerender({ id: 'A' }); const third = result.current.begin();
  expect(first()).toBe(false); expect(second()).toBe(false); expect(third()).toBe(true);
  act(() => unmount()); expect(third()).toBe(false);
});

it('remains usable after StrictMode lifecycle replay', () => {
  const { result, unmount } = renderHook(() => useLatestRequest('A'), { wrapper: StrictMode });
  const current = result.current.begin(); expect(current()).toBe(true);
  unmount(); expect(current()).toBe(false);
});
