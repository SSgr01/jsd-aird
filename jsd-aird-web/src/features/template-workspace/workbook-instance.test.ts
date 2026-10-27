import { describe, expect, it } from 'vitest';
import {
  createWorkbookInstance,
  migrateWorkbookInstance,
  structureCopies,
  discoverWorkbookField,
  recordUnboundCell,
  retargetAggregateFormula,
  copyWorkbookCells,
  readWorkbookInstance,
  writeWorkbookInstance,
  validateWorkbookInstance,
} from './workbook-instance';
import type { FieldModel, TemplateBinding, WorkbookStructureOperation } from './types';
const model: FieldModel = {
  modelVersion: 4,
  groups: [{ id: 'g', name: '检测', order: 0 }],
  fields: [
    {
      id: 'f',
      bindingId: 'f',
      groupId: 'g',
      name: '浓度',
      kind: 'SCALAR',
      valueType: 'number',
      required: false,
      reviewStatus: 'CONFIRMED',
      experimentField: { domain: 'TEST', field: 'VALUE' },
    },
  ],
  blocks: [],
  semanticAnnotations: [],
};
function bindings(axis: 'ROW' | 'COLUMN'): TemplateBinding[] {
  return [
    {
      bindingId: 'table',
      componentId: 'table',
      dataPath: '/records',
      mappingKind: 'REPEAT_REGION',
      role: 'REPEAT_REGION',
      repeatAxis: axis,
      locatorType: 'TABLE_REGION',
      locator: { sheetId: 's', sheetName: '表', dataRange: 'C3:H20' },
      syncDirection: 'TWO_WAY',
      primaryBinding: true,
      bindingStatus: 'VALID',
    },
    {
      bindingId: 'f',
      componentId: 'table',
      parentBindingId: 'table',
      fieldCode: 'f',
      dataPath: '/values',
      mappingKind: 'REPEAT_FIELD',
      role: 'FIELD',
      repeatAxis: axis,
      locatorType: 'CELL_RANGE',
      locator: {
        sheetId: 's',
        labelRange: axis === 'COLUMN' ? 'B10' : 'D2',
        address: axis === 'COLUMN' ? 'C10:H10' : 'D3:D20',
        valueRange: axis === 'COLUMN' ? 'C10:H10' : 'D3:D20',
      },
      syncDirection: 'TWO_WAY',
      primaryBinding: true,
      bindingStatus: 'VALID',
    },
  ];
}
const op = (
  type: WorkbookStructureOperation['type'],
  index: number,
  count = 1,
): WorkbookStructureOperation => ({
  operationId: 'op',
  type,
  sheetId: 's',
  index,
  count,
  source: 'CUSTOMER',
});
describe('reusable workbook structure', () => {
  it('expands aggregate references at a repeat tail while preserving quoted text and other sheets', () => {
    const changes = [
      { before: 'C52:J59', after: 'C52:J61', axis: 'ROW' as const },
      { before: 'B52:B59', after: 'B52:B61', axis: 'ROW' as const },
    ];
    expect(
      retargetAggregateFormula(
        '=SUMPRODUCT(C52:C59*($B$52:$B$59<>"C52:C59"))+Other!C52:C59',
        changes,
      ),
    ).toBe('=SUMPRODUCT(C52:C61*($B$52:$B$61<>"C52:C59"))+Other!C52:C59');
  });
  it('extends an ordinary row table and preserves identities of moved records', () => {
    const source = createWorkbookInstance(model, bindings('ROW'));
    const next = migrateWorkbookInstance(source, op('INSERT_ROWS', 5, 2));
    expect(next.records).toHaveLength(source.records.length + 2);
    expect(next.records.find((r) => r.id === source.records[2]!.id)?.index).toBe(7);
    expect(new Set(next.records.map((r) => r.id)).size).toBe(next.records.length);
    expect(source.records[2]!.index).toBe(5);
  });
  it('extends a column table and every child at its tail exactly once', () => {
    const source = createWorkbookInstance(model, bindings('COLUMN'));
    const next = migrateWorkbookInstance(source, op('INSERT_COLUMNS', 9, 2));
    expect(next.records).toHaveLength(8);
    expect(next.mapping[0]?.locator.dataRange).toBe('C3:J20');
    expect(next.mapping[1]?.locator.valueRange).toBe('C10:J10');
    expect(structureCopies(source, op('INSERT_COLUMNS', 9, 2))).toHaveLength(2);
  });
  it('extends a matrix item axis independently of its record axis', () => {
    const source = createWorkbookInstance(model, bindings('COLUMN'), {
      templateUsage: 'EXPERIMENT_DATA',
      identities: [],
      listProjections: [
        {
          listProjectionId: 'items',
          componentId: 'table',
          parentBindingId: 'table',
          domain: 'FORMULA',
          recordAxis: 'COLUMN',
          itemAxis: 'ROW',
          labelRange: 'B4:B6',
          valueRange: 'C4:H6',
          labelSemantic: 'MATERIAL_CODE',
          valueSemantic: 'ACTUAL_QTY',
        },
      ],
    });
    const next = migrateWorkbookInstance(source, op('INSERT_ROWS', 7, 2));
    expect(next.items).toHaveLength(5);
    expect(next.records).toHaveLength(6);
    expect(next.execution?.listProjections[0]?.valueRange).toBe('C4:H8');
    expect(next.mapping[1]?.locator.address).toBe('C12:H12');
    expect(structureCopies(source, op('INSERT_ROWS', 7, 2))).toHaveLength(2);
  });
  it('removes deleted identities and moves other bindings', () => {
    const source = createWorkbookInstance(model, bindings('COLUMN'));
    const deleted = source.records[1]!.id;
    const next = migrateWorkbookInstance(source, op('DELETE_COLUMNS', 4));
    expect(next.records).toHaveLength(5);
    expect(next.records.some((r) => r.id === deleted)).toBe(false);
  });
  it('removes an instance field when its physical field row is deleted', () => {
    const source = createWorkbookInstance(model, bindings('COLUMN'));
    const next = migrateWorkbookInstance(source, op('DELETE_ROWS', 10));
    expect(next.mapping.some((b) => b.bindingId === 'f')).toBe(false);
    expect(next.model.fields.some((f) => f.bindingId === 'f')).toBe(false);
    expect(next.records.map((r) => r.id)).toEqual(source.records.map((r) => r.id));
  });
  it('flags partial multi-row inserts without silently manufacturing a record', () => {
    const mapping = bindings('ROW');
    mapping[0]!.recordHeight = 2;
    mapping[0]!.recordStride = 2;
    const source = createWorkbookInstance(model, mapping),
      next = migrateWorkbookInstance(source, op('INSERT_ROWS', 4));
    expect(next.issues[0]?.code).toBe('PARTIAL_RECORD');
    expect(structureCopies(source, op('INSERT_ROWS', 4))).toHaveLength(0);
  });
  it('does not turn ordinary padding into records', () => {
    const source = createWorkbookInstance(model, bindings('ROW'));
    expect(migrateWorkbookInstance(source, op('INSERT_ROWS', 30)).records).toEqual(source.records);
  });
  it('requires experiment semantics only for experiment data', () => {
    const source = createWorkbookInstance(
      { ...model, fields: [{ ...model.fields[0]!, experimentSemanticStatus: 'NEEDS_REVIEW' }] },
      bindings('ROW'),
      { templateUsage: 'GENERAL_DATA', identities: [], listProjections: [] },
    );
    expect(validateWorkbookInstance(source)).toEqual([]);
    source.execution!.templateUsage = 'EXPERIMENT_DATA';
    expect(validateWorkbookInstance(source)).toHaveLength(1);
  });
  it('expands list totals across appended comparison columns', () => {
    const source = createWorkbookInstance(model, bindings('COLUMN'), {
      templateUsage: 'EXPERIMENT_DATA',
      identities: [],
      listProjections: [
        {
          listProjectionId: 'items',
          componentId: 'table',
          parentBindingId: 'table',
          domain: 'FORMULA',
          recordAxis: 'COLUMN',
          itemAxis: 'ROW',
          labelRange: 'B4:B6',
          valueRange: 'C4:H6',
          totalRange: 'C7:H7',
          labelSemantic: 'MATERIAL_CODE',
          valueSemantic: 'ACTUAL_QTY',
        },
      ],
    });
    const next = migrateWorkbookInstance(source, op('INSERT_COLUMNS', 9, 2));
    expect(next.execution?.listProjections[0]?.valueRange).toBe('C4:J6');
    expect(next.execution?.listProjections[0]?.totalRange).toBe('C7:J7');
  });
  it('discovers and binds a named local field in the existing table context', () => {
    const source = createWorkbookInstance(model, bindings('COLUMN'));
    const next = discoverWorkbookField(
      source,
      { sheetId: 's', sheetName: '表', address: 'B11' },
      '耐湿热观察',
    );
    expect(next?.model.fields.at(-1)).toMatchObject({
      name: '耐湿热观察',
      mappingKind: 'REPEAT_FIELD',
      reviewStatus: 'CONFIRMED',
      experimentField: { domain: 'TEST', field: 'VALUE' },
    });
    expect(next?.mapping.at(-1)?.locator.valueRange).toBe('C11:H11');
    expect(
      discoverWorkbookField(source, { sheetId: 's', sheetName: '表', address: 'C11' }, '正常'),
    ).toBeUndefined();
  });
  it('uses the merged business heading at a section boundary rather than the closest previous group', () => {
    const source = createWorkbookInstance(model, bindings('COLUMN'));
    source.model.groups.push({ id: 'process', name: '工艺', order: 1 });
    source.model.fields.push({ ...source.model.fields.find(f => f.id === 'f')!, id: 'process-field', bindingId: 'process-field', groupId: 'process', name: '工艺说明', experimentField: { domain: 'PROCESS', field: 'OTHER' }, locator: { sheetId: 's', valueRange: 'C13:H13' } });
    const snapshot = { sheets: { s: { id: 's', name: '表', mergeData: [{ startRow: 10, endRow: 15, startColumn: 0, endColumn: 0 }], cellData: { '10': { '0': { v: '工艺' } } } } } };
    const next = discoverWorkbookField(source, { sheetId: 's', sheetName: '表', address: 'B11' }, '补充保温记录', snapshot);
    expect(next?.model.fields.at(-1)).toMatchObject({ groupId: 'process', pathSegments: ['工艺', '补充保温记录'], experimentField: { domain: 'PROCESS', field: 'OTHER' }, reviewStatus: 'CONFIRMED' });
  });
  it('copies styles and relative formulas while clearing inputs and retaining absolute references', () => {
    expect(
      copyWorkbookCells(
        [[{ v: 'old', s: 'style', custom: { fieldId: 'f' } }, { f: '=A1+$B$2+"A1"' }]],
        2,
        1,
      ),
    ).toEqual([[{ v: '', s: 'style', custom: { fieldId: 'f' } }, { f: '=B3+$B$2+"A1"' }]]);
  });
  it('serializes the whole logical instance with the workbook for consistent undo and reopen', () => {
    const snapshot = { sheets: { s: { id: 's', name: '表' } } },
      source = createWorkbookInstance(model, bindings('ROW'));
    writeWorkbookInstance(snapshot, source);
    const restored = readWorkbookInstance(snapshot)!;
    expect(restored).toEqual(source);
    restored.records[0]!.index = 100;
    expect(readWorkbookInstance(snapshot)?.records[0]?.index).toBe(3);
  });
  it('retains an unbound zero as a review issue and clears it after the cell is emptied', () => {
    const source = createWorkbookInstance(model, bindings('ROW'));
    const flagged = recordUnboundCell(source, { sheetId: 's', address: 'J30' }, 0)!;
    expect(flagged.issues).toContainEqual(
      expect.objectContaining({ code: 'UNBOUND_VALUE', address: 'J30' }),
    );
    expect(recordUnboundCell(flagged, { sheetId: 's', address: 'J30' }, '')?.issues).toHaveLength(
      0,
    );
    expect(recordUnboundCell(source, { sheetId: 's', address: 'D3' }, '0')).toBeUndefined();
  });
});
