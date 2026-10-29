import type { ISheetClipboardHook } from '@univerjs/preset-sheets-core';
import type { ICellData, IStyleData } from '@univerjs/presets';
import { percentageFormat } from '@/features/data-workbench/cell-format';

/** A text-only paste must not replace a template's borders, font or alignment.
 * Return style-only mutations so the SDK includes the repair in its paste undo. */
export function templatePasteStyleHook(readStyle: (sheetId: string, row: number, column: number) => IStyleData | null | void): ISheetClipboardHook {
  return {
    id: 'jsd-template-paste-styles',
    // Univer 1.2 executes clipboard hooks in ascending priority order.
    priority: 100000,
    onPastePlainText(pasteTo, text, payload) {
      const row = pasteTo.range.rows[0], column = pasteTo.range.cols[0];
      if (payload.pasteType !== 'default-paste' || row === undefined || column === undefined
        || !/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?$/i.test(text.trim())) return { undos: [], redos: [] };
      const style = readStyle(pasteTo.subUnitId, row, column);
      if (!style || !percentageFormat(style.n?.pattern ?? '') || !Number.isFinite(Number(text))) return { undos: [], redos: [] };
      const params = { unitId: pasteTo.unitId, subUnitId: pasteTo.subUnitId };
      return {
        undos: [{ id: 'sheet.mutation.set-range-values', params: { ...params, cellValue: { [row]: { [column]: { s: structuredClone(style) } } } } }],
        redos: [{ id: 'sheet.mutation.set-range-values', params: { ...params, cellValue: { [row]: { [column]: { v: Number(text) / 100, s: structuredClone(style), f: null, p: null } } } } }],
      };
    },
    onPasteCells(pasteFrom, pasteTo, cells, payload) {
      if (pasteFrom || payload.pasteType !== 'default-paste') return { undos: [], redos: [] };
      const cellValue: Record<number, Record<number, ICellData>> = {};
      const beforeStyles: Record<number, Record<number, ICellData>> = {};
      cells.forValue((row, column, cell) => {
        // Explicitly formatted HTML and internal copy/paste keep their own styles.
        const incoming = cell.s;
        if (incoming && (typeof incoming !== 'object' || Object.keys(incoming).some(key => key !== 'n'))) return;
        const incomingStyle = incoming && typeof incoming === 'object' ? incoming : {};
        const targetRow = pasteTo.range.rows[row], targetColumn = pasteTo.range.cols[column];
        if (targetRow === undefined || targetColumn === undefined) return;
        const style = readStyle(pasteTo.subUnitId, targetRow, targetColumn);
        if (!style) return;
        (beforeStyles[targetRow] ??= {})[targetColumn] = { s: structuredClone(style) };
        const merged: ICellData = { s: { ...structuredClone(style), ...incomingStyle } };
        // Plain clipboard numbers represent the number the operator entered.
        // An explicit percentage already carries Excel's fractional storage value.
        const plainNumber = typeof cell.v === 'number' ? cell.v : typeof cell.v === 'string'
          && /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?$/i.test(cell.v.trim()) ? Number(cell.v) : undefined;
        if (plainNumber !== undefined && percentageFormat(style.n?.pattern ?? '')
          && (!incomingStyle.n || incomingStyle.n.pattern === 'General') && Number.isFinite(plainNumber)) {
          merged.v = plainNumber / 100;
          merged.s = { ...structuredClone(style), ...incomingStyle, n: structuredClone(style.n) };
        }
        (cellValue[targetRow] ??= {})[targetColumn] = merged;
      });
      if (!Object.keys(cellValue).length) return { undos: [], redos: [] };
      const mutation = { id: 'sheet.mutation.set-range-values', params: { unitId: pasteTo.unitId, subUnitId: pasteTo.subUnitId, cellValue } };
      return { undos: [{ ...mutation, params: { ...mutation.params, cellValue: beforeStyles } }], redos: [mutation] };
    },
  };
}
