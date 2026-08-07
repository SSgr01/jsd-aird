import { describe, expect, it } from 'vitest';

import type { RecognitionSuggestion } from '@/services/templates/template-api';

import {
  applySuggestion,
  readFieldModel,
  removeBusinessField,
  updateBusinessField,
} from './field-model';

describe('field model', () => {
  it('groups an accepted table suggestion and creates one region binding', () => {
    const suggestion: RecognitionSuggestion = {
      id: 'suggestion-1',
      importJobId: 'job-1',
      source: 'RULE',
      suggestionType: 'ROW_TABLE',
      confidence: 0.78,
      evidence: [],
      decision: 'PENDING',
      createdAt: new Date().toISOString(),
      payload: {
        fieldCode: 'MATERIAL.LINES',
        fieldName: '原料明细',
        groupName: '原料信息',
        dataPath: '/materials',
        valueType: 'array',
        required: false,
        role: 'REPEAT_REGION',
        kind: 'ROW_TABLE',
        locatorType: 'TABLE_REGION',
        locator: { sheetId: 'sheet-1', address: 'A5:D15' },
        columns: [
          { code: 'name', name: '原料名称' },
          { code: 'amount', name: '用量', valueType: 'number' },
        ],
      },
    };
    const schema = { type: 'object', properties: {} };
    const model = readFieldModel(schema, []);

    const result = applySuggestion(schema, [], model, suggestion);

    expect(result.mapping).toHaveLength(1);
    expect(result.model.groups.some((group) => group.name === '原料信息')).toBe(true);
    expect(result.model.fields[0]?.kind).toBe('ROW_TABLE');
    expect(
      result.model.fields.filter((field) => field.parentFieldId === result.model.fields[0]?.id),
    ).toHaveLength(2);
    expect(result.model.fields.find((field) => field.name === '用量')?.dataPath).toBe(
      '/materials/*/amount',
    );
    expect(result.schema.properties).toMatchObject({
      materials: {
        type: 'array',
        title: '原料明细',
      },
    });

    const amount = result.model.fields.find((field) => field.name === '用量');
    expect(amount).toBeDefined();
    if (amount) {
      const childUpdated = updateBusinessField(result.schema, result.model, amount.id, {
        name: '实际用量',
        unit: 'kg',
      });
      expect(childUpdated.schema.properties).toMatchObject({
        materials: {
          items: { properties: { amount: { title: '实际用量', 'x-unit': 'kg' } } },
        },
      });
    }
  });

  it('keeps schema and field model aligned when editing and deleting a field', () => {
    const schema = {
      type: 'object',
      properties: { product: { type: 'object', properties: { name: { type: 'string' } } } },
    };
    const model = {
      modelVersion: 1,
      groups: [{ id: 'base', name: '基本信息', order: 0 }],
      blocks: [],
      semanticAnnotations: [],
      fields: [
        {
          id: 'field-1',
          bindingId: 'binding-1',
          dataPath: '/product/name',
          groupId: 'base',
          name: '产品名称',
          kind: 'SCALAR' as const,
          valueType: 'string',
          required: false,
          reviewStatus: 'CONFIRMED' as const,
        },
      ],
    };

    const updated = updateBusinessField(schema, model, 'field-1', {
      name: '产品全称',
      valueType: 'number',
      required: true,
    });
    expect(updated.schema.properties).toMatchObject({
      product: { properties: { name: { type: 'number', title: '产品全称' } }, required: ['name'] },
    });

    const removed = removeBusinessField(updated.schema, updated.model, 'field-1');
    expect(removed.model.fields).toHaveLength(0);
    const product = (removed.schema.properties as Record<string, { properties: object }>).product;
    expect(product).toBeDefined();
    expect(product?.properties).not.toHaveProperty('name');
  });

  it('keeps horizontal repeat children as independent mappings', () => {
    const schema = { type: 'object', properties: {} };
    const model = readFieldModel(schema, []);
    const parent: RecognitionSuggestion = {
      id: 'parent-suggestion',
      importJobId: 'job-1',
      source: 'MODEL',
      suggestionType: 'ROW_TABLE',
      confidence: 0.9,
      evidence: [],
      decision: 'ACCEPTED',
      createdAt: '',
      payload: {
        fieldId: 'parent-field',
        bindingId: 'parent-binding',
        relationId: 'parent-relation',
        fieldCode: 'FORMULA.ITEMS',
        fieldName: '配方明细',
        dataPath: '/formulaItems',
        valueType: 'array',
        required: false,
        role: 'REPEAT_REGION',
        kind: 'ROW_TABLE',
        locatorType: 'TABLE_REGION',
        locator: { sheetId: 'sheet-1', dataRange: 'B6:H10', address: 'B6:H10' },
        repeatAxis: 'COLUMN',
        recordHeight: 5,
        recordWidth: 1,
        recordStride: 1,
        terminationRule: { type: 'UNTIL_EMPTY_RECORD' },
        hasIndependentChildren: true,
      },
    };
    const child: RecognitionSuggestion = {
      ...parent,
      id: 'child-suggestion',
      suggestionType: 'TABLE_CHILD_FIELD',
      payload: {
        ...parent.payload,
        fieldId: 'child-field',
        bindingId: 'child-binding',
        relationId: 'child-relation',
        parentRelationId: 'parent-relation',
        parentFieldId: 'parent-field',
        parentBindingId: 'parent-binding',
        suggestionLevel: 'CHILD',
        fieldCode: 'FORMULA.ITEM.MATERIAL_CODE',
        fieldName: '原料编号',
        dataPath: '/formulaItems/*/materialCode',
        valueType: 'string',
        role: 'FIELD',
        kind: 'SCALAR',
        locatorType: 'CELL_RANGE',
        locator: {
          sheetId: 'sheet-1',
          parentRange: 'B6:H10',
          address: 'C6:H6',
          valueRange: 'C6:H6',
          valueMode: 'ARRAY_ROW',
        },
        mappingKind: 'REPEAT_FIELD',
      },
    };

    const parentResult = applySuggestion(schema, [], model, parent);
    const childResult = applySuggestion(
      parentResult.schema,
      parentResult.mapping,
      parentResult.model,
      child,
    );

    expect(childResult.mapping).toHaveLength(2);
    expect(childResult.mapping[1]).toMatchObject({
      bindingId: 'child-binding',
      parentBindingId: 'parent-binding',
      mappingKind: 'REPEAT_FIELD',
      repeatAxis: 'COLUMN',
      termination: { type: 'UNTIL_EMPTY_RECORD' },
    });
    expect(childResult.model.fields.find((field) => field.id === 'child-field')).toMatchObject({
      parentFieldId: 'parent-field',
      mappingKind: 'REPEAT_FIELD',
    });
  });

  it('does not collapse fields that arrive with the same dataPath', () => {
    const schema = { type: 'object', properties: {} };
    const model = readFieldModel(schema, []);
    const suggestion = (id: string, relationId: string, name: string): RecognitionSuggestion => ({
      id,
      importJobId: 'job-duplicate-path',
      source: 'MODEL',
      suggestionType: 'SCALAR_FIELD',
      confidence: 0.8,
      evidence: [],
      decision: 'ACCEPTED',
      createdAt: '',
      payload: {
        fieldId: `${id}-field`,
        bindingId: `${id}-binding`,
        relationId,
        fieldCode: `TEST.${id.toUpperCase()}`,
        fieldName: name,
        dataPath: '/recognized/duplicate',
        valueType: 'string',
        required: false,
        role: 'FIELD',
        locatorType: 'CELL_RANGE',
        locator: { sheetId: 'sheet-1', address: `${id}!A1` },
      },
    });

    const first = applySuggestion(schema, [], model, suggestion('first', 'relation-first', '第一字段'));
    const second = applySuggestion(
      first.schema,
      first.mapping,
      first.model,
      suggestion('second', 'relation-second', '第二字段'),
    );

    expect(second.model.fields).toHaveLength(2);
    expect(new Set(second.model.fields.map((field) => field.dataPath)).size).toBe(2);
    expect(second.mapping).toHaveLength(2);
  });
});
