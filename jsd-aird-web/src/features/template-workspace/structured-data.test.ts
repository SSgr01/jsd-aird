import { describe, expect, it } from 'vitest';

import type { TemplateBinding } from './types';
import { synchronizeStructuredData } from './structured-data';

function binding(
  bindingId: string,
  dataPath: string,
  mappingKind: TemplateBinding['mappingKind'],
  parentBindingId?: string,
): TemplateBinding {
  return {
    bindingId,
    dataPath,
    role: mappingKind?.endsWith('_REGION') ? 'REPEAT_REGION' : 'FIELD',
    mappingKind,
    parentBindingId,
    locatorType: 'CELL_RANGE',
    locator: { address: 'A1' },
    syncDirection: 'TWO_WAY',
    primaryBinding: true,
    bindingStatus: 'VALID',
  };
}

describe('synchronizeStructuredData', () => {
  it('assembles repeat child columns into business records and skips the raw parent grid', () => {
    const parent = binding('parent', '/materials', 'REPEAT_REGION');
    const code = binding('code', '/materials/*/code', 'REPEAT_FIELD', parent.bindingId);
    const quantity = binding('quantity', '/materials/*/quantity', 'REPEAT_FIELD', parent.bindingId);
    const editorValues: Record<string, unknown> = {
      parent: [['A', 10], ['B', 20]],
      code: ['A', 'B'],
      quantity: [10, 20],
    };

    const result = synchronizeStructuredData({}, [parent, code, quantity], (item) =>
      editorValues[item.bindingId]);

    expect(result.data).toEqual({
      materials: [
        { code: 'A', quantity: 10 },
        { code: 'B', quantity: 20 },
      ],
    });
    expect(result.bindingValues.map((item) => item.dataPath)).toEqual([
      '/materials/*/code',
      '/materials/*/quantity',
    ]);
  });

  it('normalizes a matrix parent into stable member records when it has no semantic children', () => {
    const parent = binding('matrix', '/matrix', 'MATRIX_REGION');
    const result = synchronizeStructuredData({}, [parent], () => [[1, 2], [3, 4]]);
    expect(result.data).toEqual({
      matrix: [
        { _member: { slotId: 'matrix:ROW:0' }, value: [1, 2] },
        { _member: { slotId: 'matrix:ROW:1' }, value: [3, 4] },
      ],
    });
  });

  it('keeps the existing value when a binding cannot be read', () => {
    const result = synchronizeStructuredData(
      { order: { code: 'existing' } },
      [binding('code', '/order/code', 'SCALAR')],
      () => undefined,
    );

    expect(result.data).toEqual({ order: { code: 'existing' } });
    expect(result.bindingValues).toEqual([]);
  });
});
