import { describe, expect, it } from 'vitest';

import type { RecognitionReview, RecognitionReviewItem } from '@/services/templates/template-api';

import { readFieldModel } from './field-model';
import { acceptRecognitionReviewItem, mergeRecognitionReview } from './recognition-review';

describe('recognition review draft merge', () => {
  it('shows pending results as candidates without adding formal schema or mappings', () => {
    const schema = { type: 'object', properties: {} };
    const review = createReview(createItem());

    const merged = mergeRecognitionReview(schema, [], readFieldModel(schema, []), review);

    expect(merged.model.fields).toHaveLength(1);
    expect(merged.model.fields[0]).toMatchObject({
      recognitionItemId: '11111111-1111-1111-1111-111111111111',
      name: '产品名称',
      reviewStatus: 'NEEDS_CONFIRMATION',
      candidate: true,
    });
    expect(merged.model.fields[0]?.candidateLocator).toMatchObject({ sheetName: '生产单', address: 'B2' });
    expect(merged.mapping).toHaveLength(0);
    expect(merged.schema.properties).toEqual({});

    const accepted = acceptRecognitionReviewItem(
      merged.schema, merged.mapping, merged.model, review.items[0] as RecognitionReviewItem,
    );
    expect(accepted.model.fields[0]).toMatchObject({ reviewStatus: 'CONFIRMED' });
    expect(accepted.model.fields[0]?.candidate).not.toBe(true);
    expect(accepted.mapping[0]?.locator).toMatchObject({ sheetName: '生产单', address: 'B2' });
    expect(accepted.schema.properties).toHaveProperty('product');
  });

  it('marks conflicts in the field model and keeps ignored items out of the draft', () => {
    const schema = { type: 'object', properties: {} };
    const conflict = createItem({ status: 'CONFLICT' });
    const conflictReview = createReview(conflict);
    const merged = mergeRecognitionReview(schema, [], readFieldModel(schema, []), conflictReview);
    expect(merged.model.fields[0]?.reviewStatus).toBe('ISSUE');

    const ignored = createReview(createItem({ status: 'IGNORED' }));
    const ignoredMerge = mergeRecognitionReview(schema, [], readFieldModel(schema, []), ignored);
    expect(ignoredMerge.model.fields).toHaveLength(0);
    expect(ignoredMerge.mapping).toHaveLength(0);
  });

  it('keeps structural roots out of the ordinary field model', () => {
    const schema = { type: 'object', properties: {} };
    const root = createItem({
      kind: 'ROW_TABLE',
      fieldName: '重复记录区域',
      payload: {
        ...createItem().payload,
        kind: 'ROW_TABLE',
        role: 'REPEAT_REGION',
        valueType: 'array',
        fieldName: '重复记录区域',
      },
    });
    const merged = mergeRecognitionReview(schema, [], readFieldModel(schema, []), createReview(root));
    expect(merged.model.fields).toHaveLength(0);
    expect(merged.mapping).toHaveLength(0);
  });

  it('replaces candidates from an older recognition run instead of accumulating them', () => {
    const schema = { type: 'object', properties: {} };
    const first = mergeRecognitionReview(
      schema, [], readFieldModel(schema, []), createReview(createItem()),
    );
    const latestItem = createItem({
      id: '33333333-3333-3333-3333-333333333333',
      fieldName: '生产日期',
      payload: {
        ...createItem().payload,
        fieldName: '生产日期',
        fieldCode: 'PRODUCTION.DATE',
        dataPath: '/production/date',
      },
    });

    const second = mergeRecognitionReview(first.schema, first.mapping, first.model, createReview(latestItem));

    expect(second.model.fields).toHaveLength(1);
    expect(second.model.fields[0]).toMatchObject({
      recognitionItemId: '33333333-3333-3333-3333-333333333333',
      name: '生产日期',
    });
  });
});

function createReview(item: RecognitionReviewItem): RecognitionReview {
  return {
    recognitionRunId: '22222222-2222-2222-2222-222222222222',
    runStatus: 'PARSED',
    groups: ['基本信息'],
    summary: {
      total: item.status === 'IGNORED' ? 0 : 1,
      confirmed: 0,
      pending: item.status === 'PENDING' ? 1 : 0,
      lowConfidence: 0,
      conflict: item.status === 'CONFLICT' ? 1 : 0,
      ignored: item.status === 'IGNORED' ? 1 : 0,
      scalar: item.status === 'IGNORED' ? 0 : 1,
      rowTable: 0,
      matrix: 0,
      qualityIssueCount: 0,
      autoFixedCount: 0,
      blockingIssueCount: 0,
    },
    items: [item],
    qualityIssues: [],
  };
}

function createItem(update: Partial<RecognitionReviewItem> = {}): RecognitionReviewItem {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    suggestionIds: ['11111111-1111-1111-1111-111111111111'],
    fieldName: '产品名称',
    description: '根据模板内容自动识别',
    groupName: '基本信息',
    kind: 'SCALAR',
    valueType: 'string',
    sheetId: 'sheet-1',
    sheetName: '生产单',
    labelAddress: 'A2',
    address: 'B2',
    confidence: 0.9,
    confidenceLevel: 'HIGH',
    status: 'PENDING',
    payload: {
      fieldCode: 'PRODUCT.NAME',
      fieldName: '产品名称',
      dataPath: '/product/name',
      valueType: 'string',
      required: false,
      role: 'FIELD',
      locatorType: 'CELL_RANGE',
      locator: { sheetId: 'sheet-1', sheetName: '生产单', labelAddress: 'A2', address: 'B2' },
      groupName: '基本信息',
      reason: '根据模板内容自动识别',
    },
    ...update,
  };
}
