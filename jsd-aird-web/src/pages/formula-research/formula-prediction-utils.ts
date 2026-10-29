import type { FormulaCandidate, FormulaContext, FormulaGoal, FormulaMaterial, FormulaRequest, FormulaTarget, FormulaTargetResult, GoalValue, ResearchRun } from '@/services/ai-rnd/formula-design-api'
import { businessValue, categoryOptions, fieldName, formatPredictionNumber, inputError, invalidContinuousPrediction, predictionFailure, unavailableReason } from '../performance-prediction/performance-prediction-utils'
import { feasibleFormulaSeed } from './formula-search'

export type MaterialSetting = { mode: 'FIXED' | 'VARIABLE'; ratio?: number; minimum?: number; maximum?: number }
export type InputSetting = { mode: 'FIXED' | 'VARIABLE'; value?: string | number | boolean; minimum?: number; maximum?: number; step?: number }
export type FormulaForm = { targets: string[]; goals: Record<string, Omit<FormulaGoal, 'targetId' | 'valueType' | 'expectedModelVersionId'>>; materials: Record<string, MaterialSetting>; inputs: Record<string, InputSetting> }
export type FormulaCompleted = { run: ResearchRun; form: FormulaForm; context: FormulaContext }
export type FormulaDraft = { form: FormulaForm; context?: FormulaContext; completed?: FormulaCompleted; pending?: { key: string; id?: string; request: FormulaRequest; context: FormulaContext } }
export const emptyFormulaForm = (): FormulaForm => ({ targets: [], goals: {}, materials: {}, inputs: {} })
export const isPreference = (operator: string) => ['MAXIMIZE', 'MINIMIZE'].includes(operator)
const finite = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value)
const object = (value: unknown): value is Record<string, unknown> => Boolean(value) && typeof value === 'object' && !Array.isArray(value)
export function operatorName(operator: string, type: string) {
  if (operator === 'MATCH' && type !== 'CONTINUOUS') return type === 'ORDINAL' ? '指定等级' : '指定类别'
  return ({ AT_LEAST: '至少达到', AT_MOST: '不超过', RANGE: '目标区间', MATCH: '目标值与偏差', MAXIMIZE: '尽量提高', MINIMIZE: '尽量降低' } as Record<string, string>)[operator] ?? '目标条件'
}
export function defaultGoal(target: FormulaTarget, operator = target.operators?.[0] ?? 'AT_LEAST'): FormulaForm['goals'][string] {
  const value: GoalValue | undefined = target.valueType !== 'CONTINUOUS' ? { label: target.positiveClass } : operator === 'RANGE' ? [undefined, undefined] : operator === 'MATCH' ? {} : undefined
  return { operator, value, mandatory: !isPreference(operator), weight: 1, minimumProbability: target.decisionThreshold }
}
export function learnedRange(ranges?: Array<{ minimum: number; maximum: number }>) {
  const valid = ranges?.filter(range => finite(range.minimum) && finite(range.maximum) && range.minimum <= range.maximum)
  if (!valid?.length) return undefined
  const minimum = Math.max(...valid.map(range => range.minimum)), maximum = Math.min(...valid.map(range => range.maximum))
  return minimum <= maximum ? { minimum, maximum } : undefined
}
export function syncFormulaForm(form: FormulaForm, context: FormulaContext) {
  const goals = { ...form.goals }, materials = { ...form.materials }, inputs = { ...form.inputs }
  context.selected.forEach(target => { goals[target.targetId] ??= defaultGoal(target) })
  context.materialIntersection.forEach(material => {
    const range = learnedRange(material.supportedRanges)
    if (typeof material.fixedValue === 'number') materials[material.materialId] = { mode: 'FIXED', ratio: material.fixedValue }
    else materials[material.materialId] ??= range?.minimum === range?.maximum && range ? { mode: 'FIXED', ratio: range.minimum } : { mode: 'VARIABLE', ...(range ? { minimum: Math.max(0, range.minimum), maximum: range.maximum } : {}) }
  })
  context.requiredInputs.forEach(field => {
    if (inputs[field.code]) return
    const range = learnedRange(field.supportedRanges), options = categoryOptions(field)
    inputs[field.code] = field.valueType === 'NUMBER' && range ? range.minimum === range.maximum ? { mode: 'FIXED', value: range.minimum } : { mode: 'VARIABLE', ...range } : { mode: 'FIXED', ...(options.length === 1 ? { value: options[0]!.value } : {}) }
  })
  return { ...form, goals, materials, inputs }
}
export function formulaMaterialName(material?: Partial<FormulaMaterial>, index = 0) {
  if (material?.sourceColumnName) return material.sourceColumnName
  const name = material?.name || material?.code || ''
  const match = /^(RESIN|ADDITIVE|CROSSLINKER|DILUENT)(?:[ _-]([A-Z]))?$/.exec(name)
  if (match) return `${({ RESIN: '树脂', ADDITIVE: '助剂', CROSSLINKER: '交联剂', DILUENT: '稀释剂' } as Record<string, string>)[match[1]!]}${match[2] ?? ''}`
  if (name && !/^(?:[0-9a-f]{8}-[0-9a-f-]{27}|R0[5-9]|R10|SYNTHETIC|TEST|STRUCT:)/i.test(name)) return name
  return `${({ RESIN: '树脂', ADDITIVE: '助剂', CROSSLINKER: '交联剂', DILUENT: '稀释剂' } as Record<string, string>)[material?.role ?? ''] ?? '材料'} ${index + 1}`
}
export function formulaUnavailable(target: FormulaTarget) {
  if (target.unavailableReasons.includes('FORMULA_TARGET_IS_RATIO')) return '此模型预测单项比例；反推配方请选择以性能为 Y 的模型'
  if (target.unavailableReasons.includes('MODEL_FORMULA_INPUTS_INCOMPLETE')) return target.missingFormulaMaterials?.length
    ? `X 中缺少材料：${target.missingFormulaMaterials.join('、')}，请加入后重新训练`
    : target.formulaIssues?.length ? `配方记录需检查：${target.formulaIssues.slice(0, 3).join('；')}` : 'X 中的材料用量不完整，请选择完整配方用量列后训练'
  if (target.unavailableReasons.includes('MODEL_MATERIALS_INCONSISTENT')) return '模型材料与训练记录不一致，请重新训练'
  if (target.unavailableReasons.includes('MODEL_NOT_FORMULA_ENABLED')) return '训练时未选择材料用量作为 X，请加入用量列后训练'
  return unavailableReason(target)
}
export function goalError(target: FormulaTarget, goal?: FormulaForm['goals'][string]) {
  if (!goal) return '请填写目标条件'
  if (target.operators?.length && !target.operators.includes(goal.operator)) return '请重新选择目标方式'
  if (!finite(goal.weight) || goal.weight <= 0) return '权重必须大于 0'
  if (target.valueType !== 'CONTINUOUS') {
    const value = !Array.isArray(goal.value) && typeof goal.value === 'object' ? goal.value : undefined
    if (!value?.label || !target.classes?.includes(value.label)) return '请选择目标类别或等级'
    if (!finite(goal.minimumProbability) || goal.minimumProbability < 0 || goal.minimumProbability > 1) return '请填写 0～100% 的最低概率'
    return ''
  }
  if (isPreference(goal.operator)) return ''
  if (goal.operator === 'RANGE') return Array.isArray(goal.value) && finite(goal.value[0]) && finite(goal.value[1]) ? goal.value[0] <= goal.value[1] ? '' : '最小目标值不能大于最大目标值' : '请补齐目标区间'
  if (goal.operator === 'MATCH') {
    const value = !Array.isArray(goal.value) && typeof goal.value === 'object' ? goal.value : undefined
    return value && finite(value.target) && finite(value.tolerance) ? value.tolerance >= 0 ? '' : '允许偏差不能小于 0' : '请填写目标值和允许偏差'
  }
  return finite(goal.value) ? '' : '请填写目标值'
}
export function materialSettingError(setting?: MaterialSetting) {
  if (!setting) return '请填写材料用量'
  if (setting.mode === 'FIXED') return finite(setting.ratio) ? setting.ratio < 0 ? '固定用量不能为负数' : '' : '请填写固定用量'
  if (!finite(setting.minimum) || !finite(setting.maximum)) return '请补齐最小、最大用量'
  if (setting.minimum < 0) return '材料用量不能为负数'
  return setting.minimum > setting.maximum ? '最小用量不能大于最大用量' : ''
}
export function formulaSpaceError(form: FormulaForm, context: FormulaContext) {
  if (!context.materialIntersection.length) return '所选指标没有共同可用材料，请分别生成方案。'
  if (context.materialIntersection.some(material => materialSettingError(form.materials[material.materialId]))) return ''
  const settings = context.materialIntersection.map(material => form.materials[material.materialId]!)
  if (!settings.some(setting => setting.mode === 'VARIABLE' && setting.maximum! > setting.minimum!)) return '请至少让一种材料用量可以变化。'
  return ''
}
export function formulaInputError(field: FormulaContext['requiredInputs'][number], setting?: InputSetting) {
  if (setting?.mode === 'VARIABLE') {
    if (!finite(setting.minimum) || !finite(setting.maximum)) return '请补齐条件范围'
    if (setting.minimum > setting.maximum) return '最小值不能大于最大值'
    if (setting.step != null && (!finite(setting.step) || setting.step <= 0)) return '步长必须大于 0'
    return ''
  }
  return inputError(field, setting?.value)
}
export function buildFormulaRequest(form: FormulaForm, context: FormulaContext): FormulaRequest {
  const components = feasibleFormulaSeed(context.materialIntersection.map(material => {
    const setting = form.materials[material.materialId]!
    return { materialId: material.materialId, fixed: setting.mode === 'FIXED', ratio: setting.ratio, minimum: setting.minimum, maximum: setting.maximum }
  }))
  const fixedInputs: FormulaRequest['fixedInputs'] = {}, variableInputs: FormulaRequest['searchSpace']['variableInputs'] = {}
  context.requiredInputs.forEach(field => {
    const setting = form.inputs[field.code]
    if (setting?.mode === 'VARIABLE') variableInputs[field.code] = { minimum: setting.minimum!, maximum: setting.maximum!, ...(setting.step == null ? {} : { step: setting.step }) }
    else if (setting?.value != null && setting.value !== '') fixedInputs[field.code] = setting.value
  })
  return { typedGoals: context.selected.map(target => ({ ...form.goals[target.targetId]!, targetId: target.targetId, valueType: target.valueType, expectedModelVersionId: target.modelVersionId })), targetTotal: components.reduce((sum, component) => sum + component.ratio, 0), formulaBasis: 'ABSOLUTE_MASS', baselineFormula: { basis: 'ABSOLUTE_MASS', compositionComplete: true, recordedTotal: components.reduce((sum, component) => sum + component.ratio, 0), components }, fixedInputs, searchSpace: { allowedMaterialIds: context.materialIntersection.map(material => material.materialId), variableMaterials: context.materialIntersection.filter(material => form.materials[material.materialId]?.mode === 'VARIABLE').map(material => ({ materialId: material.materialId, minimum: form.materials[material.materialId]!.minimum!, maximum: form.materials[material.materialId]!.maximum! })), variableInputs }, constraints: {}, candidateCount: context.candidateCountDefault || 4 }
}
export function restoreFormulaForm(request: Partial<FormulaRequest>): FormulaForm {
  const form = emptyFormulaForm()
  request.typedGoals?.forEach(goal => { form.targets.push(goal.targetId); form.goals[goal.targetId] = { operator: goal.operator, value: typeof goal.value === 'string' ? { label: goal.value } : goal.value, mandatory: goal.mandatory ?? !isPreference(goal.operator), weight: goal.weight ?? 1, minimumProbability: goal.minimumProbability } })
  request.baselineFormula?.components.forEach(component => {
    const variable = request.searchSpace?.variableMaterials.find(material => material.materialId === component.materialId)
    form.materials[component.materialId] = variable ? { mode: 'VARIABLE', minimum: variable.minimum, maximum: variable.maximum } : { mode: 'FIXED', ratio: component.ratio }
  })
  Object.entries(request.fixedInputs ?? {}).forEach(([code, value]) => { form.inputs[code] = { mode: 'FIXED', value } })
  Object.entries(request.searchSpace?.variableInputs ?? {}).forEach(([code, range]) => { form.inputs[code] = { mode: 'VARIABLE', ...range } })
  return form
}
export function formulaFingerprint(form: FormulaForm, context?: FormulaContext) {
  const stable = (value: unknown): unknown => Array.isArray(value) ? value.map(stable) : object(value) ? Object.fromEntries(Object.entries(value).sort(([a], [b]) => a.localeCompare(b)).map(([key, item]) => [key, stable(item)])) : value
  return JSON.stringify(stable({ targets: [...form.targets].sort(), goals: Object.fromEntries(form.targets.map(id => [id, form.goals[id]])), materials: context ? Object.fromEntries(context.materialIntersection.map(material => [material.materialId, form.materials[material.materialId]])) : form.materials, inputs: context ? Object.fromEntries(context.requiredInputs.map(field => [field.code, form.inputs[field.code]])) : form.inputs, models: context?.selected.map(target => [target.targetId, target.modelVersionId]).sort(([a], [b]) => (a ?? '').localeCompare(b ?? '')) }))
}
export function contextForRun(run: ResearchRun, fallback?: FormulaContext) {
  const context = run.frozenEvidence?.displayContext ?? fallback
  return context && Array.isArray(context.selected) && Array.isArray(context.requiredInputs) && Array.isArray(context.materialIntersection) ? context : undefined
}
export function formulaFailure(code?: string) {
  return ({ MATERIAL_SPACE_CONFLICT: '所选指标或材料不兼容，请调整指标组合与材料。', INPUT_SPACE_CONFLICT: '实验条件有冲突，请核对条件类型、单位和范围。', FIXED_CONSTRAINT_CONFLICT: '材料用量范围存在冲突，请调整固定用量和范围。', GOAL_VALUE_INVALID: '目标条件无效，请核对目标值、类别或等级。', MINIMUM_PROBABILITY_REQUIRED: '请填写目标类别或等级的最低概率。', INVALID_SNAPSHOT: '模型文件未能读取，请在模型中心检查或重新训练。', FILE_NOT_READY: '模型文件未能读取，请在模型中心检查或重新训练。', HASH_MISMATCH: '模型文件与已登记版本不一致，请在模型中心检查或重新训练。', INTERNAL_COMPUTE_ERROR: '配方计算未完成，请重试；若再次失败，请检查模型和输入设置。' } as Record<string, string>)[code ?? ''] ?? predictionFailure(code, '配方方案未能生成，请稍后重试。')
}
export function formulaRunNotice(run: ResearchRun) {
  if (run.executionStatus === 'FAILED') return formulaFailure(typeof run.shortfallReason === 'object' && run.shortfallReason ? run.shortfallReason.code : undefined)
  if (run.outcomeStatus === 'SUCCEEDED') return ''
  const reasons = run.rejectionSummary ?? {}
  if ((reasons.TARGET_NOT_MET ?? 0) > 0) return '未找到足够的达标方案，可调整目标值或材料范围后重新生成。'
  if ((reasons.OUT_OF_DOMAIN ?? 0) > 0) return '部分条件未能得到预测值，请核对数值和单位，或补充相近实验记录后重新训练。'
  if ((reasons.INVALID_PREDICTION ?? 0) > 0) return '部分模型结果不合理，已排除相关方案。请核对输入和单位，并补充相近实验后重新训练。'
  if ((reasons.MATERIAL_MISMATCH ?? 0) > 0) return '部分材料不在模型中，请调整材料或更换指标。'
  return run.actualCandidateCount ? `本次生成 ${run.actualCandidateCount} 个不同方案，当前条件下没有更多可行方案。` : '当前条件下没有可行方案，请调整目标值、材料用量范围或实验条件。'
}
export function resultPrediction(result?: FormulaTargetResult) { return result?.result ?? result?.prediction }
export function resultText(result?: FormulaTargetResult) {
  const value = resultPrediction(result)
  if (!value || result?.status !== 'SUCCEEDED') return '未得到预测值'
  if (value.resultType === 'CONTINUOUS') return invalidContinuousPrediction(value.value, value.unit) ? '未展示：结果不合理，请核对单位并补充相近实验后重新训练' : formatPredictionNumber(value.value)
  return value.label
}
export function goalMet(goal: FormulaForm['goals'][string] | undefined, target: FormulaTarget, result?: FormulaTargetResult) {
  if (!goal || isPreference(goal.operator)) return undefined
  const value = resultPrediction(result)
  if (!value || result?.status !== 'SUCCEEDED') return false
  if (value.resultType === 'CONTINUOUS') {
    if (goal.operator === 'RANGE' && Array.isArray(goal.value)) return value.value >= goal.value[0]! && value.value <= goal.value[1]!
    if (goal.operator === 'MATCH' && object(goal.value)) return Math.abs(value.value - Number(goal.value.target)) <= Number(goal.value.tolerance)
    return goal.operator === 'AT_MOST' ? value.value <= Number(goal.value) : value.value >= Number(goal.value)
  }
  const label = object(goal.value) ? String(goal.value.label ?? '') : ''
  const labels = target.classes ?? [], selectedIndex = labels.indexOf(label)
  const accepted = target.valueType === 'ORDINAL' && goal.operator !== 'MATCH' ? labels.filter((_, index) => goal.operator === 'AT_LEAST' ? index >= selectedIndex : index <= selectedIndex) : [label]
  const probability = value.resultType === 'BINARY' ? accepted.includes(value.positiveClass) ? value.probability : 1 - value.probability : accepted.reduce((sum, item) => sum + (value.probabilities[item] ?? 0), 0)
  return probability >= (goal.minimumProbability ?? 1)
}
export function candidateNote(candidate: FormulaCandidate) {
  return candidate.applicability?.status === 'OUT_OF_DOMAIN' || candidate.targetResults.targets?.some(result => result.applicability?.status === 'OUT_OF_DOMAIN') ? '超出已有实验范围，建议先做实验验证' : candidate.applicability?.status === 'NEAR_BOUNDARY' || candidate.targetResults.targets?.some(result => result.applicability?.status === 'NEAR_BOUNDARY') ? '接近已有实验范围边缘，建议实验验证' : ''
}
export function conditionText(value: unknown, unit?: string) { return value == null ? '未记录' : typeof value === 'number' ? `${formatPredictionNumber(value)}${unit ?? ''}` : businessValue(value) || '未记录' }
export function validFormulaContext(value: unknown): value is FormulaContext { return object(value) && Array.isArray(value.targets) && Array.isArray(value.selected) && Array.isArray(value.requiredInputs) && Array.isArray(value.materialIntersection) }
export function readFormulaDraft(key?: string): FormulaDraft {
  const empty = { form: emptyFormulaForm() }
  if (!key) return empty
  try {
    const value: unknown = JSON.parse(localStorage.getItem(key) ?? 'null')
    if (!object(value) || !object(value.form) || !Array.isArray(value.form.targets) || !object(value.form.goals) || !object(value.form.materials) || !object(value.form.inputs)) return empty
    const draft = value as FormulaDraft
    draft.form.targets = draft.form.targets.filter(id => typeof id === 'string')
    draft.form.goals = Object.fromEntries(Object.entries(draft.form.goals).filter(([, goal]) => object(goal) && typeof goal.operator === 'string'))
    draft.form.materials = Object.fromEntries(Object.entries(draft.form.materials).filter(([, setting]) => object(setting) && ['FIXED', 'VARIABLE'].includes(setting.mode)))
    draft.form.inputs = Object.fromEntries(Object.entries(draft.form.inputs).filter(([, setting]) => object(setting) && ['FIXED', 'VARIABLE'].includes(setting.mode)))
    if (!validFormulaContext(draft.context)) draft.context = undefined
    if (draft.completed && (!Array.isArray(draft.completed.run?.candidates) || !validFormulaContext(draft.completed.context))) draft.completed = undefined
    if (draft.pending && (!draft.pending.key || !Array.isArray(draft.pending.request?.typedGoals) || !validFormulaContext(draft.pending.context))) draft.pending = undefined
    return draft
  } catch { return empty }
}
export { businessValue, categoryOptions, fieldName, formatPredictionNumber }
