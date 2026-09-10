import type { TargetStatistics } from '@/services/formula-research';

export const statisticsLevelText: Record<TargetStatistics['statisticsLevel'], string> = {
  NONE: '暂无可比较案例',
  CASES_ONLY: '仅展示相似案例',
  SIMPLE_RANGE: '简单历史范围',
  FULL_STATISTICS: '完整案例统计',
};

export const confidenceText = { NONE: '数据不足', LOW: '低', MEDIUM: '中', MODEL: '模型评估' } as const;

export function formatResearchNumber(value: number | string | undefined): string {
  if (value === undefined) return '—';
  if (typeof value === 'string') return value;
  return Number(value.toFixed(2)).toString();
}

export function statisticsSummary(value: TargetStatistics): string {
  if (value.statisticsLevel === 'NONE') return '当前没有可比较的正式实验，不提供性能估算。';
  if (value.statisticsLevel === 'CASES_ONLY') return `已有 ${value.caseCount} 条相似案例，仅展示真实结果，不提供综合点估计。`;
  if (value.statisticsLevel === 'SIMPLE_RANGE') {
    if (value.minimum !== undefined && value.maximum !== undefined) return `历史范围 ${formatResearchNumber(value.minimum)}～${formatResearchNumber(value.maximum)}${value.unit || ''}，暂不提供综合点估计。`;
    return `已有 ${value.caseCount} 条案例，可展示分布和方向提示，暂不提供综合点估计。`;
  }
  if (value.pointEstimate !== undefined) {
    const modelInterval = value.q25 !== undefined && value.q75 !== undefined
      ? `；相似案例Q25～Q75为 ${formatResearchNumber(value.q25)}～${formatResearchNumber(value.q75)}${value.unit || ''}`
      : '';
    const historicalInterval = value.q25 !== undefined && value.q75 !== undefined
      ? `，Q25～Q75 为 ${formatResearchNumber(value.q25)}～${formatResearchNumber(value.q75)}${value.unit || ''}`
      : '';
    const estimate = `${formatResearchNumber(value.pointEstimate)}${value.unit || ''}`;
    return value.predictionSource === 'MODEL'
      ? `模型预测 ${estimate}${modelInterval}。`
      : `加权历史估计 ${estimate}${historicalInterval}。`;
  }
  return `已有 ${value.caseCount} 条案例，已生成完整案例统计。`;
}
