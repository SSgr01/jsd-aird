import { act, renderHook } from '@testing-library/react';
import { advanceSessionEpoch } from '@/services/http/auth-events';
import { useRequestGuard } from './use-request-guard';

it('cancels an older read and makes late success/error/finally callbacks lose ownership', () => {
  const hook = renderHook(() => useRequestGuard('list?page=1'));
  const first = hook.result.current();
  const second = hook.result.current();
  expect(first.signal.aborted).toBe(true);
  expect(first.current()).toBe(false);
  expect(second.current()).toBe(true);
});
it('invalidates ownership when filters change before the next response arrives', () => {
  const hook = renderHook(({ scope }) => useRequestGuard(scope), {
    initialProps: { scope: 'filter=A' },
  });
  const first = hook.result.current();
  hook.rerender({ scope: 'filter=B' });
  expect(first.current()).toBe(false);
  expect(first.signal.aborted).toBe(true);
  expect(hook.result.current().current()).toBe(true);
});
it('invalidates requests on unmount and on session or permission changes', () => {
  const hook = renderHook(() => useRequestGuard('permissions'));
  const first = hook.result.current();
  act(() => advanceSessionEpoch());
  expect(first.current()).toBe(false);
  const second = hook.result.current();
  hook.unmount();
  expect(second.current()).toBe(false);
  expect(second.signal.aborted).toBe(true);
});
it('keeps independent category and list requests from cancelling each other', () => {
  const hook = renderHook(() => ({
    list: useRequestGuard('list'),
    categories: useRequestGuard('categories'),
  }));
  const list = hook.result.current.list();
  const categories = hook.result.current.categories();
  expect(list.current()).toBe(true);
  expect(categories.current()).toBe(true);
});
