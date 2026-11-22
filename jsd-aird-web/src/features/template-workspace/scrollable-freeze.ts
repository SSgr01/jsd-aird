interface Freeze { startRow: number; startColumn: number; xSplit: number; ySplit: number }

/** Normalize before Univer builds frozen viewports; oversized legacy freezes can
 * otherwise initialize an inactive main viewport that never receives wheels. */
export function workbookWithScrollableFreeze(snapshot: Record<string, unknown>, viewport: { width: number; height: number }) {
  const sheets = { ...(snapshot.sheets as Record<string, Record<string, unknown>> ?? {}) };
  for (const [id, sheet] of Object.entries(sheets)) {
    const freeze = sheet.freeze as Freeze | undefined;
    if (!freeze || (!freeze.xSplit && !freeze.ySplit)) continue;
    const zoom = Number(sheet.zoomRatio) || 1;
    const rows = sheet.rowData as Record<number, { h?: number; ah?: number; hd?: number }> | undefined;
    const columns = sheet.columnData as Record<number, { w?: number; hd?: number }> | undefined;
    let height = 0;
    let width = 0;
    for (let row = Math.max(0, freeze.startRow - freeze.ySplit); row < freeze.startRow && height < viewport.height; row++) {
      const data = rows?.[row];
      height += data?.hd ? 0 : (data?.h ?? data?.ah ?? (Number(sheet.defaultRowHeight) || 20)) * zoom;
    }
    for (let column = Math.max(0, freeze.startColumn - freeze.xSplit); column < freeze.startColumn && width < viewport.width; column++) {
      const data = columns?.[column];
      width += data?.hd ? 0 : (data?.w ?? (Number(sheet.defaultColumnWidth) || 73)) * zoom;
    }
    sheets[id] = { ...sheet, freeze: scrollableFreeze(freeze, viewport, { width, height }) };
  }
  return { ...snapshot, sheets };
}

/** Leave at least half a small viewport (up to 80px) for scrollable cells. */
export function scrollableFreeze(freeze: Freeze, viewport: { width: number; height: number },
  frozen: { width: number; height: number }): Freeze {
  const next = { ...freeze };
  if (freeze.ySplit > 0 && frozen.height >= viewport.height - Math.min(80, viewport.height / 2)) {
    next.ySplit = 0;
    next.startRow = -1;
  }
  if (freeze.xSplit > 0 && frozen.width >= viewport.width - Math.min(80, viewport.width / 2)) {
    next.xSplit = 0;
    next.startColumn = -1;
  }
  return next;
}
