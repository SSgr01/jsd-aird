import { describe, expect, it } from 'vitest';
import { instanceWorkbookView } from './instance-workbook-view';
import { workbookCellInput, workbookCellValue } from './cell-format';
import { createWorkbookInstance } from '@/features/template-workspace/workbook-instance';
import type { BusinessField, FieldModel, TemplateBinding } from '@/features/template-workspace/types';

function shown(type: string, raw: unknown, pattern = 'General') {
  const field: BusinessField = { id: 'f', bindingId: 'f', fieldCode: 'LOCAL.f', name: '验收字段', groupId: 'g', kind: 'SCALAR', valueType: type, required: false, reviewStatus: 'CONFIRMED' };
  const model: FieldModel = { modelVersion: 4, groups: [{ id: 'g', name: '检测', order: 0 }], fields: [field], blocks: [], semanticAnnotations: [] };
  const binding: TemplateBinding = { bindingId: 'f', fieldCode: 'LOCAL.f', dataPath: '/f', role: 'FIELD', locatorType: 'CELL_RANGE', syncDirection: 'TWO_WAY', primaryBinding: true, bindingStatus: 'VALID', locator: { sheetId: 's', address: 'B2', valueRange: 'B2' } };
  return instanceWorkbookView({ styles: { style: { n: { pattern } } }, sheets: { s: { id: 's', name: '表', cellData: { 1: { 1: { v: raw, s: 'style' } } } } } }, createWorkbookInstance(model, [binding]), true).fields[0]!.effectiveValue;
}
describe('workbench field format matrix', () => {
  it.each([['number', 0], ['integer', 0], ['boolean', false], ['string', '00123'], ['string', '8 s / 2#杯'], ['string', '轻微发白'], ['string', '>600 s']])('retains %s values including zero, false and mixed text', (type, value) => {
    expect(shown(String(type), value)).toBe(value);
  });
  it('scales actual percentage formats and keeps quoted or escaped percent literals', () => {
    expect(shown('number', 0.09, '0.00%')).toBe(9);
    expect(shown('number', 9, '0.00"%"')).toBe(9);
    expect(shown('number', 9, '0.00\\%')).toBe(9);
  });
  it('round trips entered percentage points without scaling quoted or escaped literals', () => {
    for (const input of [43, '43', '43%', '43％']) {
      const stored = workbookCellInput(input, '0.00%');
      expect(stored).toBe(0.43);
      expect(workbookCellValue(stored, 'number', '0.00%')).toBe(43);
    }
    expect(workbookCellInput(0, '0%')).toBe(0);
    expect(workbookCellInput('43', '0.00"%"')).toBe(43);
    expect(workbookCellInput('43', '0.00\\%')).toBe(43);
    expect(workbookCellInput('43%', '0.00')).toBe('43%');
    expect(workbookCellInput('未测', '0.00%')).toBe('未测');
    expect(workbookCellInput(0.43, '0.00%', true)).toBe(0.43);
  });
  it('preserves date and datetime components without timezone drift', () => {
    const day = (Date.UTC(2026, 9, 26) - Date.UTC(1899, 11, 30)) / 86400000;
    expect(shown('date', day, 'yyyy-mm-dd')).toBe('2026-10-26');
    expect(shown('datetime', day + 0.5, 'yyyy-mm-dd hh:mm:ss')).toBe('2026-10-26 12:00:00');
    expect(shown('time', 0.5, 'hh:mm:ss')).toBe('12:00:00');
  });
  it('keeps elapsed durations beyond twenty-four hours', () => {
    expect(shown('duration', 1.5, '[h]:mm:ss')).toBe('36:00:00');
  });
});
