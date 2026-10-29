import { describe, expect, it } from 'vitest';
import { writesReadOnlyValue } from './read-only-values';
import type { TemplateBinding } from './types';
const binding: TemplateBinding = { bindingId: 'f', dataPath: '/total', role: 'FIELD', syncDirection: 'EDITOR_TO_DATA', primaryBinding: true, bindingStatus: 'VALID', locatorType: 'CELL_RANGE', locator: { sheetId: 's', address: 'C60:H60' } };
describe('read-only value guard', () => {
  it('blocks values and clears at calculated coordinates, including sparse multi-cell paste', () => {
    expect(writesReadOnlyValue([binding], { subUnitId: 's', cellValue: { 59: { 2: { v: 1 } } } })).toBe(true);
    expect(writesReadOnlyValue([binding], { subUnitId: 's', cellValue: { 59: { 3: null } } })).toBe(true);
  });
  it('allows formatting, input values, other sheets and structural commands', () => {
    for (const params of [{ subUnitId: 's', cellValue: { 59: { 2: { s: 'style' } } } }, { subUnitId: 's', cellValue: { 58: { 2: { v: 1 } } } }, { subUnitId: 'other', cellValue: { 59: { 2: { v: 1 } } } }, { subUnitId: 's', range: { startColumn: 2, endColumn: 3 } }]) expect(writesReadOnlyValue([binding], params)).toBe(false);
  });
});
