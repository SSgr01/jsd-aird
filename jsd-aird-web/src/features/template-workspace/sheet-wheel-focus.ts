/** Univer ignores wheel events while a surrounding form owns workbook focus.
 * Restore only the sheet canvas context, before Univer's own wheel listener;
 * leave toolbar inputs, popups and formula editors untouched. */
export function bindSheetWheelFocus(container: HTMLElement, focusWorkbook: () => void) {
  const focus = (event: WheelEvent) => {
    if (event.ctrlKey || !(event.target instanceof HTMLCanvasElement)
      || !event.target.id.startsWith('univer-sheet-main-canvas')) return;
    focusWorkbook();
  };
  container.addEventListener('wheel', focus, { capture: true, passive: true });
  return () => container.removeEventListener('wheel', focus, true);
}
