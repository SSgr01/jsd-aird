import { describe, expect, it } from 'vitest';
import {
  buildExperimentSnapshot,
  parseExperimentSnapshot,
  withoutOcrSourceSheet,
} from '@/features/experiment-workspace/experiment-workbook';
import type { ExperimentModel } from '@/services/experiments/experiment-api';

describe('experiment-workbook round trip', () => {
  const model: ExperimentModel = {
    schemaVersion: 2,
    title: '环氧固化实验',
    purpose: '验证固化剂比例对硬度的影响',
    plan: '在 60°C 下固化 24h',
    conclusion: { resultStatus: 'SUCCESS', failureCategory: '', mainConclusion: '比例 3:1 效果最佳' },
    formulaItems: [
      {
        itemId: 'formula-1',
        sourceRefs: [{ sheetId: '配方', cellRange: 'A2:D2', extensionRef: 'keep' }],
        materialId: 'material-1',
        materialCode: 'EP-A',
        materialName: '环氧树脂',
        ratio: '10',
        actualQty: '100',
        unit: 'g',
        rawValue: '100',
        rawUnit: '克',
        extensionField: 'formula-extension',
      },
      {
        itemId: 'formula-2', sourceRefs: [], materialName: '固化剂', ratio: '1', actualQty: '10', unit: 'g',
      },
    ],
    processSteps: [
      {
        itemId: 'process-1', sourceRefs: [], stepNo: '1', operation: '称量', temperature: '25', duration: '10min',
      },
    ],
    testResults: [{
      itemId: 'test-1',
      sourceRefs: [{ sheetId: '测试', cellRange: 'B2' }],
      testItem: '硬度',
      value: '85',
      unit: 'HD',
      judgement: '合格',
      testMethod: '邵氏硬度',
      testCondition: '23°C',
      substrate: 'PET',
      extensionField: 'test-extension',
    }],
    events: [{ eventTime: '2026-08-01', eventType: 'INFO', description: '无', action: '无' }],
  };

  it('preserves scalar record fields', () => {
    const snapshot = buildExperimentSnapshot(model, 'exp-1');
    const round = parseExperimentSnapshot(snapshot, model);
    expect(round.title).toBe('环氧固化实验');
    expect(round.purpose).toBe('验证固化剂比例对硬度的影响');
    expect(round.plan).toBe('在 60°C 下固化 24h');
    expect(round.conclusion?.resultStatus).toBe('SUCCESS');
    expect(round.conclusion?.mainConclusion).toBe('比例 3:1 效果最佳');
  });

  it('preserves detail records', () => {
    const snapshot = buildExperimentSnapshot(model, 'exp-1');
    const round = parseExperimentSnapshot(snapshot, model);
    expect(round.formulaItems).toHaveLength(2);
    expect(round.formulaItems?.[0]).toEqual(model.formulaItems?.[0]);
    expect(round.processSteps?.[0]?.operation).toBe('称量');
    expect(round.testResults?.[0]?.judgement).toBe('合格');
    expect(round.events?.[0]?.eventType).toBe('INFO');
  });

  it('keeps stable item ids and unknown fields when visible cells are edited', () => {
    const snapshot = buildExperimentSnapshot(model, 'exp-1');
    const sheets = snapshot.sheets as Record<string, Record<string, unknown>>;
    const formulaCells = sheets['sheet-配方数据']?.cellData as Record<string, Record<string, { v: unknown }>>;
    formulaCells['2']!['1'] = { v: '12' };

    const round = parseExperimentSnapshot(snapshot, model);
    const formula = round.formulaItems?.[0];
    expect(formula?.itemId).toBe('formula-1');
    expect(formula?.ratio).toBe('12');
    expect(formula?.materialId).toBe('material-1');
    expect(formula?.materialCode).toBe('EP-A');
    expect(formula?.sourceRefs).toEqual(model.formulaItems?.[0]?.sourceRefs);
    expect(formula?.extensionField).toBe('formula-extension');
    expect(round.testResults?.[0]?.testMethod).toBe('邵氏硬度');
    expect(round.testResults?.[0]?.testCondition).toBe('23°C');
    expect(round.testResults?.[0]?.substrate).toBe('PET');
    expect(round.testResults?.[0]?.extensionField).toBe('test-extension');
  });

  it('moves extension fields with their hidden stable item ids', () => {
    const snapshot = buildExperimentSnapshot(model, 'exp-1');
    const sheets = snapshot.sheets as Record<string, Record<string, unknown>>;
    const formulaCells = sheets['sheet-配方数据']?.cellData as Record<string, Record<string, { v: unknown }>>;
    const first = formulaCells['2'];
    formulaCells['2'] = formulaCells['3']!;
    formulaCells['3'] = first!;

    const round = parseExperimentSnapshot(snapshot, model);
    expect(round.formulaItems?.[0]?.itemId).toBe('formula-2');
    expect(round.formulaItems?.[1]?.itemId).toBe('formula-1');
    expect(round.formulaItems?.[1]?.extensionField).toBe('formula-extension');
    expect(round.formulaItems?.[1]?.sourceRefs).toEqual(model.formulaItems?.[0]?.sourceRefs);
  });

  it('preserves imported null values and their original missing marker', () => {
    const importedModel: ExperimentModel = {
      ...model,
      formulaItems: [{
        itemId: 'formula-missing',
        sourceRefs: [{ sheetId: 'Sheet1', cell: 'D20' }],
        materialName: 'SJ-230',
        ratio: null,
        rawValue: '/',
        rawUnit: '',
      }],
      testResults: [{
        itemId: 'test-missing',
        sourceRefs: [{ sheetId: 'Sheet1', cell: 'D30' }],
        testItem: '附着力',
        value: null,
        rawValue: '/',
        unit: '',
        judgement: '',
      }],
    };

    const snapshot = buildExperimentSnapshot(importedModel, 'exp-imported');
    const round = parseExperimentSnapshot(snapshot, importedModel);

    expect(round.formulaItems?.[0]?.ratio).toBeNull();
    expect(round.formulaItems?.[0]?.rawValue).toBe('/');
    expect(round.testResults?.[0]?.value).toBeNull();
    expect(round.testResults?.[0]?.rawValue).toBe('/');

    const secondSnapshot = buildExperimentSnapshot(round, 'exp-imported');
    const secondRound = parseExperimentSnapshot(secondSnapshot, round);
    expect(secondRound.formulaItems?.[0]?.ratio).toBeNull();
    expect(secondRound.testResults?.[0]?.value).toBeNull();
  });

  it('renders record sheet with key-value layout', () => {
    const snapshot = buildExperimentSnapshot(model, 'exp-1');
    const sheets = snapshot.sheets as Record<string, Record<string, unknown>>;
    expect(sheets['sheet-record']).toBeDefined();
    // 表头
    const cellData = sheets['sheet-record']?.cellData as Record<string, Record<string, { v: unknown }>>;
    expect(cellData['1']?.['0']?.v).toBe('字段');
    expect(cellData['1']?.['1']?.v).toBe('内容');
    // 第一行标签与值
    expect(cellData['2']?.['0']?.v).toBe('实验标题');
    expect(cellData['2']?.['1']?.v).toBe('环氧固化实验');
    const formulaSheet = sheets['sheet-配方数据'];
    expect((formulaSheet?.columnData as Record<string, { hd?: number }>)['4']?.hd).toBe(1);
  });

  it('keeps an Excel template snapshot as the independent experiment document', () => {
    const templateSnapshot = {
      id: 'published-template',
      sheetOrder: ['sheet-template'],
      sheets: {
        'sheet-template': {
          id: 'sheet-template',
          name: '模板表格',
          rowCount: 20,
          columnCount: 10,
          cellData: { 0: { 0: { v: '模板内容' } } },
        },
      },
    };
    const templateModel: ExperimentModel = {
      title: '模板实验',
      documentFormat: 'excel',
    };

    expect(buildExperimentSnapshot(templateModel, 'exp-template', templateSnapshot)).toBe(templateSnapshot);
    expect(parseExperimentSnapshot(templateSnapshot, templateModel).documentSnapshot).toBe(templateSnapshot);
  });

  it('keeps an AI draft usable when the selected template has no field coordinates', () => {
    const templateSnapshot = {
      id: 'published-template',
      sheetOrder: ['sheet-template'],
      sheets: {
        'sheet-template': { id: 'sheet-template', name: '客户模板', rowCount: 20, columnCount: 10, cellData: {} },
      },
    };
    const optimizationModel: ExperimentModel = {
      title: '优化实验', documentFormat: 'excel',
      formulaItems: [{ itemId: 'formula-1', sourceRefs: [], materialId: 'm1', materialName: '树脂', ratio: 76.7, actualQty: '', unit: '%' }],
      aiSource: {
        plannedInputs: { TEST_TEMPERATURE: 25 },
        predictions: { targets: [
          { targetName: '60°光泽', status: 'SUCCEEDED', result: { resultType: 'CONTINUOUS', value: 51.2, lower: 47, upper: 55, unit: 'GU' } },
          { targetName: 'UV表干', status: 'SUCCEEDED', result: { resultType: 'BINARY', label: '表干', probability: 0.92 } },
        ] },
      },
    };

    const snapshot = buildExperimentSnapshot(optimizationModel, 'exp-ai', templateSnapshot);
    const sheets = snapshot.sheets as Record<string, Record<string, unknown>>;
    const formula = sheets['sheet-配方数据']?.cellData as Record<string, Record<string, { v: unknown }>>;
    const conditions = sheets['sheet-ai-conditions']?.cellData as Record<string, Record<string, { v: unknown }>>;
    const predictions = sheets['sheet-ai-prediction-reference']?.cellData as Record<string, Record<string, { v: unknown }>>;
    expect(snapshot.sheetOrder).toEqual(['sheet-template', 'sheet-配方数据', 'sheet-ai-conditions', 'sheet-ai-prediction-reference']);
    expect(formula?.['2']?.['0']?.v).toBe('树脂');
    expect(formula?.['2']?.['1']?.v).toBe(76.7);
    expect(conditions?.['2']?.['0']?.v).toBe('测试温度');
    expect(conditions?.['2']?.['1']?.v).toBe(25);
    expect(predictions?.['3']?.['1']?.v).toBe('51.2 GU');
    expect(predictions?.['3']?.['2']?.v).toBe('47～55 GU');
    expect(predictions?.['4']?.['1']?.v).toBe('表干 · 92%');
    expect(sheets['sheet-record']).toBeUndefined();
    expect(parseExperimentSnapshot(snapshot, optimizationModel).formulaItems?.[0]?.ratio).toBe('76.7');
  });

  it('writes AI recipe and test conditions into the selected template without changing its layout', () => {
    const templateSnapshot = {
      id: 'published-template',
      sheetOrder: ['customer-sheet'],
      sheets: {
        'customer-sheet': {
          id: 'customer-sheet', name: '配方实验记录', rowCount: 12, columnCount: 6,
          cellData: {
            '0': { '0': { v: '材料' }, '1': { v: '配方比例' }, '2': { v: '测试温度' }, '3': { v: '实测结果' } },
            '1': { '0': { v: '' }, '1': { v: '', s: 9 }, '2': { v: '' }, '3': { v: '' } },
            '2': { '0': { v: '' }, '1': { v: '', s: 9 }, '2': { v: '' }, '3': { v: '' } },
          },
        },
      },
    };
    const optimizationModel: ExperimentModel = {
      title: '树脂配方优化', documentFormat: 'excel',
      formulaItems: [
        { itemId: 'formula-1', sourceRefs: [], materialName: '树脂', ratio: 76.7, actualQty: '', unit: '%' },
        { itemId: 'formula-2', sourceRefs: [], materialName: '助剂', ratio: 23.3, actualQty: '', unit: '%' },
      ],
      dynamicValues: { TEST_TEMPERATURE: 25 },
      aiSource: {
        plannedInputs: { TEST_TEMPERATURE: 25 },
        predictions: { targets: [{ targetName: '光泽', status: 'SUCCEEDED', result: { resultType: 'CONTINUOUS', value: 51.2 } }] },
        templateBindings: [
          {
            fieldCode: 'FORMULA_MATERIAL_NAME', targetPath: '/editModel/formulaItems/*/materialName',
            mappingKind: 'REPEAT_FIELD', repeatAxis: 'ROW', recordHeight: 1, recordStride: 1,
            valueSource: 'USER_INPUT', locator: { sheetId: 'customer-sheet', valueRange: 'A2:A8' },
          },
          {
            fieldCode: 'FORMULA_RATIO', targetPath: '/editModel/formulaItems/*/ratio',
            mappingKind: 'REPEAT_FIELD', repeatAxis: 'ROW', recordHeight: 1, recordStride: 1,
            valueSource: 'USER_INPUT', locator: { sheetId: 'customer-sheet', valueRange: 'B2:B8' },
          },
          {
            fieldCode: 'TEST_TEMPERATURE', targetPath: '/editModel/dynamicValues/*',
            mappingKind: 'SCALAR', valueSource: 'USER_INPUT', locator: { sheetId: 'customer-sheet', valueRange: 'C2' },
          },
        ],
      },
    };

    const snapshot = buildExperimentSnapshot(optimizationModel, 'exp-ai-template', templateSnapshot);
    const sheets = snapshot.sheets as Record<string, Record<string, unknown>>;
    const cells = sheets['customer-sheet']?.cellData as Record<string, Record<string, { v: unknown; s?: number }>>;

    expect(snapshot.sheetOrder).toEqual(['customer-sheet', 'sheet-ai-prediction-reference']);
    expect(cells['1']?.['0']?.v).toBe('树脂');
    expect(cells['1']?.['1']).toEqual({ v: 76.7, s: 9 });
    expect(cells['2']?.['0']?.v).toBe('助剂');
    expect(cells['2']?.['1']?.v).toBe(23.3);
    expect(cells['1']?.['2']?.v).toBe(25);
    expect(cells['1']?.['3']?.v).toBe('');
    expect(sheets['sheet-ai-recipe-plan']).toBeUndefined();
    expect(sheets['sheet-ai-conditions']).toBeUndefined();
    expect(sheets['sheet-record']).toBeUndefined();
    cells['1']!['1'] = { ...cells['1']!['1']!, v: '77' };
    const edited = parseExperimentSnapshot(snapshot, optimizationModel);
    expect(edited.formulaItems?.[0]?.ratio).toBe('77');
    expect(edited.dynamicValues?.TEST_TEMPERATURE).toBe('25');
  });

  it('adds AI draft sheets when the API has already attached a blank template snapshot', () => {
    const optimizationModel: ExperimentModel = {
      title: '优化实验',
      documentFormat: 'word',
      documentSnapshot: {
        id: 'blank-template-copy',
        sheetOrder: ['sheet-1'],
        sheets: {
          'sheet-1': { id: 'sheet-1', name: 'Sheet1', rowCount: 200, columnCount: 26, cellData: {} },
        },
      },
      formulaItems: [
        { itemId: 'formula-1', sourceRefs: [], materialId: 'm1', materialName: '树脂', ratio: 73.70012048, actualQty: '', unit: '%' },
        { itemId: 'formula-2', sourceRefs: [], materialId: 'm2', materialName: '助剂', ratio: 26.29987952, actualQty: '', unit: '%' },
      ],
      aiSource: {
        plannedInputs: { TEST_TEMPERATURE: 25 },
        predictions: { targets: [{ targetName: '60°光泽', status: 'SUCCEEDED', result: { resultType: 'CONTINUOUS', value: 51.2, lower: 47, upper: 55, unit: 'GU' } }] },
      },
    };

    const snapshot = buildExperimentSnapshot(optimizationModel, 'exp-ai-with-snapshot');
    const sheets = snapshot.sheets as Record<string, Record<string, unknown>>;
    const formula = sheets['sheet-配方数据']?.cellData as Record<string, Record<string, { v: unknown }>>;
    const conditions = sheets['sheet-ai-conditions']?.cellData as Record<string, Record<string, { v: unknown }>>;
    const predictions = sheets['sheet-ai-prediction-reference']?.cellData as Record<string, Record<string, { v: unknown }>>;

    expect(snapshot.sheetOrder).toEqual(expect.arrayContaining(['sheet-配方数据', 'sheet-ai-conditions', 'sheet-ai-prediction-reference']));
    expect(snapshot.sheetOrder).not.toContain('sheet-1');
    expect((snapshot.sheetOrder as string[])[0]).toBe('sheet-配方数据');
    expect(formula?.['2']?.['0']?.v).toBe('树脂');
    expect(formula?.['2']?.['1']?.v).toBe(73.7);
    expect(formula?.['3']?.['1']?.v).toBe(26.3);
    expect((sheets['sheet-配方数据']?.columnData as Record<string, Record<string, unknown>>)?.['0']?.w).toBe(240);
    expect((sheets['sheet-配方数据']?.columnData as Record<string, Record<string, unknown>>)?.['4']?.hd).toBe(1);
    expect(conditions?.['2']?.['1']?.v).toBe(25);
    expect(predictions?.['3']?.['1']?.v).toBe('51.2 GU');
    expect(sheets['sheet-1']).toBeUndefined();
  });

  it('keeps a blank experiment free of starter content', () => {
    const blankWord = buildExperimentSnapshot(
      { title: '空白 Word 实验', documentFormat: 'word', blankDocument: true },
      'exp-blank-word',
    );
    expect((blankWord.body as Record<string, unknown>)?.dataStream).toBe('\r\n');

    const blankExcel = buildExperimentSnapshot(
      { title: '空白 Excel 实验', documentFormat: 'excel', blankDocument: true },
      'exp-blank-excel',
    );
    const sheets = blankExcel.sheets as Record<string, Record<string, unknown>>;
    expect(blankExcel.sheetOrder).toEqual(['sheet-1']);
    expect(sheets['sheet-1']?.cellData).toEqual({});
  });

  it('removes the legacy OCR source sheet and its drawing resource', () => {
    const snapshot = {
      sheetOrder: ['sheet-result', 'sheet-ocr-text', 'sheet-content'],
      sheets: {
        'sheet-result': { id: 'sheet-result', name: '识别结果' },
        'sheet-ocr-text': { id: 'sheet-ocr-text', name: 'OCR原文' },
        'sheet-content': { id: 'sheet-content', name: '识别文本' },
      },
      resources: [
        { name: 'SHEET_DRAWING_PLUGIN', data: '{"sheet-ocr-text":{}}' },
        { name: 'OTHER_PLUGIN', data: '{}' },
      ],
    };
    const sanitized = withoutOcrSourceSheet(snapshot);
    expect(sanitized.sheetOrder).toEqual(['sheet-result', 'sheet-content']);
    expect((sanitized.sheets as Record<string, unknown>)['sheet-ocr-text']).toBeUndefined();
    expect(sanitized.resources).toEqual([
      { name: 'SHEET_DRAWING_PLUGIN', data: '{}' },
      { name: 'OTHER_PLUGIN', data: '{}' },
    ]);
  });

  it('keeps user pictures on other sheets while removing OCR drawings', () => {
    const picture = { data: { 'user-picture': { source: 'data:image/png;base64,test' } }, order: ['user-picture'] };
    const source = {
      sheetOrder: ['sheet-1', 'sheet-ocr-text'],
      sheets: { 'sheet-1': { name: '结果' }, 'sheet-ocr-text': { name: 'OCR原文' } },
      resources: [{ name: 'SHEET_DRAWING_PLUGIN', data: JSON.stringify({ 'sheet-1': picture, 'sheet-ocr-text': {} }) }],
    };
    const result = withoutOcrSourceSheet(source);
    const resources = result.resources as Array<{ data: string }>;
    expect(JSON.parse(resources[0]!.data)).toEqual({ 'sheet-1': picture });
    expect(JSON.parse(source.resources[0]!.data)).toHaveProperty('sheet-ocr-text');
  });
});
