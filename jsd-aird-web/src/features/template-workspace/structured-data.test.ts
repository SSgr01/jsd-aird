import { describe, expect, it } from 'vitest';

import type { TemplateBinding } from './types';
import { synchronizeStructuredData } from './structured-data';

function binding(
  bindingId: string,
  dataPath: string,
  mappingKind: TemplateBinding['mappingKind'],
  parentBindingId?: string,
): TemplateBinding {
  return {
    bindingId,
    dataPath,
    role: mappingKind?.endsWith('_REGION') ? 'REPEAT_REGION' : 'FIELD',
    mappingKind,
    parentBindingId,
    locatorType: 'CELL_RANGE',
    locator: { address: 'A1' },
    syncDirection: 'TWO_WAY',
    primaryBinding: true,
    bindingStatus: 'VALID',
  };
}

describe('synchronizeStructuredData', () => {
  it('assembles repeat child columns into business records and skips the raw parent grid', () => {
    const parent = binding('parent', '/materials', 'REPEAT_REGION');
    const code = binding('code', '/materials/*/code', 'REPEAT_FIELD', parent.bindingId);
    const quantity = binding('quantity', '/materials/*/quantity', 'REPEAT_FIELD', parent.bindingId);
    const editorValues: Record<string, unknown> = {
      parent: [['A', 10], ['B', 20]],
      code: ['A', 'B'],
      quantity: [10, 20],
    };

    const result = synchronizeStructuredData({}, [parent, code, quantity], (item) =>
      editorValues[item.bindingId]);

    expect(result.data).toEqual({
      materials: [
        { code: 'A', quantity: 10 },
        { code: 'B', quantity: 20 },
      ],
    });
    expect(result.bindingValues.map((item) => item.dataPath)).toEqual([
      '/materials/*/code',
      '/materials/*/quantity',
    ]);
  });

  it('keeps the existing value when a binding cannot be read', () => {
    const result = synchronizeStructuredData(
      { order: { code: 'existing' } },
      [binding('code', '/order/code', 'SCALAR')],
      () => undefined,
    );

    expect(result.data).toEqual({ order: { code: 'existing' } });
    expect(result.bindingValues).toEqual([]);
  });

  it('removes stale repeat records before comparing editor values with structured data', () => {
    const parent = binding('parent', '/records/component/items', 'REPEAT_REGION');
    const product = binding('product', '/records/component/items/*/产品', 'REPEAT_FIELD', parent.bindingId);
    const specification = binding('specification', '/records/component/items/*/规格', 'REPEAT_FIELD', parent.bindingId);
    const result = synchronizeStructuredData(
      { records: { component: { items: [
        { 产品: 'A', 规格: 'small' },
        { 产品: 'B', 规格: 'large' },
        { 产品: 'stale', 规格: 'stale' },
      ] } } },
      [parent, product, specification],
      (item) => ({ product: ['A', 'B'], specification: ['small', 'large'] })[item.bindingId as 'product' | 'specification'],
    );

    expect(result.data).toEqual({ records: { component: { items: [
      { 产品: 'A', 规格: 'small' },
      { 产品: 'B', 规格: 'large' },
    ] } } });
    expect(result.bindingValues.every((pair) => JSON.stringify(pair.dataValue) === JSON.stringify(pair.editorValue))).toBe(true);
  });

  it('clears old values when only one repeat column becomes shorter', () => {
    const parent = binding('parent', '/records/component', 'REPEAT_REGION');
    const product = binding('product', '/records/component/*/产品', 'REPEAT_FIELD', parent.bindingId);
    const specification = binding('specification', '/records/component/*/规格', 'REPEAT_FIELD', parent.bindingId);
    const result = synchronizeStructuredData(
      { records: { component: [
        { 产品: 'A', 规格: 'small' },
        { 产品: 'B', 规格: 'medium' },
        { 产品: 'old-3', 规格: 'large' },
      ] } },
      [parent, product, specification],
      (item) => ({ product: ['A', 'B'], specification: ['small', 'medium', 'large'] })[item.bindingId as 'product' | 'specification'],
    );

    expect(result.data).toEqual({ records: { component: [
      { 产品: 'A', 规格: 'small' },
      { 产品: 'B', 规格: 'medium' },
      { 产品: null, 规格: 'large' },
    ] } });
    expect(result.bindingValues.find((pair) => pair.dataPath.endsWith('/产品'))).toEqual({
      dataPath: '/records/component/*/产品',
      dataValue: ['A', 'B', null],
      editorValue: ['A', 'B', null],
    });
  });
});
