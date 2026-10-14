import { ArrowLeftOutlined, CheckCircleFilled, DatabaseOutlined, ExperimentOutlined } from '@ant-design/icons'
import { Alert, Button, Checkbox, Empty, Input, Select, Space, Table, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useMemo, useState } from 'react'

import type {
  EligibilityPage, EligibilitySummary, InputFieldSummary, PredictionTargetSummary,
  SourceMappingSuggestion, SourceMappingSuggestions, TargetVersion,
} from '@/services/ai-rnd/ai-rnd-types'
import './modeling-target-workflow.css'

const { Text } = Typography

type EligibilityRow = EligibilityPage['items'][number]
type JsonRecord = Record<string, unknown>
type Step = 'target' | 'data' | 'train'

const sourceLabels: Record<string, string> = { DATA_CENTER: '数据中心', EXPERIMENT: '实验记录本' }
const valueTypeLabels: Record<string, string> = { CONTINUOUS: '连续值', ORDINAL: '序数等级', BINARY: '二分类', CATEGORICAL: '多分类' }
const inputTypeLabels: Record<string, string> = { NUMBER: '数值', STRING: '文本', BOOLEAN: '是/否', CATEGORY: '类别', COMPOSITION: '配方组成' }
const reasonLabels: Record<string, string> = {
  MISSING_REQUIRED_X: '缺少必需的输入 X', UNKNOWN_MATERIAL: '材料身份尚未确认', MATERIAL_NOT_IN_MODEL: '材料暂不在当前模型范围',
  MATERIAL_NOT_IN_DICTIONARY: '材料尚未完成标准映射', TARGET_SEMANTIC_MISMATCH: '没有匹配到当前目标 Y', TARGET_AMBIGUOUS: '发现多个可能的目标结果',
  INVALID_Y_VALUE: '目标结果格式无法使用', UNSUPPORTED_OBSERVATION_TYPE: '结果表示方式暂不支持', SOURCE_INVALIDATED: '来源记录已失效',
  SOURCE_NOT_CONFIRMED: '来源记录尚未确认', SOURCE_MAPPING_REQUIRED: '目标结果来源尚未确认', FORMULA_BASIS_UNRESOLVED: '配方比例或基准不完整',
  UNIT_UNKNOWN: '单位无法确认', POST_EXPERIMENT_FIELD_NOT_ALLOWED: '包含实验完成后才能获得的输入', SAMPLE_IDENTITY_CONFLICT: '样本身份存在冲突',
  ANOMALY_REVIEW_REQUIRED: '记录需要人工复核',
}
const keyLabels: Record<string, string> = {
  temperatureC: '温度', humidityRhPct: '湿度', coatingSolidsPct: '涂料固含', uvaIntensityMwCm2: 'UVA光强',
  uvEnergyMjCm2: 'UV能量', applicatorSpecUm: '绕丝棒规格', applicationMethod: '施工方式', curingSource: '固化光源',
  substrate: '基材', targetValue: '当前目标 Y', value: '数值', standardValue: '标准值', rawValue: '原始值', unit: '单位',
  observationType: '结果类型', direction: '方向', minimum: '下限', maximum: '上限', label: '结果',
  testStage: '测试阶段', stage: '测试阶段', testMethod: '测试方法',
  pretreatment: '前处理', optimizationDirection: '优化方向', valueDomain: '取值范围',
}

const valueLabels: Record<string, string> = {
  UV_12H: 'UV固化后放置12小时', UV_IMMEDIATE: 'UV固化后即时', NONE: '无额外前处理',
  MINIMIZE: '越小越好', MAXIMIZE: '越大越好', PET_100UM_OPTICAL: 'PET 100μm光学膜',
  PC_170UM: 'PC 170μm膜', MERCURY_LAMP_UV: '汞灯UV', MERCURY_UV: '汞灯UV',
  WIRE_BAR_ROLL_COATING: '绕丝棒涂布', UVPU_APPLICATION_FORMULATION: 'UV/PU应用配方测试',
}

const asRecord = (value: unknown): JsonRecord => value && typeof value === 'object' && !Array.isArray(value) ? value as JsonRecord : {}
const asArray = (value: unknown): unknown[] => Array.isArray(value) ? value : []
const isBlank = (value: unknown) => value == null || value === '' || (Array.isArray(value) && value.length === 0)
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f-]{27,}$/i
const technicalKey = (key: string) => /(^|_)(id|hash|sha|revision|version|parser|sop|code)(_|$)/i.test(key)
  || /Id$|Hash$|Version$|Code$|Sha\d*$/i.test(key)
const humanKey = (key: string) => keyLabels[key] ?? key
  .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
  .replace(/[_./:-]+/g, ' ')
  .trim()

const formatScalar = (value: unknown): string => {
  if (typeof value === 'number') return Number.isInteger(value) ? String(value) : String(Number(value.toFixed(4)))
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (typeof value === 'string') return valueLabels[value] ?? value
  return ''
}

const stringValue = (value: unknown, fallback = '') => typeof value === 'string' || typeof value === 'number'
  ? String(value)
  : fallback

const normalizedText = (value: unknown) => stringValue(value).normalize('NFKC').toLowerCase().replace(/[\s_\-/:：()（）]+/g, '')

const structuredItems = (value: unknown) => asArray(asRecord(value).items)

const itemEntries = (items: unknown[], limit = 20) => items.flatMap((item) => {
  const row = asRecord(item)
  const label = stringValue(row.itemLabel ?? row.fieldName ?? row.labelPath ?? row.testItem ?? row.operation ?? row.name ?? row.field)
  const value = row.normalizedValue ?? row.standardValue ?? row.effectiveValue ?? row.value ?? row.rawValue
  const unit = stringValue(row.normalizedUnit ?? row.unit ?? row.rawUnit)
  if (!label || isBlank(value)) return []
  const rendered = formatScalar(value)
  return rendered ? [{ label, value: unit && !rendered.endsWith(unit) ? `${rendered} ${unit}` : rendered }] : []
}).slice(0, limit)

const factNodes = (sample?: JsonRecord) => Object.values(asRecord(sample?.facts)).filter((value) => value && typeof value === 'object' && !Array.isArray(value)).map(asRecord)

const anchorNodes = (sample?: JsonRecord) => asArray(asRecord(sample?.sourceCoordinates).anchors).map(asRecord)

const collectLeaves = (value: unknown, prefix = '', limit = 24, out: Array<{ label: string; value: string }> = []): Array<{ label: string; value: string }> => {
  if (out.length >= limit || isBlank(value)) return out
  if (Array.isArray(value)) {
    value.forEach((item, index) => collectLeaves(item, prefix || `第${index + 1}项`, limit, out))
    return out
  }
  if (typeof value !== 'object' || value == null) {
    const text = formatScalar(value)
    if (text && !uuidPattern.test(text)) out.push({ label: prefix || '数值', value: text })
    return out
  }
  Object.entries(value as JsonRecord).forEach(([key, child]) => {
    if (technicalKey(key) || out.length >= limit || isBlank(child)) return
    collectLeaves(child, prefix ? `${prefix} · ${humanKey(key)}` : humanKey(key), limit, out)
  })
  return out
}

const formulaEntries = (sample?: JsonRecord) => {
  const composition = asRecord(sample?.composition)
  const components = asArray(composition.components ?? composition.items ?? composition.formulaItems)
  if (components.length) {
    const byMaterial = new Map<string, { label: string; value: string; numeric: number }>()
    components.forEach((item) => {
      const row = asRecord(item)
      const name = stringValue(row.materialName ?? row.materialCode ?? row.code ?? row.name ?? row.material, '材料')
      const ratio = row.normalizedRatio ?? row.analysisRatio ?? row.ratio ?? row.percentage ?? row.value ?? row.amount
      const rawUnit = stringValue(row.unit, '%')
      const unit = rawUnit === 'PERCENT' ? '%' : rawUnit
      const numeric = typeof ratio === 'number' ? ratio : Number(ratio)
      const entry = { label: name, value: isBlank(ratio) ? '未记录' : `${formatScalar(ratio)}${unit === '%' ? '%' : ` ${unit}`}`, numeric: Number.isFinite(numeric) ? numeric : 0 }
      const existing = byMaterial.get(name)
      if (!existing || (!existing.numeric && entry.numeric)) byMaterial.set(name, entry)
    })
    return [...byMaterial.values()].filter((item) => !uuidPattern.test(item.label)).sort((a, b) => Number(Boolean(b.numeric)) - Number(Boolean(a.numeric))).map(({ label, value }) => ({ label, value }))
  }
  const anchors = anchorNodes(sample).filter((row) => stringValue(row.fieldCode).toUpperCase() === 'FORMULA.RATIO')
  if (anchors.length) return anchors.map((row) => ({
    label: stringValue(row.labelPath ?? row.itemLabel, '材料'),
    value: `${formatScalar(row.normalizedValue ?? row.rawValue)}%`,
  })).filter((item) => item.value !== '%').slice(0, 12)
  return collectLeaves(composition, '', 12)
}

const extractBusinessValue = (value: unknown): { value: unknown; unit?: string } | undefined => {
  if (isBlank(value)) return undefined
  if (typeof value !== 'object' || Array.isArray(value)) return { value }
  const row = asRecord(value)
  const resolved = row.normalizedValue ?? row.standardValue ?? row.effectiveValue ?? row.rawNumericValue
    ?? row.rawValue ?? row.value ?? row.displayValue
  if (isBlank(resolved)) return undefined
  return { value: resolved, unit: stringValue(row.normalizedUnit ?? row.standardUnit ?? row.unit ?? row.rawUnit) || undefined }
}

const findExactFieldValue = (value: unknown, key: string): unknown => {
  if (!value || typeof value !== 'object') return undefined
  if (Array.isArray(value)) {
    for (const item of value) {
      const found = findExactFieldValue(item, key)
      if (found !== undefined) return found
    }
    return undefined
  }
  const row = value as JsonRecord
  if (Object.prototype.hasOwnProperty.call(row, key) && !isBlank(row[key])) return row[key]
  for (const child of Object.values(row)) {
    const found = findExactFieldValue(child, key)
    if (found !== undefined) return found
  }
  return undefined
}

const findSemanticFieldValue = (value: unknown, code: string, name: string): unknown => {
  if (!value || typeof value !== 'object') return undefined
  if (Array.isArray(value)) {
    for (const item of value) {
      const found = findSemanticFieldValue(item, code, name)
      if (found !== undefined) return found
    }
    return undefined
  }
  const row = value as JsonRecord
  const aliases = [row.labelPath, row.label, row.name, row.fieldCode, row.operation, row.itemLabel, row.testItem, row.testCondition]
    .map(normalizedText).filter(Boolean)
  const expected = [normalizedText(code.replace(/^X_[A-Z]+__/, '')), normalizedText(name)].filter(Boolean)
  if (aliases.some((alias) => expected.some((item) => alias === item || alias.includes(item)))) {
    const extracted = extractBusinessValue(row)
    if (extracted) return row
  }
  for (const [key, child] of Object.entries(row)) {
    const semanticKey = normalizedText(key.replace(/^TABLE\.COLUMN\./i, ''))
    if (expected.some((item) => semanticKey === item || semanticKey.includes(item))) return child
    const found = findSemanticFieldValue(child, code, name)
    if (found !== undefined) return found
  }
  return undefined
}

const inputFieldValue = (sample: JsonRecord | undefined, field: InputFieldSummary) => {
  if (!sample) return '—'
  if (field.valueType === 'COMPOSITION') return formulaEntries(sample).length ? '已记录' : '—'
  const sections = [sample.process, sample.conditions, sample.facts, sample.observations]
  let found: unknown
  for (const section of sections) {
    found = findExactFieldValue(section, field.code)
    if (found !== undefined) break
  }
  if (found === undefined) {
    for (const section of sections) {
      found = findSemanticFieldValue(section, field.code, field.name)
      if (found !== undefined) break
    }
  }
  const extracted = extractBusinessValue(found)
  if (!extracted) return '—'
  const rendered = formatScalar(extracted.value)
  if (!rendered) return '—'
  const unit = extracted.unit ?? field.unit
  return unit && !normalizedText(rendered).includes(normalizedText(unit)) ? `${rendered} ${unit}` : rendered
}

const targetEntries = (sample: JsonRecord | undefined, targetId: string, targetFieldNames: string[]) => {
  const evidence = asRecord(sample?.targetEvidence)
  const targetValue = evidence.targetValue ?? evidence.normalizedTarget ?? evidence
  const entries = collectLeaves(targetValue, '', 12)
  if (entries.length) return entries
  const targetItems = structuredItems(sample?.observations).filter((item) => stringValue(asRecord(item).targetId) === targetId)
  const observationEntries = itemEntries(targetItems, 12)
  if (observationEntries.length) return observationEntries
  const names = targetFieldNames.map(normalizedText).filter(Boolean)
  const facts = factNodes(sample).filter((row) => {
    const label = normalizedText(row.labelPath ?? row.itemLabel ?? row.testItem ?? row.fieldName)
    return label && names.some((name) => label === name || label.includes(name) || name.includes(label))
  })
  return itemEntries(facts, 12)
}

const summaryText = (entries: Array<{ label: string; value: string }>, take = 3) => {
  if (!entries.length) return '未记录'
  const shown = entries.slice(0, take).map((item) => `${item.label} ${item.value}`).join('；')
  return entries.length > take ? `${shown}；另有${entries.length - take}项` : shown
}

const findFirst = (value: unknown, keys: RegExp): string | undefined => {
  if (!value || typeof value !== 'object') return undefined
  for (const [key, child] of Object.entries(value as JsonRecord)) {
    if (keys.test(key) && (typeof child === 'string' || typeof child === 'number')) {
      const text = String(child).trim()
      if (text && !uuidPattern.test(text)) return text
    }
    const nested = findFirst(child, keys)
    if (nested) return nested
  }
  return undefined
}

const recordName = (row: EligibilityRow, index: number) => {
  const sample = asRecord(row.sample)
  const identityFact = factNodes(sample).find((fact) => {
    const experimentField = asRecord(fact.experimentField)
    return stringValue(fact.targetPath) === '/sourceIdentity' || stringValue(experimentField.field).toUpperCase() === 'SOURCE_IDENTITY'
  })
  const factIdentity = identityFact?.normalizedValue ?? identityFact?.rawValue ?? identityFact?.displayValue
  if (typeof factIdentity === 'string' && factIdentity.trim()) return factIdentity.trim()
  const title = stringValue(asRecord(sample.facts).title)
  if (title && !uuidPattern.test(title)) return title
  const coordinates = sample.sourceCoordinates
  const named = findFirst(coordinates, /(experiment(No|Number|Code)|sample(No|Number|Code)|record(No|Number)|sourceIdentity|columnHeader)/i)
  if (named && !uuidPattern.test(named)) return named.replace(/^(EXPERIMENT|DATA_CENTER|SOURCE):/i, '')
  return `${row.authoritySourceType === 'EXPERIMENT' ? '实验记录' : '数据记录'} ${index + 1}`
}

const recordDate = (row: EligibilityRow) => findFirst(asRecord(row.sample), /(sourceExperimentDate|experimentDate|recordDate|testDate|date)$/i) ?? '—'

const invalidReasonCodes = new Set(['INVALID_Y_VALUE', 'SOURCE_INVALIDATED'])
const mappingReasonCodes = new Set(['TARGET_SEMANTIC_MISMATCH', 'TARGET_AMBIGUOUS', 'SOURCE_MAPPING_REQUIRED'])
const stateText = (row: EligibilityRow, mappingPending: boolean) => {
  if (row.state === 'TRAINABLE') return '可训练'
  if (mappingPending && mappingReasonCodes.has(row.primaryReasonCode ?? '')) return '等待确认 Y 来源'
  if (invalidReasonCodes.has(row.primaryReasonCode ?? '')) return '数据异常'
  if (row.state === 'REVIEW_REQUIRED') return '需要处理'
  return '本次不使用'
}
const stateColor = (row: EligibilityRow, mappingPending: boolean) => {
  if (row.state === 'TRAINABLE') return 'green'
  if (invalidReasonCodes.has(row.primaryReasonCode ?? '')) return 'red'
  if (mappingPending || row.state === 'REVIEW_REQUIRED') return 'gold'
  return undefined
}
const displayReason = (row: EligibilityRow, mappingPending: boolean) => mappingPending && mappingReasonCodes.has(row.primaryReasonCode ?? '')
  ? '确认当前 Y 的字段对应后重新检查'
  : reasonText(row)
const reasonText = (row: EligibilityRow) => row.primaryReasonCode ? (reasonLabels[row.primaryReasonCode] ?? row.reasons?.[0]?.message ?? '暂不符合训练条件') : '输入和结果完整'

const algorithmNames = (valueType: string) => valueType === 'CONTINUOUS'
  ? ['Gaussian Process（高斯过程）', 'Random Forest（随机森林）', 'LightGBM', 'XGBoost']
  : valueType === 'ORDINAL'
    ? ['Ordinal Cumulative Logit（序数累计Logit）']
    : ['Logistic Regression（逻辑回归）', 'Random Forest（随机森林）', 'LightGBM', 'XGBoost']

function DataSection({ title, entries, empty = '未记录' }: { title: string; entries: Array<{ label: string; value: string }>; empty?: string }) {
  return <section className="modeling-record-section">
    <h4>{title}</h4>
    {entries.length ? <dl>{entries.map((entry, index) => <div key={`${entry.label}-${index}`}><dt>{entry.label}</dt><dd>{entry.value}</dd></div>)}</dl> : <Text type="secondary">{empty}</Text>}
  </section>
}

function TrainingInputCell({ sample, fields }: { sample: JsonRecord; fields: InputFieldSummary[] }) {
  const formulaField = fields.find((field) => field.valueType === 'COMPOSITION')
  const formula = formulaField ? formulaEntries(sample) : []
  const scalarFields = fields.filter((field) => field.valueType !== 'COMPOSITION')
  return <div className="modeling-training-inputs">
    {formulaField && <div className="modeling-training-input-group">
      <b>配方</b>
      {formula.length ? <div className="modeling-formula-chips">{formula.map((entry, index) => <span key={`${entry.label}-${index}`}><em>{entry.label}</em> {entry.value}</span>)}</div> : <Text type="secondary">—</Text>}
    </div>}
    <div className="modeling-scalar-inputs">{scalarFields.map((field) => <div key={field.currentVersionId ?? field.id}><small>{field.name}</small><strong>{inputFieldValue(sample, field)}</strong></div>)}</div>
  </div>
}

export type ModelingTargetWorkflowProps = {
  target: PredictionTargetSummary
  versions: TargetVersion[]
  mappingSuggestions?: SourceMappingSuggestions
  inputFields: InputFieldSummary[]
  selectedInputFieldIds: string[]
  eligibilitySummary?: EligibilitySummary
  eligibilityPage?: EligibilityPage
  selectedSampleRevisionIds: string[]
  eligibilityLoading: boolean
  trainingLoading: boolean
  canManage: boolean
  modelAvailable: boolean
  onBack: () => void
  onStepChange: (step: 'definition' | 'scheme' | 'training') => void
  activeLegacyStep: string
  onToggleInput: (fieldVersionId: string) => void
  onSaveInputs: () => void
  onReevaluate: () => void
  onToggleSample: (sampleRevisionId: string, checked: boolean) => void
  onSelectSamples: (sampleRevisionIds: string[]) => void
  onClearSamples: () => void
  onStartTraining: () => void
  onConfirmMapping: (suggestion: SourceMappingSuggestion) => void
  onOpenModels?: () => void
  onReviewRecord?: (eligibilityId: string) => void
}

export function ModelingTargetWorkflow(props: ModelingTargetWorkflowProps) {
  const [keyword, setKeyword] = useState('')
  const [recordFilter, setRecordFilter] = useState<'ALL' | 'TRAINABLE' | 'UNAVAILABLE' | 'SELECTED'>('ALL')
  const currentVersion = props.versions.find((item) => item.id === props.target.currentVersionId)
    ?? props.versions.find((item) => item.status === 'PUBLISHED')
    ?? props.versions[0]
  const step: Step = props.activeLegacyStep === 'definition' ? 'target' : props.activeLegacyStep === 'training' ? 'train' : 'data'
  const rows = props.eligibilityPage?.items ?? []
  const selectedSet = useMemo(() => new Set(props.selectedSampleRevisionIds), [props.selectedSampleRevisionIds])
  const selectedInputFields = useMemo(() => props.inputFields
    .filter((field) => field.currentVersionId && props.selectedInputFieldIds.includes(field.currentVersionId))
    .sort((a, b) => props.selectedInputFieldIds.indexOf(a.currentVersionId as string) - props.selectedInputFieldIds.indexOf(b.currentVersionId as string)),
  [props.inputFields, props.selectedInputFieldIds])
  const compositionSelected = selectedInputFields.some((field) => field.valueType === 'COMPOSITION')
  const scalarInputFields = selectedInputFields.filter((field) => field.valueType !== 'COMPOSITION')
  const targetFieldNames = useMemo(() => {
    const names = new Set<string>([props.target.name])
    props.mappingSuggestions?.suggestions.forEach((item) => names.add(item.fieldName))
    return [...names]
  }, [props.mappingSuggestions?.suggestions, props.target.name])
  const targetValueText = (row: EligibilityRow) => {
    const entries = targetEntries(asRecord(row.sample), props.target.id, targetFieldNames)
    const primary = entries.find((entry) => ['数值', '标准值', '原始值', '结果', '当前目标 Y'].includes(entry.label))
    if (!primary) return summaryText(entries, 2)
    const unit = currentVersion?.unit
    return unit && !normalizedText(primary.value).includes(normalizedText(unit)) ? `${primary.value} ${unit}` : primary.value
  }
  const filteredRows = useMemo(() => rows.filter((row, index) => {
    const selected = selectedSet.has(row.sampleRevisionId)
    const matchesState = recordFilter === 'ALL' || (recordFilter === 'TRAINABLE' ? row.state === 'TRAINABLE' : recordFilter === 'UNAVAILABLE' ? row.state !== 'TRAINABLE' : selected)
    if (!matchesState) return false
    const sample = asRecord(row.sample)
    const haystack = [recordName(row, index), row.logicalSampleKey, row.authoritySourceType, summaryText(formulaEntries(sample), 20),
      ...scalarInputFields.map((field) => `${field.name} ${inputFieldValue(sample, field)}`), targetValueText(row), reasonText(row)].join(' ').toLowerCase()
    return !keyword.trim() || haystack.includes(keyword.trim().toLowerCase())
  }), [keyword, recordFilter, rows, scalarInputFields, selectedSet])

  const count = (code: string) => props.eligibilitySummary?.funnel.find((item) => item.code === code)?.count ?? 0
  const total = props.eligibilitySummary?.total ?? rows.length
  const trainable = props.eligibilitySummary?.trainable ?? rows.filter((row) => row.state === 'TRAINABLE').length
  const pendingMappings = props.mappingSuggestions?.suggestions.filter((item) => item.confirmationRequired) ?? []
  const mappingPending = pendingMappings.length > 0

  const columns: ColumnsType<EligibilityRow> = [
    { title: '选择', width: 52, render: (_, row) => <Checkbox disabled={row.state !== 'TRAINABLE'} checked={selectedSet.has(row.sampleRevisionId)} onChange={(event) => props.onToggleSample(row.sampleRevisionId, event.target.checked)} /> },
    { title: '实验记录', width: 190, render: (_, row, index) => <div className="modeling-record-identity"><strong>{recordName(row, index)}</strong><span>{row.authoritySourceType === 'EXPERIMENT' ? <ExperimentOutlined /> : <DatabaseOutlined />} {sourceLabels[row.authoritySourceType ?? ''] ?? '来源记录'} · {recordDate(row)}</span></div> },
    { title: `训练输入 X · 全部 ${selectedInputFields.length} 项`, render: (_, row) => <TrainingInputCell sample={asRecord(row.sample)} fields={selectedInputFields} /> },
    { title: `Y · ${props.target.name}`, width: 170, render: (_, row) => <strong className="modeling-target-value">{targetValueText(row)}</strong> },
    { title: '当前状态', width: 170, render: (_, row) => <div className="modeling-record-status"><Tag color={stateColor(row, mappingPending)}>{stateText(row, mappingPending)}</Tag><span>{displayReason(row, mappingPending)}</span></div> },
  ]

  const setStep = (next: Step) => props.onStepChange(next === 'target' ? 'definition' : next === 'data' ? 'scheme' : 'training')

  return <div className="modeling-target-workflow">
    <header className="modeling-workflow-header">
      <Button type="text" icon={<ArrowLeftOutlined />} onClick={props.onBack}>返回模型中心</Button>
      <div><span>正在配置模型</span><h1>{props.target.name}</h1></div>
      {props.modelAvailable && props.onOpenModels && <Button onClick={props.onOpenModels}>查看现有模型</Button>}
    </header>

    <nav className="modeling-workflow-steps" aria-label="建模步骤">
      {([['target', '1', '选择目标 Y'], ['data', '2', '选择数据与 X'], ['train', '3', '确认并训练']] as const).map(([key, no, label], index) => <div key={key} className="modeling-step-wrap">
        <button type="button" className={step === key ? 'active' : ''} onClick={() => setStep(key)}><b>{no}</b><span>{label}</span></button>
        {index < 2 && <i />}
      </div>)}
    </nav>

    {step === 'target' && <section className="modeling-workflow-panel">
      <div className="modeling-panel-title"><div><span>第 1 步</span><h2>确认要预测的目标 Y</h2><p>这里只确认预测结果的业务定义，不修改历史模型。</p></div><Tag color="blue">{valueTypeLabels[props.target.valueType] ?? props.target.valueType}</Tag></div>
      <div className="modeling-target-definition-grid">
        <div><span>目标 Y</span><strong>{props.target.name}</strong></div>
        <div><span>结果类型</span><strong>{valueTypeLabels[currentVersion?.valueType ?? props.target.valueType] ?? props.target.valueType}</strong></div>
        <div><span>单位</span><strong>{currentVersion?.unit || '无单位'}</strong></div>
        <div><span>当前定义</span><strong>第 {currentVersion?.version ?? props.target.currentVersion ?? 1} 版 · {currentVersion?.status === 'PUBLISHED' ? '已发布' : '草稿'}</strong></div>
      </div>
      {collectLeaves(currentVersion?.definition, '', 10).length > 0 && <DataSection title="测试条件" entries={collectLeaves(currentVersion?.definition, '', 10)} />}
      {pendingMappings.length > 0 ? <div className="modeling-mapping-confirm">
        <Alert type="warning" showIcon message={`有 ${pendingMappings.length} 个结果字段需要确认`} description="系统没有擅自选择名称相近的结果，请确认后再进入训练数据。" />
        {pendingMappings.map((item) => <div className="modeling-mapping-row" key={item.candidateKey}><div><strong>{item.fieldName}</strong><span>{[item.testMethod, item.substrate, item.stage, item.unit].filter(Boolean).join(' · ') || '未记录额外条件'} · 共 {item.dataCenterCount + item.experimentCount} 条</span></div><Button type="primary" ghost disabled={!props.canManage} onClick={() => props.onConfirmMapping(item)}>确认对应</Button></div>)}
      </div> : <Alert type="success" showIcon message="目标结果已经唯一匹配" description="系统会直接展示与当前 Y 对应的完整实验记录，不需要再处理字段来源。" />}
      <footer className="modeling-step-actions"><span /><Button type="primary" onClick={() => setStep('data')}>下一步：选择数据与 X</Button></footer>
    </section>}

    {step === 'data' && <section className="modeling-workflow-panel modeling-data-panel">
      <div className="modeling-panel-title"><div><span>第 2 步</span><h2>选择训练记录和输入 X</h2><p>每一行都是一条完整实验记录。勾选范围只影响本次训练，不修改实验原始数据。</p></div></div>
      {props.eligibilitySummary?.evaluationStatus === 'FAILED' && <Alert type="error" showIcon message="训练数据检查失败" description={props.eligibilitySummary.unavailableReason || '请重新检查当前配置。'} />}
      {(!props.eligibilitySummary || props.eligibilitySummary.evaluationStatus === 'NOT_EVALUATED') && <Alert type="info" showIcon message="正在等待训练数据检查" description="保存输入 X 后，系统会逐条检查配方、工艺和目标结果。" />}
      {mappingPending && <Alert
        className="modeling-data-blocker"
        type="warning"
        showIcon
        message={`先确认 ${pendingMappings.length} 个目标 Y 来源字段`}
        description={`下面 ${total} 条记录目前只是等待字段对应，并不表示它们全部有问题。确认后重新检查，系统才会分别判断哪些记录可以训练。`}
        action={<Button size="small" onClick={() => setStep('target')}>去确认字段</Button>}
      />}
      <div className="modeling-count-strip">
        <button type="button" onClick={() => setRecordFilter('ALL')}><span>当前记录</span><strong>{total}</strong><small>同一批数据</small></button>
        <button type="button" onClick={() => setRecordFilter('ALL')}><span>Y 有有效结果</span><strong>{mappingPending ? '—' : count('Y_VALID_VALUE')}</strong><small>{mappingPending ? '等待确认字段' : props.target.name}</small></button>
        <button type="button" onClick={() => setRecordFilter('ALL')}><span>必要 X 完整</span><strong>{mappingPending ? '—' : count('X_COMPLETE')}</strong><small>{mappingPending ? '尚未正式检查' : '按当前输入方案'}</small></button>
        <button type="button" onClick={() => setRecordFilter('TRAINABLE')} className="success"><span>可以训练</span><strong>{mappingPending ? '—' : trainable}</strong><small>{mappingPending ? '确认后计算' : '规则检查通过'}</small></button>
        <button type="button" onClick={() => setRecordFilter('SELECTED')} className="selected"><span>本次已选择</span><strong>{props.selectedSampleRevisionIds.length}</strong><small>将被冻结</small></button>
      </div>

      <div className="modeling-record-toolbar">
        <Input.Search allowClear value={keyword} onChange={(event) => setKeyword(event.target.value)} placeholder="搜索实验编号、材料、工艺或结果" />
        <Select value={recordFilter} onChange={setRecordFilter} options={[
          { value: 'ALL', label: '全部记录' }, { value: 'TRAINABLE', label: '只看可训练' }, { value: 'SELECTED', label: '只看已选择' }, { value: 'UNAVAILABLE', label: '只看暂不可用' },
        ]} />
        <Button onClick={() => props.onSelectSamples(filteredRows.filter((row) => row.state === 'TRAINABLE').map((row) => row.sampleRevisionId))}>选中当前结果</Button>
        <Button onClick={props.onClearSamples}>清空选择</Button>
        <Button disabled={!props.target.currentInputSchemeId} onClick={props.onReevaluate}>重新检查</Button>
      </div>
      <div className="modeling-visible-fields">
        <span>表格正在显示全部训练字段</span>
        {selectedInputFields.map((field) => <Tag key={field.currentVersionId}>X · {field.name}</Tag>)}
        <Tag color="blue">Y · {props.target.name}</Tag>
      </div>
      <Table<EligibilityRow>
        className="modeling-record-table"
        loading={props.eligibilityLoading}
        rowKey="id"
        columns={columns}
        dataSource={filteredRows}
        tableLayout="fixed"
        pagination={{ pageSize: 20, showSizeChanger: true, pageSizeOptions: [20, 50, 100], showTotal: (value) => `共 ${value} 条完整记录` }}
        expandable={{
          expandRowByClick: true,
          expandedRowRender: (row) => {
            const sample = asRecord(row.sample)
            return <div className="modeling-record-detail">
              <header><div><strong>{recordName(row, 0)}</strong><span>{sourceLabels[row.authoritySourceType ?? ''] ?? '来源记录'} · {stateText(row, mappingPending)}</span></div>{row.state === 'REVIEW_REQUIRED' && props.onReviewRecord && <Button size="small" onClick={() => props.onReviewRecord?.(row.id)}>处理待确认问题</Button>}</header>
              <div className="modeling-record-detail-grid">
                {compositionSelected && <DataSection title="输入 X · 配方组成" entries={formulaEntries(sample)} />}
                <DataSection title={`输入 X · 其余 ${scalarInputFields.length} 项`} entries={scalarInputFields.map((field) => ({ label: field.name, value: inputFieldValue(sample, field) }))} />
                <DataSection title={`目标 Y · ${props.target.name}`} entries={targetEntries(sample, props.target.id, targetFieldNames)} />
              </div>
            </div>
          },
        }}
        locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前筛选条件下没有记录" /> }}
      />

      <div className="modeling-x-section">
        <div className="modeling-x-head"><div><span>X 配置</span><h3>选择预测前能够确定的输入 X</h3><p>点击字段即可加入或移除。选择后，系统会重新检查上面的完整记录。</p></div><div><Tag color="blue">已选 {props.selectedInputFieldIds.length} 项 X</Tag><Button type="primary" disabled={!props.canManage || !props.selectedInputFieldIds.length} onClick={props.onSaveInputs}>保存 X 选择</Button></div></div>
        <div className="modeling-x-grid">{props.inputFields.length ? props.inputFields.map((field) => {
          const fieldVersionId = field.currentVersionId as string
          const selected = props.selectedInputFieldIds.includes(fieldVersionId)
          return <button type="button" key={fieldVersionId} className={selected ? 'selected' : ''} onClick={() => props.onToggleInput(fieldVersionId)}><span className="check">{selected ? <CheckCircleFilled /> : ''}</span><span><strong>{field.name}</strong><small>{inputTypeLabels[field.valueType] ?? field.valueType}{field.unit ? ` · ${field.unit}` : ''}</small></span><em>{selected ? 'X' : '+'}</em></button>
        }) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无可用输入 X" />}</div>
      </div>
      <footer className="modeling-step-actions"><Button onClick={() => setStep('target')}>上一步</Button><Button type="primary" disabled={!props.selectedSampleRevisionIds.length || !props.selectedInputFieldIds.length} onClick={() => setStep('train')}>下一步：确认并训练</Button></footer>
    </section>}

    {step === 'train' && <section className="modeling-workflow-panel">
      <div className="modeling-panel-title"><div><span>第 3 步</span><h2>确认本次建模范围</h2><p>系统会自动使用固定验证折比较候选算法，不需要手工设置训练参数。</p></div></div>
      <div className="modeling-training-summary">
        <div><span>目标 Y</span><strong>{props.target.name}</strong><small>{valueTypeLabels[props.target.valueType]}{currentVersion?.unit ? ` · ${currentVersion.unit}` : ''}</small></div>
        <div><span>输入 X</span><strong>{props.selectedInputFieldIds.length} 项</strong><small>{props.inputFields.filter((field) => field.currentVersionId && props.selectedInputFieldIds.includes(field.currentVersionId)).map((field) => field.name).join('、') || '尚未选择'}</small></div>
        <div><span>训练记录</span><strong>{props.selectedSampleRevisionIds.length} 条</strong><small>从 {total} 条当前记录中选择</small></div>
        <div><span>暂不纳入</span><strong>{Math.max(0, total - props.selectedSampleRevisionIds.length)} 条</strong><small>缺少 Y、X 或未通过规则检查</small></div>
      </div>
      <div className="modeling-algorithms"><div><span>系统将自动比较</span><h3>候选算法</h3><p>所有算法使用同一批记录和同一验证折，最终保留验证表现最合适的模型。</p></div><div>{algorithmNames(props.target.valueType).map((name) => <Tag key={name}>{name}</Tag>)}</div></div>
      {!props.selectedSampleRevisionIds.length && <Alert type="warning" showIcon message="尚未选择训练记录" description="返回上一步，至少选择一条可训练记录。" />}
      <footer className="modeling-step-actions"><Button onClick={() => setStep('data')}>返回修改数据和 X</Button><Space><Text type="secondary">开始后，本次记录范围和 X/Y 配置会被冻结。</Text><Button type="primary" size="large" loading={props.trainingLoading} disabled={!props.canManage || !props.selectedSampleRevisionIds.length || !props.selectedInputFieldIds.length} onClick={props.onStartTraining}>开始训练</Button></Space></footer>
    </section>}
  </div>
}
