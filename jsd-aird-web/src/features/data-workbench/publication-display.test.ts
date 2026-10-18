import { describe, expect, it } from 'vitest';
import { recordLabels, snapshotRows } from './publication-display';

describe('publication snapshot display', () => {
  it('exposes only active and disabled record states', () => {
    expect(Object.keys(recordLabels)).toEqual(['ACTIVE', 'DISABLED']);
  });
  it('uses physical source coordinates for structured fields rather than virtual binding columns', () => {
    const rows = snapshotRows({
      sheets: [{ sheetId: 'one', sheetName: '检测标准' }],
      rows: [{ id: 'r', sheetId: 'one', rowNumber: 1, normalizedValues: {
        APPROVER: { sourceColumn: 'virtual-binding', normalizedValue: '陈志远', fieldName: '核准' },
      }, sourceMetadata: { cells: { 'virtual-binding': { sheetName: '检测标准', cellAddress: 'I5' } } } }],
    });
    expect(rows[0]).toMatchObject({ field: '核准', effective: '陈志远', location: '检测标准 · I5' });
  });
});
