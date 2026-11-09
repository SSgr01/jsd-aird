// Coalesce adjacent text only. Lifecycle/tool events flush the text first so
// gates, checkpoints and completion keep their original durable order.
export function textEvents(enqueue, { intervalMs = 24, maxChars = 2048 } = {}) {
  let pending, timer, firstText = true;
  const flush = () => {
    clearTimeout(timer);
    timer = undefined;
    if (pending) {
      const event = pending;
      pending = undefined;
      enqueue(event);
    }
  };
  return {
    flush,
    push(event) {
      if (event.type !== 'message.delta') {
        flush();
        if (event.type === 'message.started') firstText = true;
        enqueue(event);
        return;
      }
      if (firstText) {
        firstText = false;
        enqueue(event);
        return;
      }
      if (pending?.payload.id !== event.payload.id) flush();
      if (pending) pending.payload.delta += event.payload.delta;
      else pending = { ...event, payload: { ...event.payload } };
      if (pending.payload.delta.length >= maxChars) flush();
      else if (timer === undefined) timer = setTimeout(flush, intervalMs);
    },
  };
}
