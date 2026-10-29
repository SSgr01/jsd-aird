import { describe, expect, it } from 'vitest';
import { createWorkbookInstance, writeWorkbookInstance } from './workbook-instance';
import { normalizeInstanceDefinition, resolveInstanceDefinition } from './instance-definition';
import type { FieldModel, TemplateBinding } from './types';
const model: FieldModel = { modelVersion: 1, groups: [{ id: 'g', name: '测试结果', order: 0 }], blocks: [], semanticAnnotations: [], fields: [
  { id: 'f', bindingId: 'f', name: '黏度', groupId: 'g', kind: 'SCALAR', valueType: 'number', unit: 'cP', required: true, reviewStatus: 'CONFIRMED' },
] };
const mapping: TemplateBinding[] = [{ bindingId: 'f', fieldCode: 'f', dataPath: '/samples/*/value', role: 'FIELD', mappingKind: 'REPEAT_FIELD', locatorType: 'CELL_RANGE', locator: { sheetId: 's', address: 'C12:H12' }, syncDirection: 'TWO_WAY', primaryBinding: true, bindingStatus: 'VALID' }];
describe('instance definition source', () => {
  it('uses the business group rather than a geometry root in paths of local fields', () => {
    const instance = createWorkbookInstance(model, mapping);
    instance.model.fields.unshift({ id: 'root', name: '实验对照组', groupId: 'g', kind: 'COLUMN_TABLE', valueType: 'array', required: false, reviewStatus: 'CONFIRMED', structuralOnly: true });
    instance.model.fields[1]!.parentFieldId = 'root';
    instance.model.fields[1]!.fieldOrigin = 'INSTANCE_LOCAL';
    instance.model.fields[1]!.pathSegments = ['实验对照组', '黏度'];
    expect(normalizeInstanceDefinition(instance, {}).model.fields.find(f => f.id === 'f')?.pathSegments).toEqual(['测试结果', '黏度']);
  });
  it('uses template normalization without losing types, units or required flags', () => {
    const value = normalizeInstanceDefinition(createWorkbookInstance(model, mapping), {});
    expect(value.model.fields[0]).toMatchObject({ name: '黏度', groupId: 'g', fieldType: 'TABLE_COLUMN', valueType: 'number', unit: 'cP', required: true });
    expect(value.model.groups.map(g => g.name)).toEqual(['测试结果']);
  });
  it('prefers saved instance extensions over the fallback version definition without mutating the source', () => {
    const saved = createWorkbookInstance(model, mapping), fallback = structuredClone(saved);
    saved.model.fields[0]!.name = '本次扩展';
    const snapshot = { sheets: { s: { id: 's' } } };
    writeWorkbookInstance(snapshot, saved);
    const before = structuredClone(snapshot);
    expect(resolveInstanceDefinition(snapshot, fallback)?.model.fields[0]?.name).toBe('本次扩展');
    expect(snapshot).toEqual(before);
  });
});
