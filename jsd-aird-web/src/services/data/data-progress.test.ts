import { dataParseProgress, dataParseStageLabel } from './data-progress';

describe('data parsing progress', () => {
  it('does not reuse post-parse operation progress', () => {
    expect(dataParseProgress({ status: 'WAITING_MAPPING', progress: 35 })).toBe(100);
    expect(dataParseProgress({ status: 'VALIDATING', progress: 55 })).toBe(100);
    expect(dataParseProgress({ status: 'COMMITTING', progress: 90 })).toBe(100);
  });

  it('keeps queued and active parsing progress meaningful', () => {
    expect(dataParseProgress({ status: 'QUEUED', progress: 1 })).toBe(0);
    expect(dataParseProgress({ status: 'PARSING', progress: 45 })).toBe(45);
    expect(dataParseStageLabel({ status: 'PARSING', currentStage: 'ANALYZING_STRUCTURE' })).toBe('正在分析结构');
  });
});
