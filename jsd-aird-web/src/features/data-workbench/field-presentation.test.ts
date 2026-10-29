import type { DataFieldValueView, DataWorkbookSnapshot } from '@/services/data/data-api';
import { createWorkbookInstance } from '@/features/template-workspace/workbook-instance';
import type { FieldModel, TemplateBinding } from '@/features/template-workspace/types';
import { presentWorkbookFields } from './field-presentation';

const model: FieldModel = {
  modelVersion: 5, blocks: [], semanticAnnotations: [],
  groups: [{ id: 'basic', name: '基础信息', order: 0 }, { id: 'resin', name: '树脂物性', order: 1 }, { id: 'recipe', name: '配方', order: 2 }],
  fields: [
    { id: 'title', bindingId: 'title', fieldCode: 'basic.title', groupId: 'basic', name: '实验题目', kind: 'SCALAR', valueType: 'string', required: false, reviewStatus: 'CONFIRMED' },
    { id: 'ph', bindingId: 'ph', fieldCode: 'resin.ph', groupId: 'resin', name: 'pH', kind: 'SCALAR', valueType: 'string', required: false, reviewStatus: 'CONFIRMED' },
    { id: 'total', bindingId: 'total', fieldCode: 'total', groupId: 'recipe', name: '合计', kind: 'SCALAR', valueType: 'number', required: false, reviewStatus: 'CONFIRMED', unit: 'g', experimentField: { domain: 'FORMULA', field: 'ACTUAL_QTY' } },
  ],
};
const mapping: TemplateBinding[] = [{ bindingId: 'samples', fieldCode: 'samples', dataPath: '/samples', role: 'REPEAT_REGION', mappingKind: 'REPEAT_REGION', repeatAxis: 'COLUMN', locatorType: 'TABLE_REGION', locator: { sheetId: 'sheet', dataRange: 'C1:H20' }, syncDirection: 'TWO_WAY', primaryBinding: true, bindingStatus: 'VALID' }];
const instance = createWorkbookInstance(model, mapping, {
  templateUsage: 'EXPERIMENT_DATA', identities: [], listProjections: [{ listProjectionId: 'materials', domain: 'FORMULA', componentId: 'samples', parentBindingId: 'samples', recordAxis: 'COLUMN', itemAxis: 'ROW', labelRange: 'B10:B12', valueRange: 'C10:H12', labelSemantic: 'MATERIAL_CODE', valueSemantic: 'ACTUAL_QTY' }],
});
const value = (bindingId: string, fieldCode: string, fieldName: string, address: string, rawValue: unknown): DataFieldValueView => ({
  bindingId, fieldCode, fieldName, address, sheetId: 'sheet', valuePath: '/', valueSource: 'INPUT', valueStatus: 'VALID', required: false, identity: false, trainingEligible: false, ragEligible: true, rawValue, effectiveValue: rawValue, editable: true, excluded: false,
});

describe('presentWorkbookFields', () => {
  it('uses configured Chinese groups for imported values and definitions, preserving pH and identities', () => {
    const workbook: DataWorkbookSnapshot = { fileName: 'record.xlsx', format: 'XLSX', snapshot: {}, editable: true, sheets: [], regions: [], records: [],
      fields: [value('ph', 'resin.ph', 'pH', 'C8', 7.6), value('title', 'basic.title', '实验题目', 'B1', '示例')],
      fieldDefinitions: [{ componentId: 'samples', bindingId: 'ph', fieldCode: 'resin.ph', displayName: 'pH', labelPath: 'resin > pH', groupPath: 'resin', required: false, identity: false }],
    };
    const original = structuredClone(workbook);
    const view = presentWorkbookFields(workbook, instance);
    expect(view.fields.map((f) => f.labelPath)).toEqual(['基础信息 > 实验题目', '树脂物性 > pH']);
    expect(view.fields[1]).toMatchObject({ bindingId: 'ph', rawValue: 7.6, address: 'C8', groupPath: '树脂物性' });
    expect(view.fieldDefinitions?.[0]?.groupPath).toBe('树脂物性');
    expect(workbook).toEqual(original);
  });

  it('groups list entries through existing projection semantics and retains material codes and repeated source rows', () => {
    const workbook: DataWorkbookSnapshot = { fileName: 'record.xlsx', format: 'XLSX', snapshot: {}, editable: true, sheets: [], regions: [], records: [], fields: [
      value('matrix-1', 'FORMULA.ACTUAL_QTY', 'SIM-DIW-001', 'C10', '420g'),
      value('matrix-2', 'FORMULA.ACTUAL_QTY', 'SIM-DIW-001', 'C12', 30),
      value('outside', 'FORMULA.ACTUAL_QTY', '其他区域', 'C18', 4),
    ] };
    const view = presentWorkbookFields(workbook, instance);
    expect(view.fields[0]).toMatchObject({ groupPath: '配方', labelPath: '配方 > SIM-DIW-001', unit: 'g', rawValue: '420g' });
    expect(view.fields[1]).toMatchObject({ bindingId: 'matrix-2', address: 'C12', rawValue: 30 });
    expect(view.fields[2]?.groupPath).toBeUndefined();
    expect(presentWorkbookFields(workbook)).toBe(workbook);
  });
});
