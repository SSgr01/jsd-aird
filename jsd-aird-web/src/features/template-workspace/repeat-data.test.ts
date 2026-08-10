import { describe, expect, it } from 'vitest';

import { buildRepeatDisplay, displayCellValue } from './repeat-data';

describe('repeat display data', () => {
  it('groups sparse column arrays into populated rows', () => {
    const result = buildRepeatDisplay([
      { key: 'sequence', name: '序号', sequence: true, values: [null, null, null] },
      { key: 'material', name: '原料编号', values: [3213, 12312, null] },
      { key: 'quantity', name: '实际投料量', values: [null, null, null] },
    ], { maxRows: 15 });

    expect(result.filledRows).toBe(2);
    expect(result.totalRows).toBe(15);
    expect(result.rows).toHaveLength(2);
    expect(result.rows[0]?.values.material).toBe(3213);
    expect(result.rows[1]?.values.material).toBe(12312);
  });

  it('can expand trailing empty slots without rendering null values', () => {
    const result = buildRepeatDisplay([
      { key: 'material', name: '原料编号', values: [3213, null] },
    ], { maxRows: 3, expanded: true });

    expect(result.rows).toHaveLength(3);
    expect(displayCellValue(result.rows[1]?.values.material)).toBe('—');
    expect(displayCellValue(0)).toBe('0');
    expect(displayCellValue(false)).toBe('false');
  });

  it('does not treat prefilled sequence numbers as populated business rows', () => {
    const result = buildRepeatDisplay([
      { key: 'sequence', name: '序号', sequence: true, values: [1, 2, 3, 4] },
      { key: 'material', name: '原料编号', values: [3213, 12312, null, null] },
    ], { maxRows: 15 });

    expect(result.filledRows).toBe(2);
    expect(result.rows).toHaveLength(2);
    expect(result.totalRows).toBe(15);
  });
});
