import { describe, expect, it } from 'vitest';

import type { DataFieldValueView } from '@/services/data/data-api';
import { importFieldForCell, importFieldInSheetRow, normalizeImportCellAddress } from './data-import-workbench-utils';

const field = (overrides: Partial<DataFieldValueView> = {}): DataFieldValueView => ({
  recordId: 'record-1',
  fieldCode: 'MATERIAL.NAME',
  fieldName: '产品名称',
  bindingId: 'binding-1',
  valuePath: '/material/name',
  valueSource: 'USER_INPUT',
  valueStatus: 'VALID',
  required: false,
  identity: false,
  trainingEligible: true,
  ragEligible: true,
  sheetId: 'sheet-1',
  sheetName: 'Sheet1',
  rowNumber: 2,
  address: 'B2',
  editable: true,
  excluded: false,
  ...overrides,
});

describe('data import workbench cell identity', () => {
  it('normalizes absolute and one-cell range addresses', () => {
    expect(normalizeImportCellAddress('$b$2')).toBe('B2');
    expect(normalizeImportCellAddress('B2:B2')).toBe('B2');
  });

  it('matches a field by sheet name when the editor sheet id differs', () => {
    expect(importFieldForCell([field()], {
      sheetId: 'univer-generated-id', sheetName: 'Sheet1', address: '$B$2',
    })).toEqual(field());
  });

  it('keeps row fallback constrained to the same sheet', () => {
    const other = field({ sheetId: 'sheet-2', sheetName: 'Other', rowNumber: 2, address: 'C2' });
    expect(importFieldInSheetRow([other, field()], {
      sheetId: 'sheet-1', sheetName: 'Sheet1', address: 'A2',
    }, 2)).toEqual(field());
  });
});
