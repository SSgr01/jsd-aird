import { describe, expect, it } from 'vitest';
import { workbookOperationBlock, copyableSheetMetadata, isGuardedWorkbookOperation } from './workbook-operation-guard';
import { createWorkbookInstance, migrateWorkbookInstance } from './workbook-instance';
import { operationFromUniverCommand } from './structure-migration';
import type { FieldModel, TemplateBinding } from './types';

const model: FieldModel = { modelVersion: 5, groups: [], fields: [], blocks: [], semanticAnnotations: [] };
const mapping: TemplateBinding[] = [
  { bindingId: 'root', role:'REPEAT_REGION', mappingKind: 'REPEAT_REGION', repeatAxis: 'COLUMN', dataPath: '/records', locatorType: 'TABLE_REGION',
    locator: { sheetId: 's', sheetName: '原表', dataRange: 'C4:H20' }, syncDirection: 'TWO_WAY', primaryBinding: true, bindingStatus: 'VALID' },
  ...['10','11'].map(row => ({ bindingId: `f${row}`, role:'FIELD' as const, parentBindingId: 'root', mappingKind: 'REPEAT_FIELD' as const, dataPath: `/fields/${row}`,
    locatorType: 'CELL_RANGE', locator: { sheetId: 's', sheetName: '原表', labelRange: `B${row}`, valueRange: `C${row}:H${row}` },
    syncDirection: 'TWO_WAY' as const, primaryBinding: true, bindingStatus: 'VALID' as const })),
];
const instance = createWorkbookInstance(model,mapping);
const context = { instance, bindings: mapping, sheetId: 's' };
const range = (r: number,c: number,er = r,ec = c) => ({ startRow: r,endRow: er,startColumn: c,endColumn: ec });
const block = (id: string,params: object) => workbookOperationBlock({ id: `sheet.command.${id}`,params },context);

describe('native workbook operation protection', () => {
  it.each(['insert-range-move-down','delete-range-move-up'])('checks the full vertical corridor for %s', id => {
    expect(block(id,{ range: range(0,2) })).toBeTruthy();
    expect(block(id,{ range: range(30,2) })).toBeUndefined();
    expect(block(id,{ range: range(0,9) })).toBeUndefined();
  });
  it.each(['insert-range-move-right','delete-range-move-left'])('checks the full horizontal corridor for %s', id => {
    expect(block(id,{ range: range(9,0) })).toBeTruthy();
    expect(block(id,{ range: range(9,10) })).toBeUndefined();
  });
  it('uses current selection when a menu command has no parameters and isolates sheets', () => {
    expect(workbookOperationBlock({ id: 'sheet.command.insert-range-move-down' },{ ...context,selection: { sheetId: 's',sheetName:'原表',address: 'C1' } })).toBeTruthy();
    expect(block('insert-range-move-down',{ subUnitId: 'other',range: range(0,2) })).toBeUndefined();
  });
  it.each(['copy-sheet','remove-sheet'])('protects bound sheets but permits unbound sheets: %s', id => {
    expect(block(id,{})).toBeTruthy();
    expect(block(id,{ subUnitId: 'other' })).toBeUndefined();
  });
  it('protects both ends of range moves and the intervening rows of a drag', () => {
    expect(block('move-range',{ fromSubUnitId: 'other',toSubUnitId: 's',fromRange: range(0,0),toRange: range(9,2) })).toBeTruthy();
    expect(block('move-range',{ fromRange: range(30,10),toRange: range(32,10) })).toBeUndefined();
    expect(block('move-rows',{ fromRange: range(0,0,0,10),toRange: range(22,0,22,10) })).toBeTruthy();
  });
  it('protects sorting and permits formatting, clearing and whole row/column operations', () => {
    expect(block('sort-range',{ range: range(9,2,10,3) })).toBeTruthy();
    expect(block('sort-range',{ range: range(30,10,35,12) })).toBeUndefined();
    for (const id of ['insert-row','remove-row-by-range','insert-col','remove-col-by-range','clear-selection-all','set-range-values','set-range-font-family']) expect(isGuardedWorkbookOperation(`sheet.command.${id}`)).toBe(false);
  });
  it('blocks merged independent values and labels but permits presentation cells', () => {
    expect(block('add-worksheet-merge',{ selections: [{ range: range(9,2,9,3) }] })).toBeTruthy();
    expect(block('add-worksheet-merge',{ selections: [{ range: range(9,2,10,2) }] })).toBeTruthy();
    expect(block('add-worksheet-merge',{ selections: [{ range: range(9,1,10,1) }] })).toBeTruthy();
    expect(block('add-worksheet-merge',{ selections: [{ range: range(9,0,15,0) }] })).toBeUndefined();
    expect(block('remove-worksheet-merge',{ range: range(9,0,15,0) })).toBeUndefined();
  });
  it('allows a merge within one multi-column record but not across record identities', () => {
    const wide = { ...instance,records: [{ id:'r1',regionId:'root',sheetId:'s',axis:'COLUMN' as const,index:3,size:2 },{ id:'r2',regionId:'root',sheetId:'s',axis:'COLUMN' as const,index:5,size:2 }] };
    expect(workbookOperationBlock({ id:'sheet.command.add-worksheet-merge',params:{ selections:[{ range:range(9,2,9,3) }] } },{ ...context,instance:wide })).toBeUndefined();
    expect(workbookOperationBlock({ id:'sheet.command.add-worksheet-merge',params:{ selections:[{ range:range(9,3,9,4) }] } },{ ...context,instance:wide })).toBeTruthy();
  });
  it('keeps printable metadata when copying unbound sheets without duplicating system identities', () => {
    expect(copyableSheetMetadata({ print:{ landscape:true },templateInstance:instance,labTemplate:{ kind:'TEST' },other:'keep' })).toEqual({ print:{ landscape:true },other:'keep' });
  });
  it('renames sheets without changing identities, paths or ranges', () => {
    const operation = operationFromUniverCommand({ id:'sheet.command.set-worksheet-name',params:{ subUnitId:'s',name:'中文表2',oldName:'原表' } })!;
    const renamed = migrateWorkbookInstance(instance,operation);
    expect(renamed.mapping[1]).toMatchObject({ bindingId:'f10',locator:{ sheetId:'s',sheetName:'中文表2',valueRange:'C10:H10' } });
    expect(renamed.records).toEqual(instance.records);
    expect(renamed.mapping[1]!.dataPath).toBe(instance.mapping[1]!.dataPath);
  });
});
