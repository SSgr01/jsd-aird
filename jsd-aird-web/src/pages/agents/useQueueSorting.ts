import { useEffect, useLayoutEffect, useRef, useState, type PointerEvent } from 'react';
import type { ConversationEntry, QueueChange } from '@/services/agents/agent-api';

interface DragRow {
  id: string;
  element: HTMLElement;
  top: number;
  height: number;
}

interface Drag {
  id: string;
  pointerId: number;
  startY: number;
  y: number;
  scrollTop: number;
  order: string;
  rows: DragRow[];
  index: number;
  active: boolean;
  lastFrame?: number;
}

export function useQueueSorting(
  items: ConversationEntry[],
  busy: boolean,
  onChange: (change: QueueChange) => Promise<boolean>,
) {
  const listRef = useRef<HTMLOListElement>(null);
  const drag = useRef<Drag>();
  const frame = useRef<number>();
  const before = useRef<Map<string, number>>();
  const beforeScroll = useRef(0);
  const animations = useRef<Animation[]>([]);
  const savingRef = useRef(false);
  const [saving, setSaving] = useState(false);
  const [draggingId, setDraggingId] = useState<string>();
  const [optimistic, setOptimistic] = useState<{ base: string; ids: string[] }>();
  const order = JSON.stringify(items.map((item) => item.id));
  const current = useRef({ busy, order });
  current.current = { busy, order };
  const byId = new Map(items.map((item) => [item.id, item]));
  const displayed = optimistic?.base === order ? optimistic.ids.map((id) => byId.get(id)!) : items;

  const rows = () =>
    Array.from(listRef.current?.querySelectorAll<HTMLElement>(':scope > li') || []);
  const rememberPositions = () => {
    beforeScroll.current = listRef.current?.scrollTop || 0;
    before.current = new Map(
      rows().map((row) => [row.dataset.queueId!, row.getBoundingClientRect().top]),
    );
  };
  const stopFrame = () => {
    if (frame.current !== undefined) cancelAnimationFrame(frame.current);
    frame.current = undefined;
  };
  const cancelDrag = () => {
    if (drag.current?.active) rememberPositions();
    drag.current = undefined;
    stopFrame();
    setDraggingId(undefined);
  };

  // FLIP from the actual on-screen positions, including a partially completed
  // neighbour animation. Reordering or rolling back never snaps to a new row.
  useLayoutEffect(() => {
    const positions = before.current;
    if (!positions || drag.current?.active) return;
    before.current = undefined;
    animations.current.forEach((animation) => animation.cancel());
    animations.current = [];
    const elements = rows();
    elements.forEach((row) => row.style.removeProperty('transform'));
    if (listRef.current) listRef.current.scrollTop = beforeScroll.current;
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    elements.forEach((row) => {
      const previous = positions.get(row.dataset.queueId!);
      const offset = previous === undefined ? 0 : previous - row.getBoundingClientRect().top;
      if (!reduceMotion && Math.abs(offset) > 0.5 && row.animate) {
        animations.current.push(
          row.animate(
            [
              { transform: `translate3d(0, ${offset}px, 0)` },
              { transform: 'translate3d(0, 0, 0)' },
            ],
            { duration: 180, easing: 'cubic-bezier(0.2, 0.8, 0.2, 1)' },
          ),
        );
      }
    });
  });

  useLayoutEffect(() => {
    if (drag.current && (busy || drag.current.order !== order)) cancelDrag();
  }, [busy, order]);

  useLayoutEffect(() => {
    if (optimistic && optimistic.base !== order) setOptimistic(undefined);
  }, [optimistic, order]);

  useEffect(
    () => () => {
      stopFrame();
      animations.current.forEach((animation) => animation.cancel());
    },
    [],
  );

  const move = async (id: string, position: number) => {
    const index = displayed.findIndex((item) => item.id === id);
    if (
      busy ||
      savingRef.current ||
      index < 0 ||
      index === position ||
      position < 0 ||
      position >= displayed.length
    )
      return;
    const ids = displayed.map((item) => item.id);
    ids.splice(position, 0, ids.splice(index, 1)[0]!);
    rememberPositions();
    savingRef.current = true;
    setSaving(true);
    setOptimistic({ base: order, ids });
    try {
      if (!(await onChange({ action: 'move', message_id: id, position }))) {
        rememberPositions();
        setOptimistic(undefined);
      }
    } catch {
      rememberPositions();
      setOptimistic(undefined);
    } finally {
      savingRef.current = false;
      setSaving(false);
    }
  };

  const paint = (timestamp: number, autoScroll = true) => {
    frame.current = undefined;
    const session = drag.current;
    const list = listRef.current;
    if (!session?.active || !list) return;
    if (current.current.busy || current.current.order !== session.order) {
      cancelDrag();
      return;
    }
    const bounds = list.getBoundingClientRect();
    const edge = Math.min(36, bounds.height / 3);
    const proximity =
      edge > 0
        ? session.y < bounds.top + edge
          ? -Math.min(1, (bounds.top + edge - session.y) / edge)
          : Math.max(0, Math.min(1, (session.y - bounds.bottom + edge) / edge))
        : 0;
    const elapsed = Math.min(32, timestamp - (session.lastFrame ?? timestamp - 16));
    session.lastFrame = timestamp;
    const previousScroll = list.scrollTop;
    if (autoScroll && list.scrollHeight > list.clientHeight)
      list.scrollTop += (proximity * 480 * elapsed) / 1000;
    const active = session.rows.find((row) => row.id === session.id)!;
    const first = session.rows[0]!;
    const last = session.rows.at(-1)!;
    const offset = Math.max(
      first.top - active.top,
      Math.min(
        last.top + last.height - active.top - active.height,
        session.y - session.startY + list.scrollTop - session.scrollTop,
      ),
    );
    const originalIndex = session.rows.indexOf(active);
    session.index = originalIndex;
    let distance = 0;
    const direction = offset < 0 ? -1 : 1;
    for (
      let index = originalIndex + direction;
      index >= 0 && index < session.rows.length;
      index += direction
    ) {
      const height = session.rows[index]!.height;
      distance += height;
      if (Math.abs(offset) < distance - height / 2) break;
      session.index = index;
    }
    const others = session.rows.filter((row) => row.id !== session.id);
    others.splice(session.index, 0, active);
    let top = first.top;
    for (const row of others) {
      const shift = row.id === session.id ? offset : top - row.top;
      row.element.style.transform = `translate3d(0, ${shift}px, 0)`;
      top += row.height;
    }
    if (autoScroll && list.scrollTop !== previousScroll)
      frame.current = requestAnimationFrame((time) => paint(time));
  };

  const startDrag = (event: PointerEvent<HTMLElement>, id: string) => {
    if (busy || savingRef.current || items.length < 2 || event.button !== 0 || !event.isPrimary)
      return;
    event.preventDefault();
    event.currentTarget.focus({ preventScroll: true });
    event.currentTarget.setPointerCapture(event.pointerId);
    animations.current.forEach((animation) => animation.cancel());
    const list = listRef.current!;
    const top = list.getBoundingClientRect().top;
    drag.current = {
      id,
      pointerId: event.pointerId,
      startY: event.clientY,
      y: event.clientY,
      scrollTop: list.scrollTop,
      order,
      index: displayed.findIndex((item) => item.id === id),
      active: false,
      rows: rows().map((element) => {
        const rect = element.getBoundingClientRect();
        return {
          id: element.dataset.queueId!,
          element,
          top: rect.top - top + list.scrollTop,
          height: rect.height,
        };
      }),
    };
  };

  const updateDrag = (event: PointerEvent<HTMLElement>) => {
    const session = drag.current;
    if (!session || session.pointerId !== event.pointerId) return;
    session.y = event.clientY;
    if (!session.active && Math.abs(session.y - session.startY) < 4) return;
    if (!session.active) {
      session.active = true;
      setDraggingId(session.id);
    }
    if (frame.current === undefined) frame.current = requestAnimationFrame((time) => paint(time));
  };

  const endDrag = (event: PointerEvent<HTMLElement>) => {
    const session = drag.current;
    if (!session || session.pointerId !== event.pointerId) return;
    stopFrame();
    session.y = event.clientY;
    if (session.active) paint(performance.now(), false);
    if (drag.current !== session) return;
    const position = session.index;
    const active = session.active;
    cancelDrag();
    if (active) void move(session.id, position);
  };

  return {
    listRef,
    displayed,
    draggingId,
    saving,
    startDrag,
    updateDrag,
    endDrag,
    cancelDrag,
    move,
  };
}
