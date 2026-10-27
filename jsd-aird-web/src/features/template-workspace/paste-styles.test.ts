import { describe, expect, it } from 'vitest';
import type { ICellDataWithSpanInfo } from '@univerjs/preset-sheets-core';
import type { ObjectMatrix } from '@univerjs/presets';
import { templatePasteStyleHook } from './paste-styles';

describe('template text paste styles', () => {
  const border = { b: { s: 1, cl: { rgb: '#555555' } } };
  const target = { unitId: 'book', subUnitId: 'sheet', range: { rows: [67, 68], cols: [2, 3] } };
  const matrix = (cell: ICellDataWithSpanInfo) => ({ forValue: (visit: (row: number, column: number, value: ICellDataWithSpanInfo) => void) => visit(1, 1, cell) }) as ObjectMatrix<ICellDataWithSpanInfo>;
  it('keeps target borders on multi-cell text pastes and preserves parsed percentage formats', () => {
    const hook = templatePasteStyleHook(() => ({ bd: border, ff: '宋体', fs: 11 }));
    const result = hook.onPasteCells!(null, target, matrix({ v: 0.6, s: { n: { pattern: '0%' } } }), { pasteType: 'default-paste' });
    expect(result.redos[0]?.params).toMatchObject({ cellValue: { 68: { 3: { s: { bd: border, ff: '宋体', n: { pattern: '0%' } } } } } });
    expect(result.undos[0]?.params).toMatchObject({ cellValue: { 68: { 3: { s: { bd: border, ff: '宋体' } } } } });
    expect(JSON.stringify(result.undos)).not.toContain('0%');
    expect(JSON.stringify(result.redos)).not.toContain('"v":');
  });
  it('keeps internal copy, explicit styles and paste-format behavior unchanged', () => {
    const hook = templatePasteStyleHook(() => ({ bd: border }));
    expect(hook.onPasteCells!(target, target, matrix({ v: '新值' }), { pasteType: 'default-paste' }).redos).toEqual([]);
    expect(hook.onPasteCells!(null, target, matrix({ v: '新值', s: { bg: { rgb: '#f00' } } }), { pasteType: 'default-paste' }).redos).toEqual([]);
    expect(hook.onPasteCells!(null, target, matrix({ v: '新值' }), { pasteType: 'special-paste-format' }).redos).toEqual([]);
  });
  it('pastes bare values as displayed percentage points without scaling explicit percentages twice', () => {
    const hook = templatePasteStyleHook(() => ({ bd: border, n: { pattern: '0.00%' } }));
    expect(hook.onPasteCells!(null, target, matrix({ v: 9 }), { pasteType: 'default-paste' }).redos[0]?.params).toMatchObject({ cellValue: { 68: { 3: { v: 0.09, s: { n: { pattern: '0.00%' } } } } } });
    expect(hook.onPasteCells!(null, target, matrix({ v: '9', s: { n: { pattern: 'General' } } }), { pasteType: 'default-paste' }).redos[0]?.params).toMatchObject({ cellValue: { 68: { 3: { v: 0.09, s: { n: { pattern: '0.00%' } } } } } });
    expect(hook.onPasteCells!(null, target, matrix({ v: 0.09, s: { n: { pattern: '0%' } } }), { pasteType: 'default-paste' }).redos[0]?.params).toMatchObject({ cellValue: { 68: { 3: { s: { n: { pattern: '0%' } } } } } });
    expect(JSON.stringify(hook.onPasteCells!(null, target, matrix({ v: 0.09, s: { n: { pattern: '0%' } } }), { pasteType: 'default-paste' }).redos)).not.toContain('"v":');
    expect(JSON.stringify(hook.onPasteCells!(null, target, matrix({ v: 9, s: { n: { pattern: '0.0"%"' } } }), { pasteType: 'default-paste' }).redos)).not.toContain('"v":');
    expect(hook.onPastePlainText!(target, '9', { pasteType: 'default-paste' }).redos[0]?.params).toMatchObject({ cellValue: { 67: { 2: { v: 0.09 } } } });
    expect(hook.onPastePlainText!(target, '9%', { pasteType: 'default-paste' }).redos).toEqual([]);
  });
});
