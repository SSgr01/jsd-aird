import type { TemplateBinding } from './types';
import { locatorValueRange } from './locator';
import { parseRange } from './structure-migration';

/** Protect calculated values without locking entire row/column structure operations. */
export function writesReadOnlyValue(bindings: TemplateBinding[], params?: object) {
  const input = params as { subUnitId?: string; cellValue?: Record<string, Record<string, unknown>> } | undefined;
  if (!input?.cellValue) return false;
  const ranges = bindings.filter(b => b.role === 'FIELD' && b.syncDirection === 'EDITOR_TO_DATA'
    && b.locator.sheetId === input.subUnitId).map(b => parseRange(locatorValueRange(b.locator))).filter(r => r !== undefined);
  return Object.entries(input.cellValue).some(([row, cells]) => Object.entries(cells).some(([column, value]) => {
    if (value && typeof value === 'object' && !['v', 'p', 'f'].some(key => key in value)) return false;
    return ranges.some(r => +row + 1 >= r.startRow && +row + 1 <= r.endRow && +column + 1 >= r.startColumn && +column + 1 <= r.endColumn);
  }));
}
