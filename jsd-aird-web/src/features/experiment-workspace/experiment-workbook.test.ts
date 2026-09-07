import { describe, expect, it } from 'vitest';
import {
  buildExperimentSnapshot,
  parseExperimentSnapshot,
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
      documentSnapshot: templateSnapshot,
    };

    expect(buildExperimentSnapshot(templateModel, 'exp-template', templateSnapshot)).toBe(templateSnapshot);
    expect(parseExperimentSnapshot(templateSnapshot, templateModel).documentSnapshot).toBe(templateSnapshot);
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
});
