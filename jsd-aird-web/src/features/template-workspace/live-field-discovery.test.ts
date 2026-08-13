import { describe, expect, it } from 'vitest';

import {
  isCellMutationCommand,
  isNewFieldLabelChange,
  isSingleCellAddress,
  isStructuredDataCell,
  potentialFieldLabel,
} from './live-field-discovery';

describe('live field discovery guards', () => {
  it('accepts a single ordinary cell, including a same-cell range', () => {
    expect(isSingleCellAddress('B4')).toBe(true);
    expect(isSingleCellAddress('$B$4:$B$4')).toBe(true);
    expect(isSingleCellAddress('B4:C4')).toBe(false);
  });

  it('normalizes a typed label without treating data as a field', () => {
    expect(potentialFieldLabel('客户批号')).toBe('客户批号');
    expect(potentialFieldLabel('客户批号：')).toBe('客户批号');
    expect(potentialFieldLabel('123')).toBeUndefined();
    expect(potentialFieldLabel('=A1')).toBeUndefined();
    expect(potentialFieldLabel('https://example.com')).toBeUndefined();
    expect(potentialFieldLabel('客户批号：123')).toBeUndefined();
    expect(potentialFieldLabel(['客户批号'])).toBeUndefined();
    expect(potentialFieldLabel({ v: '物料名称' })).toBe('物料名称');
    expect(potentialFieldLabel({ v: 123 })).toBeUndefined();
  });

  it('creates a field only for empty-to-text edits and treats label edits as renames', () => {
    expect(isNewFieldLabelChange('', '物料名称')).toBe(true);
    expect(isNewFieldLabelChange({ v: '' }, { v: '物料名称' })).toBe(true);
    expect(isNewFieldLabelChange('物料名称', '产品名称')).toBe(false);
    expect(isNewFieldLabelChange({ v: '物料名称' }, { v: '产品名称' })).toBe(false);
    expect(isNewFieldLabelChange('', '')).toBe(false);
  });

  it('recognizes Univer workbook value mutation commands', () => {
    expect(isCellMutationCommand('sheet.command.set-range-values')).toBe(true);
    expect(isCellMutationCommand('sheet.mutation.set-range-values')).toBe(true);
    expect(isCellMutationCommand('sheet.command.set-cell-value')).toBe(true);
    expect(isCellMutationCommand('sheet.command.set-range-format')).toBe(false);
    expect(isCellMutationCommand('sheet.command.set-cell-edit')).toBe(true);
    expect(isCellMutationCommand('sheet.command.set-range-data', {
      value: { v: '物料名称' },
      range: { startRow: 0, startColumn: 0, endRow: 0, endColumn: 0 },
    })).toBe(true);
    expect(isCellMutationCommand('sheet.command.unknown', {
      value: { v: '物料名称' },
      range: { startRow: 0, startColumn: 0, endRow: 0, endColumn: 0 },
    })).toBe(true);
  });

  it('does not discover short business values inside a repeat data range', () => {
    expect(isStructuredDataCell(
      { sheetId: 'sheet-1', address: 'A2' },
      [{
        mappingKind: 'REPEAT_REGION',
        locator: { sheetId: 'sheet-1', dataRange: 'A2:F200', headerRange: 'A1:F1' },
      }],
      [],
    )).toBe(true);
    expect(isStructuredDataCell(
      { sheetId: 'sheet-1', address: 'A1' },
      [{
        mappingKind: 'REPEAT_REGION',
        locator: { sheetId: 'sheet-1', dataRange: 'A2:F200', headerRange: 'A1:F1' },
      }],
      [],
    )).toBe(false);
  });
});
