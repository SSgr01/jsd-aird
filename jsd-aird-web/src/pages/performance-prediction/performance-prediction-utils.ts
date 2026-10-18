import type { PredictionCatalogTarget, PredictionContext, PredictionInputField, PredictionRequest, PredictionResponse, TargetBlocked, TargetFailed, TargetSuccess } from '@/services/ai-rnd/ai-rnd-types'

export type PredictionForm = { targets: string[]; inputs: Record<string, unknown> }
export type PredictionSubmission = { request: PredictionRequest; context: PredictionContext }
export type PredictionDraft = {
  form: PredictionForm
  pending?: { key: string; id?: string; submission: PredictionSubmission }
  completed?: { response: PredictionResponse; submission: PredictionSubmission }
}
const emptyForm = (): PredictionForm => ({ targets: [], inputs: {} })
const inputLabels: Record<string, string> = { COATING_SOLIDS_PCT: '涂料固含', COATING_SOLIDS: '涂料固含', TEMPERATURE_C: '温度', HUMIDITY_RH_PCT: '湿度', UVA_INTENSITY_MW_CM2: 'UVA光强', UV_ENERGY_MJ_CM2: 'UV能量', APPLICATOR_SPEC_UM: '绕丝棒规格', APPLICATION_METHOD: '施工方式', CURING_SOURCE: '固化光源', SUBSTRATE: '基材' }
const categoryLabels: Record<string, string> = { PET_100UM_OPTICAL: 'PET 100μm光学膜', PC_170UM: 'PC 170μm膜', PMMA_PC_COMPOSITE_064: 'PMMA/PC复合板', WIRE_BAR_ROLL_COAT_40UM: '40μm绕丝棒涂布', SPRAY_COATING: '喷涂', MERCURY_LAMP_UV: '汞灯UV', LED_UV: 'LED UV', UVPU_APPLICATION_FORMULATION: 'UV/PU应用配方', UV_IMMEDIATE: 'UV固化后即时测试', NONE: '无特殊前处理', STANDARD: '标准条件' }
const errorLabels: Record<string, string> = {
  INVALID_SNAPSHOT: '模型文件未能读取，请在模型中心检查或重新训练。', MODEL_LOAD_FAILED: '模型文件未能读取，请在模型中心检查或重新训练。',
  FORMULA_INCOMPLETE: '请确认配方已完整。', FORMULA_AMOUNT_MISSING: '请补齐材料用量。', FORMULA_RATIO_MISSING: '请补齐材料比例；未使用的材料填 0。', FORMULA_RATIO_NEGATIVE: '材料比例不能小于 0。', FORMULA_RATIO_NOT_NUMERIC: '材料比例必须为数值。', FORMULA_TOTAL_NON_POSITIVE: '配方合计必须大于 0。', FORMULA_UNIT_UNRESOLVED: '请检查配方单位。', MISSING_REQUIRED_X: '请补齐必填的实验条件。',
  MATERIAL_NOT_IN_MODEL: '当前模型未包含所选材料，请调整材料或更换预测指标。', UNKNOWN_CATEGORY: '所选类别无法用于当前模型，请重新选择。', OUT_OF_DOMAIN: '模型未返回该条件的预测值，请核对数值和单位，或补充相近实验记录后重新训练。', INVALID_PREDICTION_RESULT: '模型结果不合理，未展示数值。请核对输入和单位，并补充相近实验后重新训练。',
  COMPUTE_UNAVAILABLE: '计算服务暂不可用，请稍后重试。', MODEL_ARTIFACT_UNAVAILABLE: '模型暂不可用，请在模型中心检查状态。', MODEL_ARTIFACT_INVALID: '模型暂不可用，请在模型中心检查状态。', MODEL_CONTRACT_MISMATCH: '模型输入已更新，请刷新模型信息后重试。', MODEL_VERSION_CHANGED: '模型版本已更新，请核对输入后重新预测。', NO_ACTIVE_MODEL: '当前指标没有已启用模型，请在模型中心启用。', MODEL_DOMAIN_UNAVAILABLE: '模型训练信息不完整，请在模型中心重新训练。',
}
export function fieldName(field: PredictionInputField) { const name = field.name || field.sourceLabel || field.code; return inputLabels[name.toUpperCase()] || (name.toLowerCase() === 'temperature' || name === 'TEST_TEMPERATURE' ? '温度' : name.toLowerCase() === 'humidity' ? '湿度' : name || '实验条件') }
export function businessValue(value: unknown) { if (typeof value === 'boolean') return value ? '是' : '否'; const text = typeof value === 'string' || typeof value === 'number' ? String(value) : ''; if (text === 'UV_CURED') return 'UV固化后'; return categoryLabels[text] ?? categoryLabels[text.toUpperCase()] ?? (/^[A-Z][A-Z0-9]+(?:_[A-Z0-9]+)+$/.test(text) ? '' : text) }
export function materialName(material?: { name?: string; code: string }) { return material?.name || material?.code || '选择材料' }
export function formatPredictionNumber(value: number) {
  if (!Number.isFinite(value)) return '—'
  if (value === 0) return '0'
  const magnitude = Math.abs(value)
  return magnitude < 0.0001 || magnitude >= 1e9 ? value.toExponential(3) : String(Number(value.toFixed(magnitude < 1 ? 6 : 4)))
}
export function invalidContinuousPrediction(value: number, unit?: string) {
  const normalized = (unit ?? '').normalize('NFKC').trim().toLocaleLowerCase('en-US')
  return !Number.isFinite(value) || ['次', 'cycle', 'cycles'].includes(normalized) && value < 0
}
export function targetAvailable(target: PredictionCatalogTarget) { return target.targetStatus !== 'RETIRED' && Boolean(target.predictionAvailable ?? (target.formalPredictionAvailable || target.simulationPredictionAvailable)) }
export function unavailableReason(target: { targetStatus?: string; unavailableReasons: string[]; registered?: boolean }) {
  if (target.targetStatus === 'RETIRED') return '已停用'
  if (target.registered || target.unavailableReasons.includes('REGISTERED_DATA_INSUFFICIENT')) return '训练数据不足'
  if (target.unavailableReasons.includes('SIMULATION_NOT_ENABLED')) return '当前模型暂不可用，请在模型中心检查状态。'
  if (target.unavailableReasons.includes('MODEL_NOT_PRODUCTION_ELIGIBLE')) return '模型尚未达到启用条件'
  if (target.unavailableReasons.includes('NO_ACTIVE_MODEL')) return '尚无已启用模型'
  if (target.unavailableReasons.includes('MODEL_DOMAIN_UNAVAILABLE')) return '模型信息不完整，请在模型中心重新训练。'
  if (target.unavailableReasons.includes('TARGET_NOT_READY')) return '预测目标尚未发布'
  return '暂不可预测'
}
type TargetLabelData = Pick<PredictionCatalogTarget, 'targetId' | 'name'> & Partial<Pick<PredictionCatalogTarget, 'unit' | 'testMethod' | 'testStage' | 'category' | 'modelSource'>>
export function targetLabels(targets: TargetLabelData[], includeUnit = true) {
  const labels = new Map<string, string>()
  const description = (target: TargetLabelData) => target.modelSource ? `${target.modelSource.datasetName} · X：${target.modelSource.xColumns.map(x => x.name).join('、')}` : [businessValue(target.testMethod), businessValue(target.testStage), target.category === '客户测试语义' ? undefined : businessValue(target.category)].filter(Boolean).join(' · ')
  for (const target of targets) {
    const base = `${target.name}${includeUnit && target.unit ? `（${target.unit}）` : ''}`
    const sameName = targets.filter(item => item.name === target.name && item.unit === target.unit)
    const context = description(target)
    const label = sameName.length > 1 && context ? `${base} · ${context}` : base
    const duplicates = sameName.filter(item => description(item) === context)
    labels.set(target.targetId, sameName.length > 1 && duplicates.length > 1 ? `${label}（指标 ${duplicates.findIndex(item => item.targetId === target.targetId) + 1}）` : label)
  }
  return labels
}
export function conditionFieldLabels(fields: PredictionInputField[], targets: TargetLabelData[]) {
  const targetNameById = targetLabels(targets, false)
  const byCode = new Map<string, string>()
  for (const field of fields) {
    const base = fieldName(field)
    const duplicates = fields.filter(item => fieldName(item) === base && item.unit === field.unit)
    if (duplicates.length < 2) { byCode.set(field.code, base); continue }
    const related = (field.requiredByTargets ?? []).map(item => targetNameById.get(item.targetId) ?? item.targetName).filter(Boolean)
    const qualifiers = [...new Set(related)]
    byCode.set(field.code, qualifiers.length ? `${base} · ${qualifiers.join(' / ')}` : `${base} · ${field.code}`)
  }
  return byCode
}
export function categoryOptions(field: PredictionInputField) {
  const configured: unknown = field.allowedValues ?? field.encoding?.allowedValues
  if (Array.isArray(configured) && configured.length) return configured.filter((value): value is string | number => typeof value === 'string' || typeof value === 'number').map(value => ({ value: String(value), label: categoryLabels[String(value)] ?? String(value) }))
  const code = field.code.toLowerCase().replace(/[-_]/g, '')
  const name = fieldName(field)
  const values = code.includes('substrate') || name.includes('基材') ? ['PET_100UM_OPTICAL', 'PC_170UM', 'PMMA_PC_COMPOSITE_064'] : code.includes('applicationmethod') || name.includes('施工') || name.includes('涂布') ? ['WIRE_BAR_ROLL_COAT_40UM', 'SPRAY_COATING'] : code.includes('curingsource') || name.includes('固化') || name.includes('光源') ? ['MERCURY_LAMP_UV', 'LED_UV'] : []
  return values.map(value => ({ value, label: categoryLabels[value] ?? value }))
}
export function inputError(field: PredictionInputField, value: unknown) {
  if (value == null || typeof value === 'string' && !value.trim()) return field.required ? `请填写${fieldName(field)}` : ''
  if (field.valueType === 'NUMBER' && (typeof value !== 'number' || !Number.isFinite(value))) return '请输入有效数值'
  if (field.valueType === 'BOOLEAN' && typeof value !== 'boolean') return '请选择是或否'
  if (field.valueType === 'CATEGORY' && (typeof value !== 'string' && typeof value !== 'number' || categoryOptions(field).length && !categoryOptions(field).some(option => option.value === String(value)))) return '请重新选择类别'
  return ''
}
export function buildPredictionRequest(form: PredictionForm, context: PredictionContext): PredictionRequest {
  return {
    inputs: Object.fromEntries(context.requiredInputs.filter(field => form.inputs[field.code] !== undefined).map(field => [field.code, form.inputs[field.code]])),
    targets: form.targets.map(targetId => ({ targetId, expectedModelVersionId: context.selected.find(target => target.targetId === targetId)?.modelVersionId })),
  }
}
export function predictionFingerprint(request: PredictionRequest) {
  return JSON.stringify({ ...request, inputs: Object.fromEntries(Object.entries(request.inputs).sort(([a], [b]) => a.localeCompare(b))), targets: [...request.targets].sort((a, b) => a.targetId.localeCompare(b.targetId)) })
}
export function predictionFailure(code?: string, fallback = '预测未完成，请核对输入后重试。') { return errorLabels[code ?? ''] ?? fallback }
export function predictionNotes(item: TargetSuccess | TargetBlocked | TargetFailed, context: PredictionContext) {
  if (item.status === 'SUCCEEDED') {
    if (item.applicability.status === 'OUT_OF_DOMAIN') return ['超出已有实验范围，建议先做实验验证']
    if (item.applicability.status === 'NEAR_BOUNDARY') return ['接近已有实验范围边缘，建议实验验证']
    return item.quality?.level === 'LOW' ? ['需实验验证'] : []
  }
  const detail = item.detail as { missingFields?: string[]; violations?: Array<{ fieldCode: string; value: number; minimum: number; maximum: number }> } | undefined
  const missing = detail?.missingFields?.map(code => context.requiredInputs.find(field => field.code === code)).filter((field): field is PredictionInputField => Boolean(field)) ?? []
  const violations = (detail?.violations ?? []).map(issue => {
    const field = context.requiredInputs.find(field => issue.fieldCode === field.code || issue.fieldCode.endsWith(`:${field.code}`))
    const material = context.materials.find(value => issue.fieldCode.includes(value.materialId))
    return `${field ? fieldName(field) : material ? materialName(material) : '实验条件'} ${formatPredictionNumber(issue.value)}${field?.unit ?? ''}；已有实验范围 ${formatPredictionNumber(issue.minimum)}～${formatPredictionNumber(issue.maximum)}${field?.unit ?? ''}`
  })
  const fieldError = item.status === 'BLOCKED' && ['INVALID_X_VALUE', 'UNKNOWN_CATEGORY'].includes(item.errorCode) && item.detail && 'fieldName' in item.detail ? item.message : predictionFailure(item.errorCode)
  return [fieldError, ...(missing.length ? [`待填写：${missing.map(fieldName).join('、')}`] : []), ...violations]
}
function isObject(value: unknown): value is Record<string, unknown> { return Boolean(value) && typeof value === 'object' && !Array.isArray(value) }
export function readPredictionDraft(key?: string): PredictionDraft {
  const empty = { form: emptyForm() }
  if (!key) return empty
  try {
    const raw: unknown = JSON.parse(localStorage.getItem(key) ?? 'null')
    if (!isObject(raw) || !isObject(raw.form) || !Array.isArray(raw.form.targets) || !isObject(raw.form.inputs)) return empty
    const form: PredictionForm = { targets: raw.form.targets.filter((value): value is string => typeof value === 'string'), inputs: Object.fromEntries(Object.entries(raw.form.inputs).filter(([, value]) => value == null || ['string', 'number', 'boolean'].includes(typeof value))) }
    const validSubmission = (value: unknown) => isObject(value) && isObject(value.request) && Array.isArray(value.request.targets) && isObject(value.context) && Array.isArray(value.context.selected) && Array.isArray(value.context.requiredInputs) && Array.isArray(value.context.materials)
    return { form, ...(isObject(raw.pending) && typeof raw.pending.key === 'string' && validSubmission(raw.pending.submission) ? { pending: raw.pending as PredictionDraft['pending'] } : {}), ...(isObject(raw.completed) && isObject(raw.completed.response) && Array.isArray(raw.completed.response.results) && validSubmission(raw.completed.submission) ? { completed: raw.completed as PredictionDraft['completed'] } : {}) }
  } catch { return empty }
}
