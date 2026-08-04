import { describe, expect, it } from 'vitest';

import type { RecognitionSuggestion } from '@/services/templates/template-api';

import { applySuggestion, readFieldModel, removeBusinessField, updateBusinessField } from './field-model';

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
    expect(result.schema.properties).toMatchObject({
      materials: {
        type: 'array',
        title: '原料明细',
      },
    });
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
      fields: [{
        id: 'field-1',
        bindingId: 'binding-1',
        dataPath: '/product/name',
        groupId: 'base',
        name: '产品名称',
        kind: 'SCALAR' as const,
        valueType: 'string',
        required: false,
        reviewStatus: 'CONFIRMED' as const,
      }],
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
});
