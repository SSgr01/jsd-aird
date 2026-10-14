import { describe, expect, it } from 'vitest';

import type { RecognitionReview, RecognitionReviewItem } from '@/services/templates/template-api';

import { readFieldModel } from './field-model';
import {
  acceptRecognitionReviewItem,
  isRecognitionRegionRoot,
  mergeRecognitionReview,
} from './recognition-review';

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
    expect(isRecognitionRegionRoot(root)).toBe(true);
    expect(merged.model.fields).toHaveLength(0);
    expect(merged.mapping).toHaveLength(0);
  });

  it('does not classify a repeat child as a structural root', () => {
    const child = createItem({
      child: true,
      payload: {
        ...createItem().payload,
        kind: 'SCALAR',
        mappingKind: 'REPEAT_FIELD',
        suggestionLevel: 'CHILD',
        parentBindingId: 'parent-binding',
      },
    });

    expect(isRecognitionRegionRoot(child)).toBe(false);
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

  it('keeps every Word table-cell candidate when cells have no spreadsheet address', () => {
    const schema = { type: 'object', properties: {} };
    const formField = createItem({
      id: '88888888-8888-8888-8888-888888888881',
      fieldName: '项目名称',
      payload: {
        ...createItem().payload,
        fieldName: '项目名称', fieldCode: 'WORD.PROJECT', dataPath: '/word/project',
        relationId: 'word-project', regionId: 'word-form-region', blockId: 'word-form-region',
        locatorType: 'DOCX_TABLE_CELL',
        locator: {
          locatorType: 'DOCX_TABLE_CELL', nodeId: 'docx-cell-project',
          valueAnchor: 'docx-cell-project', valueCellPaths: ['/document/table[1]/row[1]/cell[2]'],
        },
      },
    });
    const rowField = createItem({
      id: '88888888-8888-8888-8888-888888888882',
      fieldName: '物料名称',
      child: true,
      payload: {
        ...createItem().payload,
        fieldName: '物料名称', fieldCode: 'WORD.MATERIAL', dataPath: '/word/records/*/material',
        relationId: 'word-material', regionId: 'word-row-region', blockId: 'word-row-region',
        parentRelationId: 'word-row-region', mappingKind: 'REPEAT_FIELD',
        suggestionLevel: 'CHILD', repeatAxis: 'ROW',
        locatorType: 'DOCX_TABLE_CELL',
        locator: {
          locatorType: 'DOCX_TABLE_CELL', nodeId: 'docx-cell-material',
          valueAnchor: 'docx-cell-material', valueCellPaths: ['/document/table[2]/row[2]/cell[2]'],
        },
      },
    });
    const secondRowField = createItem({
      id: '88888888-8888-8888-8888-888888888883',
      fieldName: '单位',
      child: true,
      payload: {
        ...rowField.payload,
        fieldName: '单位', fieldCode: 'WORD.UNIT', dataPath: '/word/records/*/unit',
        relationId: 'word-unit',
        locator: {
          locatorType: 'DOCX_TABLE_CELL', nodeId: 'docx-cell-unit',
          valueAnchor: 'docx-cell-unit', valueCellPaths: ['/document/table[2]/row[2]/cell[4]'],
        },
      },
    });
    const review = createReview(formField);
    review.items = [formField, rowField, secondRowField];

    const merged = mergeRecognitionReview(schema, [], readFieldModel(schema, []), review);

    expect(merged.model.fields).toHaveLength(3);
    expect(merged.model.fields.map((field) => field.name))
      .toEqual(expect.arrayContaining(['项目名称', '物料名称', '单位']));
    expect(merged.model.fields.filter((field) => field.mappingKind === 'REPEAT_FIELD'))
      .toHaveLength(2);
  });

  it('uses the locator nested type when a physical field omits the duplicated root type', () => {
    const schema = { type: 'object', properties: {} };
    const item = createItem({
      status: 'CONFIRMED',
      payload: {
        ...createItem().payload,
        locatorType: undefined,
        bindingId: 'binding-product-name',
        mappingKind: 'SCALAR',
        suggestionLevel: 'ROOT',
        locator: {
          sheetId: 'sheet-1', sheetName: '生产单', labelAddress: 'A2',
          address: 'B2', locatorType: 'CELL_RANGE',
        },
      },
    });

    const merged = mergeRecognitionReview(schema, [], readFieldModel(schema, []), createReview(item));

    expect(merged.mapping[0]).toMatchObject({
      bindingId: 'binding-product-name',
      locatorType: 'CELL_RANGE',
    });
  });

  it('scopes legacy generic record paths to each physical component', () => {
    const schema = { type: 'object', properties: {} };
    const first = createItem({
      id: '44444444-4444-4444-4444-444444444444',
      status: 'CONFIRMED',
      payload: {
        ...createItem().payload,
        bindingId: 'first-child', parentBindingId: 'first-parent',
        mappingKind: 'REPEAT_FIELD', suggestionLevel: 'CHILD',
        dataPath: '/records/*/viscosity',
        locator: { sheetId: 'sheet-1', address: 'D8:I8', parentRange: 'A8:I37', locatorType: 'CELL_RANGE' },
      },
    });
    const second = createItem({
      id: '55555555-5555-5555-5555-555555555555',
      status: 'CONFIRMED',
      payload: {
        ...createItem().payload,
        bindingId: 'second-child', parentBindingId: 'second-parent',
        mappingKind: 'REPEAT_FIELD', suggestionLevel: 'CHILD',
        dataPath: '/records/*/viscosity',
        locator: { sheetId: 'sheet-2', address: 'D8:I8', parentRange: 'A8:I37', locatorType: 'CELL_RANGE' },
      },
    });
    const review = createReview(first);
    review.items = [first, second];

    const merged = mergeRecognitionReview(schema, [], readFieldModel(schema, []), review);
    const parents = merged.mapping.filter((item) => item.mappingKind === 'REPEAT_REGION');

    expect(parents).toHaveLength(2);
    expect(parents[0]?.dataPath).not.toBe(parents[1]?.dataPath);
    expect(merged.mapping.filter((item) => item.mappingKind === 'REPEAT_FIELD'))
      .toHaveLength(2);
  });

  it('preserves manually confirmed name, unit and type after workbook recognition', () => {
    const schema = { type: 'object', properties: {} };
    const initial = createItem({
      status: 'CONFIRMED',
      payload: {
        ...createItem().payload,
        bindingId: 'binding-product-name',
        labelPath: '基本信息 > 产品名称',
      },
    });
    const accepted = mergeRecognitionReview(
      schema, [], readFieldModel(schema, []), createReview(initial),
    );
    const field = accepted.model.fields[0]!;
    field.name = '人工产品名';
    field.unit = 'kg';
    field.valueType = 'number';
    field.manualOverrides = ['name', 'unit', 'valueType'];
    accepted.mapping[0]!.diagnostic = {
      ...accepted.mapping[0]!.diagnostic,
      manualOverrides: ['name', 'unit', 'valueType'],
      humanConfirmed: true,
    };

    const rerun = createItem({
      id: '66666666-6666-6666-6666-666666666666',
      status: 'CONFIRMED',
      fieldName: '模型新名称',
      valueType: 'string',
      payload: {
        ...createItem().payload,
        bindingId: 'new-binding-id',
        fieldName: '模型新名称',
        fieldCode: 'PRODUCT.NAME',
        valueType: 'string',
        unit: 'g',
        labelPath: '基本信息 > 模型新名称',
        locator: { sheetId: 'sheet-1', sheetName: '生产单', labelAddress: 'A2', address: 'B2' },
      },
    });
    const merged = mergeRecognitionReview(
      accepted.schema, accepted.mapping, accepted.model, createReview(rerun),
    );

    expect(merged.model.fields).toHaveLength(1);
    expect(merged.model.fields[0]).toMatchObject({
      name: '人工产品名', unit: 'kg', valueType: 'number', reviewStatus: 'CONFIRMED',
      manualOverrides: ['name', 'unit', 'valueType'],
    });
    expect(merged.mapping[0]?.labelPath).toBe('基本信息 > 人工产品名');
    expect(merged.mapping[0]?.bindingStatus).not.toBe('AMBIGUOUS');
  });

  it('keeps manual attributes but marks a moved field stale after workbook recognition', () => {
    const schema = { type: 'object', properties: {} };
    const initial = createItem({
      status: 'CONFIRMED',
      payload: {
        ...createItem().payload,
        bindingId: 'binding-product-name',
        labelPath: '基本信息 > 产品名称',
      },
    });
    const accepted = mergeRecognitionReview(
      schema, [], readFieldModel(schema, []), createReview(initial),
    );
    const field = accepted.model.fields[0]!;
    field.name = '人工产品名';
    field.unit = 'kg';
    field.valueType = 'number';
    field.manualOverrides = ['name', 'unit', 'valueType'];
    accepted.mapping[0]!.diagnostic = {
      ...accepted.mapping[0]!.diagnostic,
      manualOverrides: ['name', 'unit', 'valueType'],
      humanConfirmed: true,
    };

    const moved = createItem({
      id: '77777777-7777-7777-7777-777777777777',
      status: 'CONFIRMED',
      payload: {
        ...createItem().payload,
        bindingId: 'new-binding-id',
        fieldCode: 'PRODUCT.NAME',
        labelPath: '基本信息 > 模型新名称',
        locator: { sheetId: 'sheet-1', sheetName: '生产单', labelAddress: 'D8', address: 'E8' },
      },
    });
    const merged = mergeRecognitionReview(
      accepted.schema, accepted.mapping, accepted.model, createReview(moved),
    );

    expect(merged.model.fields[0]).toMatchObject({
      name: '人工产品名', unit: 'kg', valueType: 'number', reviewStatus: 'ISSUE',
      conflictCode: 'RECOGNITION_STRUCTURE_CHANGED',
    });
    expect(merged.model.fields[0]?.recognitionDiff).toMatchObject({ status: 'STALE' });
    expect(merged.mapping[0]).toMatchObject({ bindingStatus: 'AMBIGUOUS' });
    expect(merged.mapping[0]?.diagnostic?.recognitionDiff).toMatchObject({ status: 'STALE' });
  });

  it('refreshes stale automatic experiment semantics from the latest recognition run', () => {
    const schema = { type: 'object', properties: {} };
    const firstItem = createItem({
      status: 'CONFIRMED',
      payload: {
        ...createItem().payload,
        bindingId: 'stable-coating-appearance',
        experimentField: { domain: 'OTHER', field: 'DYNAMIC_VALUE' },
        experimentSemanticStatus: 'NEEDS_REVIEW',
        experimentSemanticSource: 'MODEL',
        experimentSemanticIssue: '旧识别存在歧义',
      },
    });
    const first = mergeRecognitionReview(schema, [], readFieldModel(schema, []), createReview(firstItem));
    const latestItem = createItem({
      id: '99999999-9999-9999-9999-999999999999',
      status: 'CONFIRMED',
      payload: {
        ...createItem().payload,
        bindingId: 'stable-coating-appearance',
        experimentField: { domain: 'TEST', field: 'VALUE' },
        experimentItemLabel: '性能测试 > 涂料外观',
        experimentSemanticConfidence: 0.96,
        experimentSemanticStatus: 'AUTO_CONFIRMED',
        experimentSemanticSource: 'FIELD_RULE',
      },
    });

    const merged = mergeRecognitionReview(
      first.schema, first.mapping, first.model, createReview(latestItem),
    );

    expect(merged.model.fields[0]).toMatchObject({
      experimentField: { domain: 'TEST', field: 'VALUE' },
      experimentItemLabel: '性能测试 > 涂料外观',
      experimentSemanticStatus: 'AUTO_CONFIRMED',
      experimentSemanticSource: 'FIELD_RULE',
      recognitionItemId: '99999999-9999-9999-9999-999999999999',
    });
    expect(merged.model.fields[0]?.experimentSemanticIssue).toBeUndefined();
    expect(merged.mapping[0]?.diagnostic?.recognitionItemId)
      .toBe('99999999-9999-9999-9999-999999999999');
  });

  it('preserves a human-confirmed experiment semantic across recognition runs', () => {
    const schema = { type: 'object', properties: {} };
    const first = mergeRecognitionReview(
      schema, [], readFieldModel(schema, []), createReview(createItem({ status: 'CONFIRMED' })),
    );
    Object.assign(first.model.fields[0]!, {
      experimentField: { domain: 'CONCLUSION', field: 'MAIN_CONCLUSION' },
      experimentSemanticConfidence: 1,
      experimentSemanticStatus: 'CONFIRMED',
      experimentSemanticSource: 'HUMAN',
    });
    const latestItem = createItem({
      status: 'CONFIRMED',
      payload: {
        ...createItem().payload,
        experimentField: { domain: 'OTHER', field: 'DYNAMIC_VALUE' },
        experimentSemanticStatus: 'NEEDS_REVIEW',
        experimentSemanticSource: 'MODEL',
        experimentSemanticIssue: '模型仍有歧义',
      },
    });

    const merged = mergeRecognitionReview(
      first.schema, first.mapping, first.model, createReview(latestItem),
    );

    expect(merged.model.fields[0]).toMatchObject({
      experimentField: { domain: 'CONCLUSION', field: 'MAIN_CONCLUSION' },
      experimentSemanticStatus: 'CONFIRMED',
      experimentSemanticSource: 'HUMAN',
    });
    expect(merged.model.fields[0]?.experimentSemanticIssue).toBeUndefined();
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
      columnTable: 0,
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
