import { describe, expect, it, vi } from 'vitest';

import { createCustomFieldWorkspace } from './custom-field-operations';
import type { FieldModel, TemplateBinding } from './types';

vi.stubGlobal('crypto', { randomUUID: () => '11111111-1111-4111-8111-111111111111' });

const model: FieldModel = {
  modelVersion: 4,
  groups: [{ id: 'g', name: '明细', order: 0 }],
  fields: [{
    id: 'parent', bindingId: 'parent-binding', dataPath: '/materials', fieldCode: 'MATERIALS',
    groupId: 'g', name: '物料', kind: 'ROW_TABLE', valueType: 'object', required: false,
    reviewStatus: 'CONFIRMED', mappingKind: 'REPEAT_REGION', repeatAxis: 'ROW',
    regionId: 'region-materials', blockId: 'block-materials',
    recordHeight: 1, recordWidth: 4, recordStride: 1,
  }],
  blocks: [], semanticAnnotations: [], staticRegions: [],
};
const parentBinding: TemplateBinding = {
  bindingId: 'parent-binding', dataPath: '/materials', role: 'REPEAT_REGION',
  mappingKind: 'REPEAT_REGION', repeatAxis: 'ROW', recordHeight: 1, recordWidth: 4,
  recordStride: 1, locatorType: 'CELL_RANGE', locator: { dataRange: 'A2:D10' },
  syncDirection: 'TWO_WAY', primaryBinding: true, bindingStatus: 'VALID',
};

describe('createCustomFieldWorkspace', () => {
  it('creates a real repeat child under its parent record path', () => {
    const result = createCustomFieldWorkspace(
      { type: 'object', properties: {} }, model, [parentBinding],
      {
        ownerId: 'template-1', origin: 'TEMPLATE_LOCAL', kind: 'REPEAT_FIELD', name: '批号',
        parentField: model.fields[0], parentBinding, sheet: { sheetId: 's1', sheetName: 'Sheet1' },
        labelRange: 'E1', valueRange: 'E2:E10',
      },
    );
    expect(result.field.dataPath).toBe('/materials/*/field_11111111111141118111111111111111');
    expect(result.binding.parentBindingId).toBe('parent-binding');
    expect(result.binding.mappingKind).toBe('REPEAT_FIELD');
    expect(result.binding.locator.valueMode).toBe('ARRAY_COLUMN');
    expect(result.field.parentFieldId).toBe('parent');
    expect(result.field.regionId).toBe('region-materials');
    expect(result.field.blockId).toBe('block-materials');
    expect(result.field.reviewStatus).toBe('CONFIRMED');
    expect(result.model.fields.find((field) => field.id === 'parent')?.columns).toHaveLength(1);
  });

  it('keeps a structured field pending until its value range is configured', () => {
    const result = createCustomFieldWorkspace(
      { type: 'object', properties: {} }, model, [parentBinding],
      {
        ownerId: 'template-1', origin: 'TEMPLATE_LOCAL', kind: 'MATRIX_FIELD', name: '指标',
        parentField: model.fields[0], parentBinding,
      },
    );
    expect(result.field.mappingKind).toBe('MATRIX_FIELD');
    expect(result.field.matrixRole).toBe('MEASURE');
    expect(result.field.reviewStatus).toBe('NEEDS_CONFIRMATION');
    expect(result.binding.bindingStatus).toBe('MISSING');
    expect(result.binding.diagnostic).toMatchObject({ parentFieldId: 'parent', parentBindingId: 'parent-binding' });
  });
});
