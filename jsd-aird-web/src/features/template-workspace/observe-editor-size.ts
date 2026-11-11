/** Resize the live canvas after panel, viewport or workspace geometry changes. */
export function observeEditorSize(container: HTMLElement, resize: () => void) {
  let frame = 0;
  const schedule = () => {
    cancelAnimationFrame(frame);
    frame = requestAnimationFrame(resize);
  };
  const observer = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(schedule);
  observer?.observe(container);
  window.addEventListener('resize', schedule);
  return () => {
    observer?.disconnect();
    cancelAnimationFrame(frame);
    window.removeEventListener('resize', schedule);
  };
}
