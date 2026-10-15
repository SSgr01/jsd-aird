import type { FormulaContext, FormulaRequest, FormulaTarget, ResearchRun } from '@/services/ai-rnd/formula-design-api'

export const formulaTargets: FormulaTarget[] = [
  { targetId: 'dry-gloss', code: 'GLOSS.DRY', name: '60°光泽', unit: 'GU', testMethod: '干膜', category: '膜性能', valueType: 'CONTINUOUS', available: true, modelVersionId: 'model-dry', operators: ['AT_LEAST', 'MATCH', 'RANGE', 'MAXIMIZE'], unavailableReasons: [] },
  { targetId: 'wet-gloss', code: 'GLOSS.WET', name: '60°光泽', unit: 'GU', testMethod: '湿膜', category: '膜性能', valueType: 'CONTINUOUS', available: true, modelVersionId: 'model-wet', operators: ['AT_LEAST', 'MATCH', 'RANGE', 'MAXIMIZE'], unavailableReasons: [] },
]
export function formulaContext(ids: string[] = []): FormulaContext {
  return { targets: formulaTargets, selected: formulaTargets.filter(target => ids.includes(target.targetId)), candidateCountDefault: 4,
    materialIntersection: ids.length ? [
      { materialId: 'resin', name: '丙烯酸树脂', supportedRanges: [{ targetId: ids[0]!, targetName: '60°光泽', minimum: 60, maximum: 80 }] },
      { materialId: 'additive', name: '流平剂', supportedRanges: [{ targetId: ids[0]!, targetName: '60°光泽', minimum: 20, maximum: 40 }] },
    ] : [],
    requiredInputs: ids.length ? [{ code: 'TEMPERATURE_C', name: '温度', unit: '°C', fieldVersionId: 'temperature-v1', valueType: 'NUMBER', required: true, supportedRanges: [{ targetId: ids[0]!, targetName: '60°光泽', minimum: 20, maximum: 32 }] }] : [],
  }
}
export function formulaRun(request?: FormulaRequest): ResearchRun {
  const targets = request?.typedGoals.map(goal => goal.targetId) ?? ['dry-gloss']
  const context = formulaContext(targets)
  return { id: 'run-1', runType: 'FORMULA_PREDICTION', executionStatus: 'SUCCEEDED', outcomeStatus: 'SUCCEEDED', progress: 100, currentStage: 'COMPLETED', requestedCandidateCount: 4, actualCandidateCount: 1, modelBindings: {}, requestId: 'request-1', simulation: true,
    frozenEvidence: request ? { ...request, displayContext: context, simulation: true } : undefined,
    candidates: [{ id: 'candidate-1', candidateNo: 1, title: '候选配方 1', formula: { components: [{ materialId: 'resin', ratio: 75 }, { materialId: 'additive', ratio: 25 }] }, inputs: { TEMPERATURE_C: 27 }, score: 999, targetResults: { targets: targets.map(targetId => ({ targetId, status: 'SUCCEEDED', result: { resultType: 'CONTINUOUS', value: 85.123456, unit: 'GU' }, applicability: { status: 'IN_DOMAIN' } })) } }],
  }
}
