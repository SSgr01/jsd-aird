import { describe, expect, it } from 'vitest';
import { focusScrollDelta, sourceFocusBinding } from './source-focus';
import { createWorkbookInstance } from './workbook-instance';
import type { FieldModel, TemplateBinding } from './types';

function binding(id: string, locator: Record<string, unknown>, kind: TemplateBinding['mappingKind'] = 'REPEAT_FIELD'): TemplateBinding {
  return { bindingId: id, fieldCode: id, dataPath: `/${id}`, role: kind === 'REPEAT_REGION' ? 'REPEAT_REGION' : 'FIELD', mappingKind: kind,
    locatorType: 'CELL_RANGE', locator: { sheetId: 'sheet', sheetName: '合成实验', ...locator },
    syncDirection: 'TWO_WAY', bindingStatus: 'VALID', primaryBinding: true };
}
const selection = { sheetId: 'sheet', sheetName: '合成实验', address: 'H8' };

describe('minimal workbook scrolling', () => {
  const viewport = { left: 200, right: 800, top: 100, bottom: 400 };
  it('keeps both scrollbars unchanged when the cell is already fully visible', () => {
    expect(focusScrollDelta({ startX: 650, endX: 750, startY: 120, endY: 170 }, viewport))
      .toEqual({ offsetX: 0, offsetY: 0 });
  });
  it('reveals the rightmost sample without pinning it to the left edge', () => {
    expect(focusScrollDelta({ startX: 900, endX: 1000, startY: 120, endY: 170 }, viewport))
      .toEqual({ offsetX: 208, offsetY: 0 });
  });
  it('reveals the full bottom cell rather than leaving its contents clipped', () => {
    expect(focusScrollDelta({ startX: 300, endX: 400, startY: 390, endY: 440 }, viewport))
      .toEqual({ offsetX: 0, offsetY: 48 });
  });
  it('reveals cells above or left and respects frozen panes independently', () => {
    const cell = { startX: 100, endX: 180, startY: 40, endY: 80 };
    expect(focusScrollDelta(cell, viewport)).toEqual({ offsetX: -108, offsetY: -68 });
    expect(focusScrollDelta(cell, viewport, { x: true, y: false })).toEqual({ offsetX: 0, offsetY: -68 });
    expect(focusScrollDelta(cell, viewport, { x: false, y: true })).toEqual({ offsetX: -108, offsetY: 0 });
  });
  it('keeps an oversized merged cell anchored at its beginning', () => {
    expect(focusScrollDelta({ startX: 300, endX: 1200, startY: 500, endY: 1000 }, viewport))
      .toEqual({ offsetX: 0, offsetY: 400 });
  });
  it('does not clip the other edge when a merged cell nearly fills the viewport', () => {
    expect(focusScrollDelta({ startX: 300, endX: 895, startY: 110, endY: 410 }, viewport))
      .toEqual({ offsetX: 100, offsetY: 10 });
  });
});

describe('shared workbook source focus', () => {
  it('focuses sample six without selecting the whole field or inheriting stale canonical value aliases', () => {
    const original = binding('content', { address: 'C8:H8', valueRange: 'C8:H8', logicalInputRange: 'C8:H8',
      value: { address: 'C8', range: 'C8:H8' }, anchorRange: 'C8:H8', labelRange: 'A8:B8' });
    const before = structuredClone(original);
    const focus = sourceFocusBinding([original], selection);
    expect(focus).toMatchObject({ bindingId: 'content', locator: { address: 'H8', range: 'H8', valueRange: 'H8', logicalInputRange: 'H8',
      anchorRange: 'H8', dataRange: 'H8', value: { address: 'H8', range: 'H8' }, labelRange: 'A8:B8' } });
    expect(original).toEqual(before);
  });

  it('uses each material row from the existing list projection instead of the enclosing comparison region', () => {
    const parent = binding('comparison', { dataRange: 'C7:H54' }, 'REPEAT_REGION');
    const model: FieldModel = { modelVersion: 5, groups: [], fields: [], blocks: [], semanticAnnotations: [] };
    const instance = createWorkbookInstance(model, [parent], { templateUsage: 'EXPERIMENT_DATA', identities: [], listProjections: [
      { listProjectionId: 'materials', domain: 'FORMULA', componentId: 'comparison', parentBindingId: 'comparison',
        recordAxis: 'COLUMN', itemAxis: 'ROW', labelRange: 'B10:B23', valueRange: 'C10:H23', labelSemantic: 'MATERIAL_CODE', valueSemantic: 'ACTUAL_QTY' },
    ] });
    expect(sourceFocusBinding([parent], { ...selection, address: 'H17' }, instance)).toMatchObject({
      bindingId: 'list:materials:valueRange', locator: { valueRange: 'H17', rowHeaderRange: 'B17' },
    });
    expect(sourceFocusBinding([parent], { ...selection, address: 'B17' }, instance)).toMatchObject({
      bindingId: 'list:materials:labelRange', locator: { valueRange: 'B17' },
    });
  });

  it('keeps column labels and narrows row/column header context to the selected record', () => {
    const field = binding('measurement', { valueRange: 'C10:H99', labelRange: 'C9:H9',
      rowHeaderRange: 'A10:B99', columnHeaderRange: 'C7:H8' });
    expect(sourceFocusBinding([field], { ...selection, address: 'H17:H18' })?.locator).toMatchObject({
      valueRange: 'H17:H18', labelRange: 'C9:H9', rowHeaderRange: 'A17:B18', columnHeaderRange: 'H7:H8',
    });
  });

  it('does not borrow a field from another sheet or highlight an unrelated enclosing region', () => {
    const other = binding('other', { sheetId: 'other', valueRange: 'H8', labelRange: 'G8' });
    const parent = binding('comparison', { dataRange: 'A1:Z200' }, 'REPEAT_REGION');
    const focus = sourceFocusBinding([other, parent], selection);
    expect(focus?.locator).toMatchObject({ sheetId: 'sheet', valueRange: 'H8' });
    expect(focus?.locator.labelRange).toBeUndefined();
    expect(focus?.bindingId).not.toBe('comparison');
  });

  it('resolves generic templates with canonical nested coordinates', () => {
    const field = binding('nested', { value: { range: 'C8:H8' }, label: { range: 'A8:B8' } });
    expect(sourceFocusBinding([field], selection)).toMatchObject({ bindingId: 'nested',
      locator: { valueRange: 'H8', labelRange: 'A8:B8' } });
  });

  it('supports unbound recognition ranges and rejects invalid source coordinates', () => {
    expect(sourceFocusBinding([], { ...selection, address: '$j$5:$L$6' })?.locator.valueRange).toBe('J5:L6');
    for (const address of ['bad', 'A0', 'A8:A7'])
      expect(sourceFocusBinding([], { ...selection, address })).toBeUndefined();
  });
});
