import { describe, expect, it } from 'vitest';
import { resultBinding } from './result-location';
import type { TemplateBinding } from './types';
const binding: TemplateBinding = { bindingId: 'f', fieldCode: 'f', dataPath: '/value', role: 'FIELD', mappingKind: 'SCALAR', locatorType: 'CELL_RANGE', locator: { sheetId: 's', address: 'H10', value: { range: 'H10' }, labelRange: 'B10' }, syncDirection: 'TWO_WAY', primaryBinding: true, bindingStatus: 'VALID' };
describe('result locations', () => {
  it('projects all aliases without changing the original binding', () => {
    const before = structuredClone(binding);
    const projected = resultBinding(binding, {}, { rows: { 9: 4 }, columns: { 1: 1, 7: 3 } });
    expect(projected?.locator).toMatchObject({ address: 'D5', value: { range: 'D5' }, labelRange: 'B5' });
    expect(binding).toEqual(before);
  });
  it('returns no target for removed fields', () => {
    expect(resultBinding(binding, {}, { rows: {}, columns: { 7: 3 } })).toBeUndefined();
  });
  it('uses stable field markers for stacked shared rows', () => {
    const snapshot = { sheets: { s: { id: 's', cellData: { 2: { 1: { custom: { lab: { fieldCode: 'f', role: 'VALUE' } } } } } } } };
    expect(resultBinding(binding, snapshot, { rows: {}, columns: {} })?.locator.address).toBe('B3');
  });
});
