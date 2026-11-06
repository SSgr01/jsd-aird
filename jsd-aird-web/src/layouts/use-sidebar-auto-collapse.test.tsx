import { act, fireEvent, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SIDEBAR_IDLE_MS, useSidebarAutoCollapse } from './use-sidebar-auto-collapse';

beforeEach(() => { vi.useFakeTimers(); });
afterEach(() => {
  document.body.classList.remove('workspace-is-maximized');
  document.querySelector('[data-idle-test]')?.remove();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

function menu() {
  const wrapper = document.createElement('div');
  wrapper.dataset.idleTest = '';
  wrapper.innerHTML = '<aside class="ant-pro-sider"><button data-sidebar-toggle>导航</button><a href="#">项目列表</a></aside><button data-content>保存</button>';
  document.body.append(wrapper);
  return wrapper;
}

describe('sidebar idle collapse', () => {
  it('collapses once after 30 seconds and cancels the timer on unmount', () => {
    const collapse = vi.fn();
    const { unmount } = renderHook(() => useSidebarAutoCollapse(false, collapse));
    act(() => { vi.advanceTimersByTime(SIDEBAR_IDLE_MS - 1); });
    expect(collapse).not.toHaveBeenCalled();
    act(() => { vi.advanceTimersByTime(1); });
    expect(collapse).toHaveBeenCalledExactlyOnceWith(true);
    unmount();
    expect(vi.getTimerCount()).toBe(0);
  });

  it.each(['pointerdown', 'pointermove', 'keydown', 'wheel', 'focusin'])('restarts the countdown on menu %s', type => {
    const wrapper = menu(), collapse = vi.fn();
    renderHook(() => useSidebarAutoCollapse(false, collapse));
    act(() => { vi.advanceTimersByTime(20_000); });
    fireEvent(wrapper.querySelector('a')!, new Event(type, { bubbles: true }));
    act(() => { vi.advanceTimersByTime(29_999); });
    expect(collapse).not.toHaveBeenCalled();
    act(() => { vi.advanceTimersByTime(1); });
    expect(collapse).toHaveBeenCalledWith(true);
  });

  it('keeps the page controls usable without extending menu idle time', () => {
    const wrapper = menu(), collapse = vi.fn();
    renderHook(() => useSidebarAutoCollapse(false, collapse));
    act(() => { vi.advanceTimersByTime(20_000); });
    const save = wrapper.querySelector<HTMLButtonElement>('[data-content]')!;
    save.focus(); fireEvent.pointerDown(save);
    act(() => { vi.advanceTimersByTime(10_000); });
    expect(collapse).toHaveBeenCalledWith(true);
    expect(document.activeElement).toBe(save);
  });

  it('starts a fresh countdown after manual expansion', () => {
    const collapse = vi.fn();
    const { rerender } = renderHook(({ collapsed }) => useSidebarAutoCollapse(collapsed, collapse), { initialProps: { collapsed: false } });
    act(() => { vi.advanceTimersByTime(20_000); });
    rerender({ collapsed: true });
    act(() => { vi.advanceTimersByTime(30_000); });
    expect(collapse).not.toHaveBeenCalled();
    rerender({ collapsed: false });
    act(() => { vi.advanceTimersByTime(30_000); });
    expect(collapse).toHaveBeenCalledExactlyOnceWith(true);
  });

  it('pauses while the workspace is full screen and restarts on restore', async () => {
    const collapse = vi.fn();
    renderHook(() => useSidebarAutoCollapse(false, collapse));
    await act(async () => {
      document.body.classList.add('workspace-is-maximized');
      await Promise.resolve();
    });
    act(() => { vi.advanceTimersByTime(60_000); });
    expect(collapse).not.toHaveBeenCalled();
    await act(async () => {
      document.body.classList.remove('workspace-is-maximized');
      await Promise.resolve();
    });
    act(() => { vi.advanceTimersByTime(29_999); });
    expect(collapse).not.toHaveBeenCalled();
    act(() => { vi.advanceTimersByTime(1); });
    expect(collapse).toHaveBeenCalledWith(true);
  });

  it('pauses in a background tab and retains a safe keyboard focus on collapse', () => {
    const wrapper = menu(), collapse = vi.fn();
    const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(true);
    renderHook(() => useSidebarAutoCollapse(false, collapse));
    act(() => { vi.advanceTimersByTime(60_000); });
    expect(collapse).not.toHaveBeenCalled();
    hidden.mockReturnValue(false);
    fireEvent(document, new Event('visibilitychange'));
    wrapper.querySelector('a')!.focus();
    act(() => { vi.advanceTimersByTime(30_000); });
    expect(collapse).toHaveBeenCalledWith(true);
    expect(document.activeElement).toBe(wrapper.querySelector('[data-sidebar-toggle]'));
  });
});
