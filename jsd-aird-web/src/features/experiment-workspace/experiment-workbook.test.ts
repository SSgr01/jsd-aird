import { describe, expect, it } from 'vitest';
import {
  buildExperimentSnapshot,
  parseExperimentSnapshot,
} from '@/features/experiment-workspace/experiment-workbook';
import type { ExperimentModel } from '@/services/experiments/experiment-api';

describe('experiment-workbook round trip', () => {
  const model: ExperimentModel = {
    title: '环氧固化实验',
    purpose: '验证固化剂比例对硬度的影响',
    plan: '在 60°C 下固化 24h',
    conclusion: { resultStatus: 'SUCCESS', failureCategory: '', mainConclusion: '比例 3:1 效果最佳' },
    formulaItems: [
      { materialName: '环氧树脂', ratio: '10', actualQty: '100', unit: 'g' },
      { materialName: '固化剂', ratio: '1', actualQty: '10', unit: 'g' },
    ],
    processSteps: [
      { stepNo: '1', operation: '称量', temperature: '25', duration: '10min' },
    ],
    testResults: [{ testItem: '硬度', value: '85', unit: 'HD', judgement: '合格' }],
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
    expect(round.formulaItems?.[0]).toEqual({ materialName: '环氧树脂', ratio: '10', actualQty: '100', unit: 'g' });
    expect(round.processSteps?.[0]?.operation).toBe('称量');
    expect(round.testResults?.[0]?.judgement).toBe('合格');
    expect(round.events?.[0]?.eventType).toBe('INFO');
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
  });
});
