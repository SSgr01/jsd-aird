import type { TemplateBinding } from './types';
import { locatorLabelRange, locatorValueRange, synchronizeLocatorCoordinates } from './locator';
import { formatRange, parseRange } from './structure-migration';

/** A display-only projection. Never write these locators into the complete instance. */
export function resultBinding(binding: TemplateBinding, snapshot: Record<string, unknown>, locationMap: Record<string, unknown>) {
  const rows = locationMap.rows as Record<string, number> | undefined;
  const columns = locationMap.columns as Record<string, number> | undefined;
  const project = (address: string) => {
    const range = parseRange(address);
    if (!range || !rows || !columns) return '';
    const rr = Object.entries(rows).filter(([r]) => +r >= range.startRow - 1 && +r <= range.endRow - 1).map(([, r]) => r + 1);
    const cc = Object.entries(columns).filter(([c]) => +c >= range.startColumn - 1 && +c <= range.endColumn - 1).map(([, c]) => c + 1);
    return rr.length && cc.length ? formatRange({ startRow: Math.min(...rr), endRow: Math.max(...rr), startColumn: Math.min(...cc), endColumn: Math.max(...cc) }) : '';
  };
  const sheets = snapshot.sheets as Record<string, { id: string; name: string; cellData?: Record<string, Record<string, { custom?: { lab?: { fieldCode?: string; role?: string } } }>> }> | undefined;
  const sheet = Object.values(sheets ?? {}).find((s) => s.id === binding.locator.sheetId || s.name === binding.locator.sheetName);
  let valueRange = project(locatorValueRange(binding.locator));
  let labelRange = project(locatorLabelRange(binding.locator));
  // Shared experiment information can be stacked by the result projector. Its stable code is more precise than row maps.
  const values: Array<{ r: number; c: number }> = [];
  for (const [row, cells] of Object.entries(sheet?.cellData ?? {})) for (const [column, cell] of Object.entries(cells)) {
    const meta = cell.custom?.lab;
    if (!meta || meta.fieldCode !== binding.fieldCode) continue;
    const address = formatRange({ startRow: +row + 1, endRow: +row + 1, startColumn: +column + 1, endColumn: +column + 1 });
    if (meta.role === 'VALUE') values.push({ r: +row + 1, c: +column + 1 });
    if (meta.role === 'LABEL') labelRange = address;
  }
  if (values.length) valueRange = formatRange({ startRow: Math.min(...values.map(v => v.r)), endRow: Math.max(...values.map(v => v.r)), startColumn: Math.min(...values.map(v => v.c)), endColumn: Math.max(...values.map(v => v.c)) });
  if (!valueRange) return undefined;
  const locator = synchronizeLocatorCoordinates(binding.locator, { address: valueRange, labelAddress: labelRange });
  for (const key of ['rowHeaderRange', 'headerRange', 'dataRange', 'totalRange', 'identityRange']) {
    if (typeof locator[key] === 'string') locator[key] = project(locator[key]);
  }
  return { ...binding, locator };
}
