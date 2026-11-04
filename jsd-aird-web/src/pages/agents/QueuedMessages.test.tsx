import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueuedMessages } from './QueuedMessages';

const pointerCaptureDescriptor = Object.getOwnPropertyDescriptor(
  HTMLElement.prototype,
  'setPointerCapture',
);
beforeEach(() => {
  vi.spyOn(window, 'matchMedia').mockImplementation((query) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }));
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  if (pointerCaptureDescriptor)
    Object.defineProperty(HTMLElement.prototype, 'setPointerCapture', pointerCaptureDescriptor);
  else Reflect.deleteProperty(HTMLElement.prototype, 'setPointerCapture');
});
const items = [
  { id: 'first', role: 'user', text: '请列出实验条件' },
  { id: 'second', role: 'user', text: '再比较两种方法' },
];

it('edits through the main composer and offers cancellation and steering directly', () => {
  const onChange = vi.fn().mockResolvedValue(true);
  const onRestore = vi.fn().mockResolvedValue(undefined);
  render(
    <QueuedMessages
      items={items}
      busy={false}
      canSteer
      onRestore={onRestore}
      onChange={onChange}
    />,
  );
  expect(screen.getByText('当前任务完成后依次处理')).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '编辑消息：请列出实验条件' }));
  expect(onRestore).toHaveBeenCalledWith(items[0]);
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '立即补充：请列出实验条件' }));
  expect(onChange).toHaveBeenCalledWith({ action: 'promote', message_id: 'first' });
  fireEvent.click(screen.getByRole('button', { name: '取消发送：再比较两种方法' }));
  expect(onChange).toHaveBeenCalledWith({ action: 'delete', message_id: 'second' });
});

it('protects an existing composer draft and blocks edits while a request is pending', () => {
  const onRestore = vi.fn().mockResolvedValue(undefined);
  const props = { items, busy: false, canSteer: false, onChange: vi.fn(), onRestore };
  const { rerender } = render(<QueuedMessages {...props} canRestore={false} />);
  const edit = screen.getByRole('button', { name: '编辑消息：请列出实验条件' });
  expect(edit).toBeDisabled();
  fireEvent.click(edit);
  expect(onRestore).not.toHaveBeenCalled();
  rerender(<QueuedMessages {...props} canRestore />);
  fireEvent.click(edit);
  expect(onRestore).toHaveBeenCalledTimes(1);
  rerender(<QueuedMessages {...props} busy canRestore />);
  expect(edit).toBeDisabled();
  fireEvent.click(edit);
  expect(onRestore).toHaveBeenCalledTimes(1);
});

it('keeps only sorting in the more menu and preserves keyboard sorting', async () => {
  const onChange = vi.fn().mockResolvedValue(true);
  render(<QueuedMessages items={items} busy={false} canSteer onChange={onChange} />);
  fireEvent.click(screen.getByRole('button', { name: '更多操作：请列出实验条件' }));
  expect(await screen.findByRole('menuitem', { name: '上移' })).toHaveAttribute(
    'aria-disabled',
    'true',
  );
  expect(
    screen.queryByRole('menuitem', { name: /放回输入框|编辑|取消发送/ }),
  ).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('menuitem', { name: '下移' }));
  expect(onChange).toHaveBeenLastCalledWith({ action: 'move', message_id: 'first', position: 1 });
  await waitFor(() => expect(screen.queryByText('正在保存顺序…')).not.toBeInTheDocument());
  fireEvent.keyDown(screen.getByRole('button', { name: '调整顺序：请列出实验条件' }), {
    key: 'ArrowUp',
  });
  expect(onChange).toHaveBeenLastCalledWith({ action: 'move', message_id: 'first', position: 0 });
  await waitFor(() => expect(screen.queryByText('正在保存顺序…')).not.toBeInTheDocument());
});

it('explains paused delivery and omits empty queues and unnecessary sorting menus', () => {
  const onChange = vi.fn();
  const { rerender } = render(
    <QueuedMessages
      items={items.slice(0, 1)}
      busy={false}
      canSteer={false}
      paused
      onChange={onChange}
    />,
  );
  expect(screen.getByText('已暂停，输入“继续”后接着处理')).toBeVisible();
  expect(screen.queryByRole('button', { name: /立即补充|更多操作/ })).not.toBeInTheDocument();
  rerender(<QueuedMessages items={[]} busy={false} canSteer={false} onChange={onChange} />);
  expect(screen.queryByRole('region', { name: '待处理消息' })).not.toBeInTheDocument();
});

function prepareDrag(heights = [48, 48]) {
  class TestPointerEvent extends MouseEvent {
    pointerId: number;
    isPrimary: boolean;
    constructor(type: string, init: PointerEventInit = {}) {
      super(type, init);
      this.pointerId = init.pointerId ?? 1;
      this.isPrimary = init.isPrimary ?? true;
    }
  }
  vi.stubGlobal('PointerEvent', TestPointerEvent);
  Object.defineProperty(HTMLElement.prototype, 'setPointerCapture', {
    configurable: true,
    value: vi.fn(),
  });
  let nextFrame: FrameRequestCallback | undefined;
  let frameTime = 0;
  vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
    nextFrame = callback;
    return 1;
  });
  vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(() => {
    nextFrame = undefined;
  });
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (
    this: HTMLElement,
  ) {
    const heightFor = (row: HTMLElement) => heights[row.dataset.queueId === 'first' ? 0 : 1] || 48;
    const siblings = this.dataset.queueId
      ? (Array.from(this.parentElement!.children) as HTMLElement[])
      : [];
    const top = siblings
      .slice(0, siblings.indexOf(this))
      .reduce((sum, row) => sum + heightFor(row), 0);
    const height = this.dataset.queueId
      ? heightFor(this)
      : heights.reduce((sum, item) => sum + item, 0);
    const offset = Number(/translate3d\(0, ([\d.-]+)px/.exec(this.style.transform)?.[1] || 0);
    return {
      top: top + offset,
      bottom: top + offset + height,
      height,
      left: 0,
      right: 600,
      width: 600,
      x: 0,
      y: top + offset,
      toJSON: () => ({}),
    };
  });
  return () =>
    act(() => {
      const callback = nextFrame;
      nextFrame = undefined;
      frameTime += 16;
      callback?.(frameTime);
    });
}

const queueIds = () =>
  Array.from(document.querySelectorAll<HTMLElement>('[data-queue-id]')).map(
    (row) => row.dataset.queueId,
  );

it('can move a taller message past a shorter last row', async () => {
  const tick = prepareDrag([96, 48]);
  const onChange = vi.fn().mockResolvedValue(true);
  render(<QueuedMessages items={items} busy={false} canSteer={false} onChange={onChange} />);
  const handle = screen.getByRole('button', { name: '调整顺序：请列出实验条件' });
  fireEvent.pointerDown(handle, { clientY: 24 });
  fireEvent.pointerMove(handle, { clientY: 68 });
  tick();
  fireEvent.pointerUp(handle, { clientY: 68 });
  expect(onChange).toHaveBeenCalledWith({ action: 'move', message_id: 'first', position: 1 });
  await waitFor(() => expect(screen.queryByText('正在保存顺序…')).not.toBeInTheDocument());
  expect(queueIds()).toEqual(['second', 'first']);
});

it('follows the pointer, makes room before dropping, and accepts later authoritative orders', async () => {
  const tick = prepareDrag();
  let finish!: (result: boolean) => void;
  const pending = new Promise<boolean>((resolve) => {
    finish = resolve;
  });
  const onChange = vi.fn(() => pending);
  const props = { busy: false, canSteer: false, onChange };
  const { rerender } = render(<QueuedMessages {...props} items={items} />);
  const handle = screen.getByRole('button', { name: '调整顺序：请列出实验条件' });
  fireEvent.pointerDown(handle, { clientY: 24 });
  fireEvent.pointerMove(handle, { clientY: 72 });
  tick();
  expect(handle.closest('li')).toHaveStyle({ transform: 'translate3d(0, 48px, 0)' });
  expect(document.querySelector('[data-queue-id="second"]')).toHaveStyle({
    transform: 'translate3d(0, -48px, 0)',
  });
  expect(onChange).not.toHaveBeenCalled();
  fireEvent.pointerUp(handle, { clientY: 72 });
  expect(queueIds()).toEqual(['second', 'first']);
  expect(screen.getByText('正在保存顺序…')).toBeVisible();
  expect(onChange).toHaveBeenCalledTimes(1);
  expect(onChange).toHaveBeenCalledWith({ action: 'move', message_id: 'first', position: 1 });
  await act(async () => {
    finish(true);
    await pending;
  });
  expect(queueIds()).toEqual(['second', 'first']);
  rerender(<QueuedMessages {...props} items={[...items].reverse()} />);
  rerender(<QueuedMessages {...props} items={items} />);
  expect(queueIds()).toEqual(['first', 'second']);
});

it('continues scrolling near the edge without requiring more pointer events and stops on cancel', async () => {
  const tick = prepareDrag();
  const onChange = vi.fn().mockResolvedValue(true);
  render(
    <QueuedMessages
      items={[...items, { id: 'third', role: 'user', text: '保留依据' }]}
      busy={false}
      canSteer={false}
      onChange={onChange}
    />,
  );
  const list = screen.getByRole('list');
  Object.defineProperties(list, { scrollHeight: { value: 144 }, clientHeight: { value: 96 } });
  const handle = screen.getByRole('button', { name: '调整顺序：请列出实验条件' });
  fireEvent.pointerDown(handle, { clientY: 24 });
  fireEvent.pointerMove(handle, { clientY: 92 });
  tick();
  const firstScroll = list.scrollTop;
  tick();
  expect(list.scrollTop).toBeGreaterThan(firstScroll);
  fireEvent.pointerCancel(handle);
  const stopped = list.scrollTop;
  tick();
  expect(list.scrollTop).toBe(stopped);
  expect(onChange).not.toHaveBeenCalled();
  await act(async () => {
    await Promise.resolve();
  });
});

it('rolls back a rejected move and accepts a newer authoritative queue', async () => {
  prepareDrag();
  let finish!: (result: boolean) => void;
  let pending!: Promise<boolean>;
  const onChange = vi.fn(() => {
    pending = new Promise<boolean>((resolve) => {
      finish = resolve;
    });
    return pending;
  });
  const props = { busy: false, canSteer: false, onChange };
  const { rerender } = render(<QueuedMessages {...props} items={items} />);
  fireEvent.keyDown(screen.getByRole('button', { name: '调整顺序：再比较两种方法' }), {
    key: 'ArrowUp',
  });
  expect(queueIds()).toEqual(['second', 'first']);
  await act(async () => {
    finish(false);
    await pending;
  });
  expect(queueIds()).toEqual(['first', 'second']);
  fireEvent.keyDown(screen.getByRole('button', { name: '调整顺序：再比较两种方法' }), {
    key: 'ArrowUp',
  });
  rerender(<QueuedMessages {...props} items={[items[1]!]} />);
  await act(async () => {
    finish(true);
    await pending;
  });
  expect(queueIds()).toEqual(['second']);
});

it('cancels dragging with Escape or a live queue change without submitting a stale move', async () => {
  const tick = prepareDrag();
  const onChange = vi.fn().mockResolvedValue(true);
  const props = { busy: false, canSteer: false, onChange };
  const { rerender } = render(<QueuedMessages {...props} items={items} />);
  const handle = screen.getByRole('button', { name: '调整顺序：请列出实验条件' });
  fireEvent.pointerDown(handle, { clientY: 24 });
  fireEvent.pointerMove(handle, { clientY: 72 });
  tick();
  fireEvent.keyDown(handle, { key: 'Escape' });
  fireEvent.pointerUp(handle, { clientY: 72 });
  expect(queueIds()).toEqual(['first', 'second']);
  expect(handle.closest('li')?.style.transform).toBe('');
  fireEvent.pointerDown(handle, { clientY: 24 });
  fireEvent.pointerMove(handle, { clientY: 72 });
  tick();
  rerender(<QueuedMessages {...props} items={[...items].reverse()} />);
  fireEvent.pointerUp(handle, { clientY: 72 });
  expect(onChange).not.toHaveBeenCalled();
  expect(queueIds()).toEqual(['second', 'first']);
  await act(async () => {
    await Promise.resolve();
  });
});
