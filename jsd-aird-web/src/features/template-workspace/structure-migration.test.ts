import { describe, expect, it } from 'vitest';

import type { FieldModel, TemplateBinding, WorkbookStructureOperation } from './types';
import { migrateWorkspaceStructure, operationFromUniverCommand } from './structure-migration';

const model: FieldModel = {
  modelVersion: 4,
  groups: [{ id: 'g1', name: '基础信息', order: 0 }],
  fields: [
    {
      id: 'f1',
      bindingId: 'b1',
      dataPath: '/value',
      groupId: 'g1',
      name: '字段',
      kind: 'SCALAR',
      valueType: 'string',
      required: false,
      reviewStatus: 'CONFIRMED',
    },
  ],
  blocks: [],
  semanticAnnotations: [],
};

const binding: TemplateBinding = {
  bindingId: 'b1',
  dataPath: '/value',
  role: 'FIELD',
  locatorType: 'CELL_RANGE',
  locator: { sheetId: 's1', labelAddress: 'A5', address: 'B5', dataRange: 'A8:D12' },
  syncDirection: 'TWO_WAY',
  primaryBinding: true,
  bindingStatus: 'VALID',
};

describe('workbook structure migration', () => {
  it.each(['row', 'col'] as const)('recognizes the SDK native remove-%s-by-range command', axis => {
    expect(operationFromUniverCommand({ id: `sheet.command.remove-${axis}-by-range`, params: {
      subUnitId: 's1', range: { startRow: 59, endRow: 60, startColumn: 2, endColumn: 3 },
    } })).toMatchObject({ type: axis === 'row' ? 'DELETE_ROWS' : 'DELETE_COLUMNS', index: axis === 'row' ? 60 : 3, count: 2 });
    expect(operationFromUniverCommand({ id: `sheet.command.remove-${axis}` })).toBeUndefined();
  });
  it('uses the SDK destination range for right/below insertion without offsetting twice', () => {
    expect(
      operationFromUniverCommand({
        id: 'sheet.command.insert-col',
        params: {
          subUnitId: 's1',
          direction: 1,
          range: { startRow: 0, endRow: 100, startColumn: 8, endColumn: 9 },
        },
      }),
    ).toMatchObject({ type: 'INSERT_COLUMNS', index: 9, count: 2 });
    expect(
      operationFromUniverCommand({
        id: 'sheet.command.insert-row',
        params: {
          subUnitId: 's1',
          direction: 2,
          range: { startRow: 59, endRow: 60, startColumn: 0, endColumn: 9 },
        },
      }),
    ).toMatchObject({ type: 'INSERT_ROWS', index: 60, count: 2 });
  });
  it('moves fields and expands a table range when rows are inserted', () => {
    const operation: WorkbookStructureOperation = {
      operationId: 'o1',
      type: 'INSERT_ROWS',
      sheetId: 's1',
      index: 10,
      count: 2,
      source: 'CUSTOMER',
    };
    const result = migrateWorkspaceStructure([binding], model, operation);
    expect(result.mapping[0]?.locator).toMatchObject({
      labelAddress: 'A5',
      address: 'B5',
      dataRange: 'A8:D14',
    });
  });

  it('marks a binding missing when its bound row is deleted', () => {
    const operation: WorkbookStructureOperation = {
      operationId: 'o2',
      type: 'DELETE_ROWS',
      sheetId: 's1',
      index: 5,
      count: 1,
      source: 'CUSTOMER',
    };
    const result = migrateWorkspaceStructure([binding], model, operation);
    expect(result.mapping[0]?.bindingStatus).toBe('MISSING');
    expect(result.mapping[0]?.locator).not.toHaveProperty('address');
    expect(result.model.fields[0]?.reviewStatus).toBe('ISSUE');
  });
  it('migrates legacy nested locator aliases and field properties together', () => {
    const legacy = {
      ...binding,
      mappingKind: 'REPEAT_FIELD' as const,
      repeatAxis: 'ROW' as const,
      locator: {
        sheetId: 's1',
        labelRange: 'A1',
        valueRange: 'A2:A3',
        logicalInputRange: 'A2:A3',
        address: 'A2:A3',
        value: { range: 'A2:A3', address: 'A2' },
        label: { range: 'A1', address: 'A1' },
      },
    };
    const result = migrateWorkspaceStructure(
      [legacy],
      { ...model, fields: [{ ...model.fields[0]!, valueRange: 'A2:A3', labelRange: 'A1' }] },
      {
        operationId: 'legacy',
        type: 'INSERT_ROWS',
        sheetId: 's1',
        index: 3,
        count: 2,
        source: 'CUSTOMER',
      },
    );
    expect(result.mapping[0]?.locator).toMatchObject({
      valueRange: 'A2:A5',
      logicalInputRange: 'A2:A5',
      value: { range: 'A2:A5', address: 'A2' },
      label: { range: 'A1', address: 'A1' },
    });
    expect(result.model.fields[0]?.valueRange).toBe('A2:A5');
  });
});
