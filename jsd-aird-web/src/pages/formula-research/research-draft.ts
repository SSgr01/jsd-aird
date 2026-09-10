import type {
  ResearchConfirmationStatus,
  ResearchConstraints,
  ResearchDraft,
  ResearchGoal,
  ResearchRequest,
  ResearchRun,
  UnresolvedResearchField,
} from '@/services/formula-research';

export interface FixedMaterialForm {
  materialCode?: string;
  ratioPercent?: number;
}

export interface RangeForm {
  code?: string;
  minimum?: number;
  maximum?: number;
}

export interface ResearchFormValues {
  baselineAnalysisRowId?: string;
  goals: ResearchGoal[];
  substrate?: string;
  requiredMaterials: string[];
  forbiddenMaterials: string[];
  fixedMaterials: FixedMaterialForm[];
  materialRanges: RangeForm[];
  processRanges: RangeForm[];
  maxMaterialCount?: number;
  requireCostCheck: boolean;
  requireInventoryCheck: boolean;
  candidateCount: number;
}

export const emptyConstraints = (): ResearchConstraints => ({
  requiredMaterials: [],
  forbiddenMaterials: [],
  fixedMaterials: {},
  materialRanges: {},
  forbiddenMaterialCombinations: [],
  processRanges: {},
  requireCostCheck: false,
  requireInventoryCheck: false,
});

export const emptyResearchDraft = (): ResearchDraft => ({
  goals: [],
  context: {},
  constraints: emptyConstraints(),
  candidateCount: 4,
});

const entriesToRanges = (values: Record<string, { minimum?: number; maximum?: number }>): RangeForm[] =>
  Object.entries(values).map(([code, range]) => ({ code, ...range }));

export function draftToFormValues(draft: ResearchDraft): ResearchFormValues {
  return {
    baselineAnalysisRowId: draft.baselineAnalysisRowId,
    goals: draft.goals,
    substrate: typeof draft.context.substrate === 'string' ? draft.context.substrate : undefined,
    requiredMaterials: draft.constraints.requiredMaterials,
    forbiddenMaterials: draft.constraints.forbiddenMaterials,
    fixedMaterials: Object.entries(draft.constraints.fixedMaterials).map(([materialCode, ratioPercent]) => ({
      materialCode,
      ratioPercent,
    })),
    materialRanges: entriesToRanges(draft.constraints.materialRanges),
    processRanges: entriesToRanges(draft.constraints.processRanges),
    maxMaterialCount: draft.constraints.maxMaterialCount,
    requireCostCheck: draft.constraints.requireCostCheck,
    requireInventoryCheck: draft.constraints.requireInventoryCheck,
    candidateCount: draft.candidateCount,
  };
}

const rangeMap = (values?: RangeForm[]) => Object.fromEntries(
  (values ?? [])
    .filter((item): item is Required<Pick<RangeForm, 'code'>> & RangeForm => Boolean(item.code))
    .map((item) => [item.code, { minimum: item.minimum, maximum: item.maximum }]),
);

export function formValuesToDraft(values: ResearchFormValues): ResearchDraft {
  return {
    goals: values.goals ?? [],
    context: values.substrate ? { substrate: values.substrate } : {},
    constraints: {
      requiredMaterials: values.requiredMaterials ?? [],
      forbiddenMaterials: values.forbiddenMaterials ?? [],
      fixedMaterials: Object.fromEntries((values.fixedMaterials ?? [])
        .filter((item): item is Required<FixedMaterialForm> => Boolean(item.materialCode) && item.ratioPercent !== undefined)
        .map((item) => [item.materialCode, item.ratioPercent])),
      materialRanges: rangeMap(values.materialRanges),
      forbiddenMaterialCombinations: [],
      processRanges: rangeMap(values.processRanges),
      maxMaterialCount: values.maxMaterialCount,
      requireCostCheck: Boolean(values.requireCostCheck),
      requireInventoryCheck: Boolean(values.requireInventoryCheck),
    },
    candidateCount: Math.min(4, Math.max(1, values.candidateCount || 4)),
    baselineAnalysisRowId: values.baselineAnalysisRowId,
  };
}

export function evaluateDraft(
  draft: ResearchDraft,
  runType: 'FORMULA_PREDICTION' | 'EXPERIMENT_OPTIMIZATION',
): { status: ResearchConfirmationStatus; unresolved: UnresolvedResearchField[] } {
  const unresolved: UnresolvedResearchField[] = [];
  if (!draft.goals.length) unresolved.push({ field: 'goals', code: 'TARGET_REQUIRED', message: '请至少选择一个性能目标' });
  draft.goals.forEach((goal) => {
    if (['AT_LEAST', 'AT_MOST', 'MATCH'].includes(goal.mode) && goal.value === undefined) {
      unresolved.push({ field: `goals.${goal.targetKey}`, code: 'TARGET_VALUE_REQUIRED', message: '请填写目标值' });
    }
  });
  if (runType === 'EXPERIMENT_OPTIMIZATION' && !draft.baselineAnalysisRowId) {
    unresolved.push({ field: 'baselineAnalysisRowId', code: 'BASELINE_REQUIRED', message: '请选择唯一的基线实验' });
  }
  const required = new Set(draft.constraints.requiredMaterials);
  const overlap = draft.constraints.forbiddenMaterials.filter((code) => required.has(code));
  if (overlap.length) {
    unresolved.push({
      field: 'constraints.materials',
      code: 'MATERIAL_CONSTRAINT_CONFLICT',
      message: `同一材料不能同时设为必选和禁用：${overlap.join('、')}`,
    });
  }
  return {
    status: unresolved.some((item) => item.code.includes('CONFLICT')) ? 'CONFLICT' : unresolved.length ? 'NEEDS_INPUT' : 'READY',
    unresolved,
  };
}

export function toResearchRequest(
  draft: ResearchDraft,
  taskProfileCode: string,
  idempotencyKey: string,
): ResearchRequest {
  return {
    taskProfileCode,
    idempotencyKey,
    baselineAnalysisRowId: draft.baselineAnalysisRowId,
    goals: draft.goals,
    context: draft.context,
    constraints: draft.constraints,
    candidateCount: draft.candidateCount,
  };
}

const candidateIndex = (text: string) => {
  if (/第二|2\s*(?:个|组|条)?方案/.test(text)) return 1;
  if (/第三|3\s*(?:个|组|条)?方案/.test(text)) return 2;
  if (/第四|4\s*(?:个|组|条)?方案/.test(text)) return 3;
  return 0;
};

export function explainExistingRun(text: string, run?: ResearchRun): string | undefined {
  if (!run || !['SUCCEEDED', 'PARTIAL'].includes(run.status)) return undefined;
  if (/相似实验|相似案例|哪些案例/.test(text)) {
    const summaries = (run.result?.targetStatistics ?? []).map((item) => {
      const references = (item.cases ?? []).slice(0, 3).map((entry) => entry.experimentNo).join('、');
      return `${item.name}：${item.caseCount}条可比较案例${references ? `，主要包括${references}` : ''}`;
    });
    return summaries.length ? summaries.join('；') : '本次研究运行没有保存可展示的相似案例。';
  }
  if (/为什么|依据|推荐理由/.test(text) && run.candidates.length) {
    const candidate = run.candidates[Math.min(candidateIndex(text), run.candidates.length - 1)]!;
    const check = candidate.ruleCheck as { afterGeneration?: { checks?: Array<{ status: string }> } };
    const passed = check.afterGeneration?.checks?.filter((item) => item.status === 'PASSED').length ?? 0;
    const evidenceCount = Object.values(candidate.evidence).flat().length;
    return `${candidate.title}属于“${candidate.strategy}”策略，可信度为${candidate.confidence}；`+
      `它通过了${passed}项已记录的硬规则检查，并引用了${evidenceCount}条本次运行保存的案例证据。`+
      '这里仅解释既有研究结果，不重新计算或改写候选数值。';
  }
  return undefined;
}
