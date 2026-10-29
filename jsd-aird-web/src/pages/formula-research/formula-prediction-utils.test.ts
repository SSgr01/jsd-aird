import { describe, expect, it } from 'vitest'
import { formulaContext, formulaTargets } from '@/test/formula-prediction-fixtures'
import { buildFormulaRequest, emptyFormulaForm, formulaFingerprint, formulaMaterialName, formulaSpaceError, formulaUnavailable, goalError, goalMet, learnedRange, restoreFormulaForm, syncFormulaForm } from './formula-prediction-utils'

describe('formula input and result consistency', () => {
  it('keeps workbench material column names aligned with the training table', () => {
    expect(formulaMaterialName({ name: 'ADDITIVE', sourceColumnName: 'ADDITIVE比例' })).toBe('ADDITIVE比例')
    expect(formulaMaterialName({ name: 'ADDITIVE' })).toBe('助剂')
  })
  it('distinguishes missing proportion inputs, incomplete formulas and a ratio selected as Y', () => {
    const base = formulaTargets[0]!
    expect(formulaUnavailable({ ...base, unavailableReasons: ['MODEL_NOT_FORMULA_ENABLED'] })).toBe('训练时未选择材料用量作为 X，请加入用量列后训练')
    expect(formulaUnavailable({ ...base, unavailableReasons: ['MODEL_FORMULA_INPUTS_INCOMPLETE'] })).toContain('材料用量不完整')
    expect(formulaUnavailable({ ...base, unavailableReasons: ['FORMULA_TARGET_IS_RATIO', 'MODEL_FORMULA_INPUTS_INCOMPLETE'] })).toContain('以性能为 Y')
  })

  it('uses only saved ranges and does not require predicted proportions in advance', () => {
    const context = formulaContext(['dry-gloss'])
    const form = syncFormulaForm({ ...emptyFormulaForm(), targets: ['dry-gloss'] }, context)
    form.goals['dry-gloss']!.value = 80
    const request = buildFormulaRequest(form, context)
    expect(request.searchSpace.variableMaterials).toEqual([{ materialId: 'resin', minimum: 60, maximum: 80 }, { materialId: 'additive', minimum: 20, maximum: 40 }])
    expect(request.searchSpace.variableInputs).toEqual({ TEMPERATURE_C: { minimum: 20, maximum: 32 } })
    expect(request.baselineFormula.components.reduce((sum, component) => sum + component.ratio, 0)).toBeCloseTo(100)
    expect(form.materials.resin?.ratio).toBeUndefined()
    expect(learnedRange([{ minimum: 10, maximum: 20 }, { minimum: 15, maximum: 30 }])).toEqual({ minimum: 15, maximum: 20 })
    expect(learnedRange([{ minimum: 10, maximum: 20 }, { minimum: 25, maximum: 30 }])).toBeUndefined()
  })
  it('restores fixed conditions, zero and false together with goal and material constraints', () => {
    const context = formulaContext(['dry-gloss']), form = syncFormulaForm({ ...emptyFormulaForm(), targets: ['dry-gloss'] }, context)
    form.goals['dry-gloss']!.value = { target: 80, tolerance: 2 }; form.goals['dry-gloss']!.operator = 'MATCH'
    const request = buildFormulaRequest(form, context)
    request.fixedInputs = { COATING_SOLIDS: 40, PRESSURE: 0, HEATED: false }
    request.searchSpace.variableInputs = { TEMPERATURE_C: { minimum: 22, maximum: 28, step: 0.5 } }
    const restored = restoreFormulaForm(request)
    expect(restored.inputs.COATING_SOLIDS).toEqual({ mode: 'FIXED', value: 40 })
    expect(restored.inputs.PRESSURE?.value).toBe(0); expect(restored.inputs.HEATED?.value).toBe(false)
    expect(restored.inputs.TEMPERATURE_C).toEqual({ mode: 'VARIABLE', minimum: 22, maximum: 28, step: 0.5 })
    expect(restored.goals['dry-gloss']?.value).toEqual({ target: 80, tolerance: 2 })
    expect(restored.materials.resin).toEqual({ mode: 'VARIABLE', minimum: 60, maximum: 80 })
  })
  it('accepts gram ranges without a 100 percent constraint', () => {
    const context = formulaContext(['dry-gloss']), form = syncFormulaForm({ ...emptyFormulaForm(), targets: ['dry-gloss'] }, context)
    form.materials.resin = { mode: 'FIXED', ratio: 180 }; form.materials.additive = { mode: 'VARIABLE', minimum: 40, maximum: 80 }
    expect(formulaSpaceError(form, context)).toBe('')
    const request = buildFormulaRequest(form, context)
    expect(request.formulaBasis).toBe('ABSOLUTE_MASS')
    expect(request.baselineFormula.components.map(item => item.unit)).toEqual(['g', 'g'])
    expect(request.targetTotal).toBe(240)
  })
  it('evaluates a target class by its probability and ordinal goals by the correct cumulative probability', () => {
    const binary = { ...formulaTargets[0]!, valueType: 'BINARY' as const, classes: ['未干', '表干'] }
    const goal = { operator: 'MATCH', value: { label: '未干' }, mandatory: true, weight: 1, minimumProbability: 0.6 }
    expect(goalMet(goal, binary, { targetId: binary.targetId, status: 'SUCCEEDED', result: { resultType: 'BINARY', label: '未干', probability: 0.3, positiveClass: '表干', decisionThreshold: 0.5 } })).toBe(true)
    const ordinal = { ...binary, valueType: 'ORDINAL' as const, classes: ['一级', '二级', '三级'] }
    expect(goalMet({ ...goal, operator: 'AT_LEAST', value: { label: '二级' }, minimumProbability: 0.7 }, ordinal, { targetId: ordinal.targetId, status: 'SUCCEEDED', result: { resultType: 'ORDINAL', label: '二级', probabilities: { 一级: 0.2, 二级: 0.5, 三级: 0.3 } } })).toBe(true)
    expect(goalError(binary, { ...goal, value: { label: 'OK' } })).toBe('请选择目标类别或等级')
  })
  it('marks changed constraints and model versions without treating ordering changes as new inputs', () => {
    const context = formulaContext(['dry-gloss']), form = syncFormulaForm({ ...emptyFormulaForm(), targets: ['dry-gloss'] }, context)
    expect(formulaFingerprint(form, context)).toBe(formulaFingerprint({ ...form, inputs: { ...form.inputs } }, context))
    expect(formulaFingerprint(form, context)).not.toBe(formulaFingerprint({ ...form, materials: { ...form.materials, resin: { ...form.materials.resin!, maximum: 90 } } }, context))
    expect(formulaFingerprint(form, context)).not.toBe(formulaFingerprint(form, { ...context, selected: context.selected.map(target => ({ ...target, modelVersionId: 'new-model' })) }))
  })
})
