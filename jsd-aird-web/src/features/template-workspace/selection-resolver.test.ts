import { describe, expect, it } from 'vitest';

import type { TemplateBinding } from './types';
import { resolveBindingSelection } from './selection-resolver';

function binding(id: string, locator: Record<string, unknown>): TemplateBinding {
  return {
    bindingId: id,
    dataPath: `/${id}`,
    role: 'FIELD',
    locatorType: 'CELL_RANGE',
    locator: { sheetId: 'sheet-1', sheetName: 'Sheet1', ...locator },
    syncDirection: 'TWO_WAY',
    primaryBinding: true,
    bindingStatus: 'VALID',
  };
}

describe('resolveBindingSelection', () => {
  const selection = { sheetId: 'sheet-1', sheetName: 'Sheet1', address: 'B3' };

  it('prioritizes a merged label over value regions', () => {
    const result = resolveBindingSelection([
      binding('large', { address: 'A1:J20' }),
      binding('label', { labelAddress: 'A3', labelRange: 'A3:C3', address: 'D3' }),
    ], { ...selection, address: 'B3' });

    expect(result?.binding.bindingId).toBe('label');
    expect(result?.hitKind).toBe('LABEL');
  });

  it('uses the smallest value region and cycles stable ties', () => {
    const bindings = [
      binding('large', { address: 'A1:J20' }),
      binding('small-a', { address: 'B3:C4' }),
      binding('small-b', { address: 'B3:C4' }),
    ];

    expect(resolveBindingSelection(bindings, selection, 0)?.binding.bindingId).toBe('small-a');
    expect(resolveBindingSelection(bindings, selection, 1)?.binding.bindingId).toBe('small-b');
  });

  it('never falls back to a different sheet', () => {
    expect(resolveBindingSelection([
      binding('other', { sheetId: 'sheet-2', sheetName: 'Sheet2', address: 'B3' }),
    ], selection)).toBeUndefined();
  });
});
