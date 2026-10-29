import { describe, expect, it } from 'vitest';
import type { BusinessField, FieldModel, TemplateBinding } from './types';
import { fieldNumberFormatEdits, fieldNumberFormat } from './field-number-format';

const field: BusinessField = { id: 'water', bindingId: 'water', name: '涂料含水', groupId: 'coating', kind: 'SCALAR', valueType: 'number', required: false, reviewStatus: 'CONFIRMED', unit: '', fieldOrigin: 'INSTANCE_LOCAL' };
const model = (fields: BusinessField[]): FieldModel => ({ modelVersion: 4, groups: [], fields, blocks: [], semanticAnnotations: [] });
const binding: TemplateBinding = { bindingId: field.id, dataPath: '/water', role: 'FIELD', locatorType: 'CELL_RANGE', syncDirection: 'TWO_WAY', primaryBinding: true, bindingStatus: 'VALID', locator: { sheetId: 'sheet', valueRange: 'C67:H67' } };
describe('field units and spreadsheet number formats', () => {
  it('initializes an inserted field independently of its percent-formatted neighbor', () => {
    const edits = fieldNumberFormatEdits(model([]), model([field]), [binding]);
    expect(edits).toEqual([{ field, binding, initialize: true, typeChanged: false }]);
    expect(fieldNumberFormat(field, true, '0.00%')).toBe('General');
    expect(fieldNumberFormat({ ...field, unit: '%' }, true, '0.00%')).toBe('General');
  });
  it('clears incompatible percent formatting when the unit is cleared or changed', () => {
    expect(fieldNumberFormatEdits(model([{ ...field, unit: '%' }]), model([field]), [binding])).toHaveLength(1);
    expect(fieldNumberFormat(field, false, '0.00%')).toBe('General');
    expect(fieldNumberFormat({ ...field, unit: 'g' }, false, '0.00%')).toBe('General');
    expect(fieldNumberFormat({ ...field, valueType: 'string', unit: '%' }, false, '0.00%')).toBe('@');
  });
  it('keeps established percentage fields and ordinary custom formats', () => {
    expect(fieldNumberFormatEdits(model([field]), model([{ ...field, name: '改名' }]), [binding])).toEqual([]);
    expect(fieldNumberFormat({ ...field, unit: '%' }, false, '0.00%')).toBe('0.00%');
    expect(fieldNumberFormat(field, false, '0.000')).toBe('0.000');
    expect(fieldNumberFormat(field, false, '0.00"%"')).toBe('0.00"%"');
    expect(fieldNumberFormat(field, false, '0.00\\%')).toBe('0.00\\%');
  });
  it('keeps a template version or a moved repeat record from resetting existing fields', () => {
    expect(fieldNumberFormatEdits(model([field]), model([field]), [{ ...binding, locator: { valueRange: 'C68:J68' } }])).toEqual([]);
    expect(fieldNumberFormatEdits(model([]), model([{ ...field, fieldOrigin: 'STANDARD' }]), [binding])).toEqual([]);
  });
  it.each([['string', '@'], ['number', 'General'], ['integer', 'General'], ['date', 'yyyy-mm-dd'], ['datetime', 'yyyy-mm-dd hh:mm:ss'], ['time', 'hh:mm:ss'], ['duration', 'General'], ['boolean', 'General']])('sets a suitable format when a field becomes %s', (type, format) => {
    expect(fieldNumberFormat({ ...field, valueType: type }, true, '0.00%')).toBe(format);
    expect(fieldNumberFormat({ ...field, valueType: type }, false, '@', true)).toBe(format);
  });
});
