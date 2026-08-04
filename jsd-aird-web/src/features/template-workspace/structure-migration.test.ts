import { describe, expect, it } from 'vitest';

import type { FieldModel, TemplateBinding, WorkbookStructureOperation } from './types';
import { migrateWorkspaceStructure } from './structure-migration';

const model: FieldModel = {
  modelVersion: 4,
  groups: [{ id: 'g1', name: '基础信息', order: 0 }],
  fields: [{
    id: 'f1', bindingId: 'b1', dataPath: '/value', groupId: 'g1', name: '字段', kind: 'SCALAR',
    valueType: 'string', required: false, reviewStatus: 'CONFIRMED',
  }],
  blocks: [], semanticAnnotations: [],
};

const binding: TemplateBinding = {
  bindingId: 'b1', dataPath: '/value', role: 'FIELD', locatorType: 'CELL_RANGE',
  locator: { sheetId: 's1', labelAddress: 'A5', address: 'B5', dataRange: 'A8:D12' },
  syncDirection: 'TWO_WAY', primaryBinding: true, bindingStatus: 'VALID',
};

describe('workbook structure migration', () => {
  it('moves fields and expands a table range when rows are inserted', () => {
    const operation: WorkbookStructureOperation = {
      operationId: 'o1', type: 'INSERT_ROWS', sheetId: 's1', index: 10, count: 2, source: 'CUSTOMER',
    };
    const result = migrateWorkspaceStructure([binding], model, operation);
    expect(result.mapping[0]?.locator).toMatchObject({ labelAddress: 'A5', address: 'B5', dataRange: 'A8:D14' });
  });

  it('marks a binding missing when its bound row is deleted', () => {
    const operation: WorkbookStructureOperation = {
      operationId: 'o2', type: 'DELETE_ROWS', sheetId: 's1', index: 5, count: 1, source: 'CUSTOMER',
    };
    const result = migrateWorkspaceStructure([binding], model, operation);
    expect(result.mapping[0]?.bindingStatus).toBe('MISSING');
    expect(result.mapping[0]?.locator).not.toHaveProperty('address');
    expect(result.model.fields[0]?.reviewStatus).toBe('ISSUE');
  });
});
