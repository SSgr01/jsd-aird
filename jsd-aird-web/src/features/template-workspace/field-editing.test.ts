import { describe, expect, it } from 'vitest';
import { createWorkbookInstance, discoverWorkbookField, renameWorkbookField, renameWorkbookFieldAt, updateWorkbookField, deleteWorkbookField, recordUnboundCell, migrateWorkbookInstance, copyWorkbookCells, retargetAggregateFormula, workbookFieldContext, addWorkbookField } from './workbook-instance';
import type { FieldModel, TemplateBinding } from './types';
import { WorkbookUndoBatch } from './undo-batch';
import { workbookWithExplicitRowHeights } from './workbook-instance';
import { readFieldModel, writeFieldModel } from './field-model';

function fixture(axis: 'ROW' | 'COLUMN' = 'COLUMN') {
  const model: FieldModel = { modelVersion: 5, groups: [{ id: 'test', name: '检测', order: 0 }, { id: 'process', name: '工艺', order: 1 }],
    fields: [{ id: 'f', bindingId: 'f', fieldCode: 'F', groupId: 'test', name: '浓度', kind: 'SCALAR', valueType: 'number', unit: '%', required: false, reviewStatus: 'CONFIRMED', pathSegments: ['检测', '浓度'] }], blocks: [], semanticAnnotations: [] };
  const mapping: TemplateBinding[] = [
    { bindingId: 'root', dataPath: '/records', mappingKind: 'REPEAT_REGION', role: 'REPEAT_REGION', repeatAxis: axis, locatorType: 'TABLE_REGION', locator: { sheetId: 's', dataRange: 'C3:H20' }, syncDirection: 'TWO_WAY', primaryBinding: true, bindingStatus: 'VALID' },
    { bindingId: 'f', dataPath: '/values', fieldCode: 'F', parentBindingId: 'root', mappingKind: 'REPEAT_FIELD', role: 'FIELD', repeatAxis: axis, locatorType: 'CELL_RANGE', locator: { sheetId: 's', labelRange: 'B10', address: axis === 'COLUMN' ? 'C10:H10' : 'D3:D20' }, syncDirection: 'TWO_WAY', primaryBinding: true, bindingStatus: 'VALID' },
  ];
  const instance = createWorkbookInstance(model, mapping);
  instance.model.fields.find(field => field.id === 'f')!.locator = mapping[1]!.locator;
  return instance;
}
const snapshot = { sheets: { s: { id: 's', name: '表', mergeData: [{ startRow: 10, endRow: 15, startColumn: 0, endColumn: 0 }], cellData: { 10: { 0: { v: '工艺' } } } } } };

describe('shared field editing', () => {
  it('keeps imported custom row heights after a stale auto-height calculation and preserves intentional auto height', () => {
    const snapshot = { sheets: { s: { id: 's', name: '表', rowData: { 0: { h: 43, ah: 20 }, 1: { h: 20, ia: 1, ah: 60 } } } } };
    const next = workbookWithExplicitRowHeights(snapshot);
    expect(next).toMatchObject({ sheets: { s: { rowData: { 0: { h: 43, ia: 0 }, 1: { ia: 1, ah: 60 } } } } });
    expect(snapshot.sheets.s.rowData[0]).toEqual({ h: 43, ah: 20 });
  });
  it('joins nested cell, metadata and format writes in a single undo group', () => {
    let opened = 0, closed = 0;
    const batches = new WorkbookUndoBatch();
    const open = () => { opened++; return { dispose: () => { closed++; } }; };
    const outer = batches.begin(open)!, inner = batches.begin(open)!;
    inner.dispose(); inner.dispose(); expect(closed).toBe(0); expect(opened).toBe(1);
    outer.dispose(); expect(closed).toBe(1);
    batches.begin(open)!.dispose(); expect(opened).toBe(2); expect(closed).toBe(2);
  });
  it('renames the definition and display path without replacing identities, units or conditions', () => {
    const source = fixture();
    const next = renameWorkbookField(source, 'f', '新浓度')!;
    expect(next.model.fields.find(field => field.id === 'f')).toMatchObject({ fieldCode: 'F', unit: '%', pathSegments: ['检测', '新浓度'] });
    expect(next.mapping[1]).toMatchObject({ bindingId: 'f', labelPath: '检测 > 新浓度', locator: { address: 'C10:H10' } });
    expect(source.model.fields.find(field => field.id === 'f')!.name).toBe('浓度');
    const moved = updateWorkbookField(next, 'f', { groupId: 'process' });
    expect(moved.mapping[1]!.labelPath).toBe('工艺 > 新浓度');
  });
  it('clears and refills a label using the same definition', () => {
    const cleared = renameWorkbookField(fixture(), 'f', '')!;
    expect(cleared.model.fields.find(field => field.id === 'f')).toMatchObject({ name: '浓度', labelStatus: 'UNRESOLVED', reviewStatus: 'NEEDS_CONFIRMATION' });
    const refilled = renameWorkbookField(cleared, 'f', '恢复名称')!;
    expect(refilled.model.fields.find(field => field.id === 'f')).toMatchObject({ labelStatus: 'RESOLVED', reviewStatus: 'CONFIRMED', name: '恢复名称' });
    expect(refilled.model.fields).toHaveLength(cleared.model.fields.length);
  });
  it('restores the same name after a cleared title is saved and the model is reloaded', () => {
    const cleared = renameWorkbookField(fixture(), 'f', '')!;
    const reloaded = { ...cleared, model: readFieldModel(writeFieldModel({}, cleared.model), cleared.mapping) };
    expect(reloaded.model.fields.find(field => field.id === 'f')).toMatchObject({ labelStatus: 'UNRESOLVED', labelTextMissing: true });
    const refilled = renameWorkbookField(reloaded, 'f', '浓度')!;
    expect(refilled.model.fields.find(field => field.id === 'f')).toMatchObject({ id: 'f', labelStatus: 'RESOLVED', labelTextMissing: false, reviewStatus: 'CONFIRMED' });
    expect(readFieldModel(writeFieldModel({}, refilled.model), refilled.mapping).fields.find(field => field.id === 'f')!.labelStatus).toBe('RESOLVED');
  });
  it('renames legacy bindings without a role and ignores adjacent method cells', () => {
    const source = fixture(); source.mapping[1] = { ...source.mapping[1]!, locator: { ...source.mapping[1]!.locator, labelRange: 'B10:C10' } }; delete (source.mapping[1] as Partial<TemplateBinding>).role;
    expect(renameWorkbookFieldAt(source, { sheetId: 's', address: 'B10' }, '改名').instance!.model.fields.find(field => field.id === 'f')!.name).toBe('改名');
    expect(renameWorkbookFieldAt(source, { sheetId: 's', address: 'C10' }, '测试方法').matched).toBe(false);
  });
  it('uses an empty section itself instead of the preceding field group', () => {
    const source = fixture();
    expect(discoverWorkbookField(source, { sheetId: 's', sheetName: '表', address: 'B11' }, '首个工艺项', snapshot)?.model.fields.at(-1)).toMatchObject({ groupId: 'process', unit: '', valueType: 'string', pathSegments: ['工艺', '首个工艺项'] });
    expect(workbookFieldContext(source, { sheetId: 's', address: 'B11' }, snapshot)).toMatchObject({ groupId: 'process', field: undefined });
  });
  it('does not suppress unbound content on a different worksheet', () => {
    const source = fixture();
    source.execution = { templateUsage: 'EXPERIMENT_DATA', identities: [], listProjections: [{ listProjectionId: 'p', componentId: 'root', parentBindingId: 'root', domain: 'FORMULA', recordAxis: 'COLUMN', itemAxis: 'ROW', labelRange: 'B4:B6', valueRange: 'C4:H6', labelSemantic: 'MATERIAL_CODE', valueSemantic: 'ACTUAL_QTY' }] };
    expect(recordUnboundCell(source, { sheetId: 'other', address: 'B5' }, '数据')?.issues).toContainEqual(expect.objectContaining({ code: 'UNBOUND_VALUE', sheetId: 'other' }));
    expect(discoverWorkbookField(source, { sheetId: 's', sheetName: '表', address: 'B5' }, 'RES-001')).toBeUndefined();
  });
  it('does not create fields or unbound errors from a configured note area', () => {
    const source = fixture(); source.model.staticRegions = [{ sheetId: 's', address: 'B11:H12', regionType: 'NOTE', displayName: '说明' }];
    expect(recordUnboundCell(source, { sheetId: 's', address: 'C11' }, '正常')).toBeUndefined();
    expect(discoverWorkbookField(source, { sheetId: 's', sheetName: '表', address: 'C11' }, '正常', snapshot)).toBeUndefined();
    expect(recordUnboundCell(source, { sheetId: 's', address: 'A11' }, '工艺', snapshot)).toBeUndefined();
  });
  it('deletes a region and all descendant definitions, summaries and identities', () => {
    const source = fixture();
    const next = deleteWorkbookField(source, 'region:root');
    expect(next.model.fields).toHaveLength(0); expect(next.mapping).toHaveLength(0); expect(next.records).toHaveLength(0);
  });
  it('keeps recipe definitions and items when deleting another field in the same component', () => {
    const source = fixture(); source.mapping.forEach(binding => { binding.componentId = 'shared'; });
    source.execution = { templateUsage: 'EXPERIMENT_DATA', identities: [], listProjections: [{ listProjectionId: 'recipe', componentId: 'shared', parentBindingId: 'root', domain: 'FORMULA', recordAxis: 'COLUMN', itemAxis: 'ROW', labelRange: 'B4:B6', valueRange: 'C4:H6', labelSemantic: 'MATERIAL_CODE', valueSemantic: 'ACTUAL_QTY' }] };
    source.items = [{ id: 'item', regionId: 'recipe', sheetId: 's', axis: 'ROW', index: 4, size: 1 }];
    const next = deleteWorkbookField(source, 'f');
    expect(next.execution!.listProjections).toHaveLength(1); expect(next.items[0]!.id).toBe('item');
  });
  it('uses current selection when adding and does not inherit a distant repeat parent', () => {
    const source = fixture();
    const next = addWorkbookField(source, { ownerId: 'record', origin: 'INSTANCE_LOCAL', selection: { sheetId: 's', sheetName: '表', address: 'B11' }, snapshot });
    expect(next.instance.model.fields.at(-1)).toMatchObject({ groupId: 'process', mappingKind: 'REPEAT_FIELD', parentFieldId: 'region:root' });
    const outside = addWorkbookField(source, { ownerId: 'record', origin: 'INSTANCE_LOCAL', selection: { sheetId: 's', sheetName: '表', address: 'B100' }, snapshot });
    expect(outside.instance.model.fields.at(-1)!.mappingKind).toBe('SCALAR');
  });
  it('clears a structural issue after repairing an inserted half-record', () => {
    const source = fixture('ROW'); source.mapping = [{ ...source.mapping[0]!, recordHeight: 2, recordStride: 2, locator: { sheetId: 's', dataRange: 'C3:H8' } }];
    const seeded = createWorkbookInstance(source.model, source.mapping);
    const inserted = migrateWorkbookInstance(seeded, { operationId: 'i', type: 'INSERT_ROWS', sheetId: 's', index: 4, count: 1, source: 'CUSTOMER' });
    expect(inserted.records[0]).toMatchObject({ id: seeded.records[0]!.id, size: 3 });
    const repaired = migrateWorkbookInstance(inserted, { operationId: 'd', type: 'DELETE_ROWS', sheetId: 's', index: 4, count: 1, source: 'CUSTOMER' });
    expect(repaired.records).toEqual(seeded.records); expect(repaired.issues).toHaveLength(0);
  });
  it('removes a scalar when all of its input is physically deleted, but retains a missing label', () => {
    const source = fixture(); source.mapping = source.mapping.slice(1).map(binding => ({ ...binding, mappingKind: 'SCALAR', parentBindingId: undefined, locator: { sheetId: 's', labelRange: 'B10', address: 'C10' } }));
    source.model.fields = source.model.fields.filter(field => field.id === 'f');
    const removed = migrateWorkbookInstance(source, { operationId: 'd', type: 'DELETE_ROWS', sheetId: 's', index: 10, count: 1, source: 'CUSTOMER' });
    expect(removed.model.fields).toHaveLength(0);
    const missing = migrateWorkbookInstance(source, { operationId: 'd', type: 'DELETE_COLUMNS', sheetId: 's', index: 2, count: 1, source: 'CUSTOMER' });
    expect(missing.model.fields[0]).toMatchObject({ id: 'f', labelStatus: 'UNRESOLVED', reviewStatus: 'NEEDS_CONFIRMATION' });
  });
  it.each(['ROW', 'COLUMN'] as const)('preserves identity and flags partial deletion of a two-cell %s record', axis => {
    const source = fixture(axis); source.mapping = [source.mapping[0]!]; source.model.fields = source.model.fields.filter(field => field.structuralOnly);
    source.mapping[0] = { ...source.mapping[0]!, recordHeight: 2, recordWidth: 2, recordStride: 2, locator: { sheetId: 's', dataRange: 'C3:H8' } };
    const seeded = createWorkbookInstance(source.model, source.mapping);
    const firstId = seeded.records[0]!.id;
    const next = migrateWorkbookInstance(seeded, { operationId: 'd', type: axis === 'ROW' ? 'DELETE_ROWS' : 'DELETE_COLUMNS', sheetId: 's', index: 4, count: 1, source: 'CUSTOMER' });
    expect(next.records.find(record => record.id === firstId)).toMatchObject({ index: 3, size: 1 });
    expect(next.issues).toContainEqual(expect.objectContaining({ code: 'PARTIAL_RECORD' }));
  });
  it.each(['ROW', 'COLUMN'] as const)('includes records inserted at the %s region head', axis => {
    const source = fixture(axis);
    const next = migrateWorkbookInstance(source, { operationId: 'i', type: axis === 'ROW' ? 'INSERT_ROWS' : 'INSERT_COLUMNS', sheetId: 's', index: 3, count: 2, source: 'CUSTOMER' });
    expect(next.records).toHaveLength(source.records.length + 2);
    expect(next.records[0]!.index).toBe(3);
    expect(next.mapping[0]!.locator.dataRange).toBe(axis === 'ROW' ? 'C3:H22' : 'C3:J20');
    expect(next.issues).toHaveLength(0);
  });
  it('creates an independent field only from an unambiguous form layout', () => {
    const source = fixture(); source.mapping = source.mapping.slice(1).map(binding => ({ ...binding, mappingKind: 'SCALAR', parentBindingId: undefined, locator: { sheetId: 's', labelRange: 'B4', address: 'C4:D4' } }));
    source.model.fields = source.model.fields.filter(field => field.id === 'f');
    source.model.blocks = [{ blockId: 'form', sheetId: 's', type: 'FORM_REGION', businessName: '基础信息', range: 'A1:F8' }];
    const next = discoverWorkbookField(source, { sheetId: 's', sheetName: '表', address: 'B5' }, '补充说明')!;
    expect(next.model.fields.at(-1)).toMatchObject({ name: '补充说明', valueType: 'string', unit: '', reviewStatus: 'CONFIRMED' });
    expect(next.mapping.at(-1)!.locator.valueRange).toBe('C5:D5');
    expect(discoverWorkbookField(source, { sheetId: 's', sheetName: '表', address: 'E5' }, '正常')).toBeUndefined();
    expect(discoverWorkbookField(source, { sheetId: 's', sheetName: '表', address: 'B5' }, '=A1')).toBeUndefined();
    source.model.fields.push({ ...source.model.fields[0]!, id: 'different', bindingId: 'different' });
    source.mapping.push({ ...source.mapping[0]!, bindingId: 'different', locator: { sheetId: 's', labelRange: 'B7', address: 'C7:E7' } });
    expect(discoverWorkbookField(source, { sheetId: 's', sheetName: '表', address: 'B5' }, '位置待核对')!.model.fields.at(-1)!.reviewStatus).toBe('NEEDS_CONFIRMATION');
  });
  it.each(['=LOG10(A1)', '=SUM(A1)+1E3', "='SHEET1'!A1", '=A1+$B$2+C$3+$D4'])('copies formula references without changing other tokens: %s', formula => {
    const expected: Record<string, string> = { '=LOG10(A1)': '=LOG10(B2)', '=SUM(A1)+1E3': '=SUM(B2)+1E3', "='SHEET1'!A1": '=SHEET1!B2', '=A1+$B$2+C$3+$D4': '=B2+$B$2+D$3+$D5' };
    expect(copyWorkbookCells([[{ f: formula }]], 1, 1)[0]![0]!.f).toBe(expected[formula]);
  });
  it('extends only local aggregate references and preserves other formula tokens', () => {
    expect(retargetAggregateFormula('=SUM(C4:C6)+LOG10(A1)+1E3+\'SHEET1\'!C4:C6', [{ before: 'C4:H6', after: 'C4:H8', axis: 'ROW' }]))
      .toBe('=SUM(C4:C8)+LOG10(A1)+1E3+\'SHEET1\'!C4:C6');
  });
});
