import { describe, expect, it } from 'vitest';
import { sectionMergeExtensions, migratedWorkbookMerges } from './section-merges';
import type { InstanceSheet } from './workbook-instance';
import type { WorkbookStructureOperation } from './types';

const sheet: InstanceSheet = { id: 's', name: '表', cellData: { '50': { '0': { v: '配方', s: 'title' } } }, mergeData: [
  { startRow: 50, endRow: 59, startColumn: 0, endColumn: 0 },
  { startRow: 60, endRow: 62, startColumn: 0, endColumn: 0 },
] };
const insert = (index: number): WorkbookStructureOperation => ({ operationId: 'op', source: 'CUSTOMER', sheetId: 's', type: 'INSERT_ROWS', index, count: 2 });
describe('merged section insertion', () => {
  it('keeps appended list items under their configured heading when the subtotal belongs visually to the next group', () => {
    expect(migratedWorkbookMerges(sheet, insert(61), false, [{ range: 'B52:B60', name: '配方' }])).toEqual([
      { startRow: 50, endRow: 61, startColumn: 0, endColumn: 0 },
      { startRow: 62, endRow: 64, startColumn: 0, endColumn: 0 },
    ]);
  });
  it('moves neighbouring sections and keeps exact geometry for structural undo checkpoints', () => {
    expect(migratedWorkbookMerges(sheet, insert(61), true)).toEqual([
      { startRow: 50, endRow: 61, startColumn: 0, endColumn: 0 },
      { startRow: 62, endRow: 64, startColumn: 0, endColumn: 0 },
    ]);
    expect(sheet.mergeData?.[1]?.startRow).toBe(60);
  });
  it('extends the selected recipe at its tail without absorbing the next section', () => {
    expect(sectionMergeExtensions(sheet, insert(61), true)).toEqual([{ range: 'A51:A62', anchor: 'A51' }]);
  });
  it('uses the next section when inserting above its first row and retains its heading', () => {
    expect(sectionMergeExtensions(sheet, insert(61), false)).toEqual([{ range: 'A61:A65', anchor: 'A61', cell: undefined }]);
    expect(sectionMergeExtensions(sheet, insert(51), false)).toEqual([{ range: 'A51:A62', anchor: 'A51', cell: { v: '配方', s: 'title' } }]);
  });
  it('keeps an interior insertion in its original section and ignores layout gaps', () => {
    expect(sectionMergeExtensions(sheet, insert(55), false)[0]?.range).toBe('A51:A62');
    expect(sectionMergeExtensions(sheet, insert(70), false)).toEqual([]);
  });
});
