import {
  ApiOutlined, DatabaseOutlined, EditOutlined, InfoCircleOutlined, PlusOutlined, SafetyCertificateOutlined,
  SettingOutlined,
} from '@ant-design/icons'
import {
  Alert, App, Button, Card, Collapse, Descriptions, Divider, Drawer, Empty, Form, Input, InputNumber,
  Modal, Select, Space, Switch, Table, Tabs, Tag, Typography,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import { modelingApi } from '@/services/ai-rnd/modeling-api'
import { predictionApi } from '@/services/ai-rnd/prediction-api'
import type {
  AvailabilityStage, InputFieldCommand, InputFieldSummary, InputFieldVersion, InputScheme,
  InputValueType, MaterialAlias, MaterialDictionary, MaterialReference, PredictionTargetSummary,
  SourceMappingVersion, TargetCommand, TargetVersion, TrainingPolicy, ValueType,
  SourceMappingSuggestions, SourceMappingSuggestion, EligibilitySummary, EligibilityPage, EligibilityDetail, EligibilityReview, QualityPolicy,
} from '@/services/ai-rnd/ai-rnd-types'
import { useAuthStore } from '@/stores/auth-store'
import './modeling-settings.css'

const { Text, Title } = Typography
const valueTypeLabels: Record<ValueType, string> = { CONTINUOUS: '连续值', ORDINAL: '序数等级', BINARY: '二分类', CATEGORICAL: '多分类' }
const fieldTypeLabels: Record<InputValueType, string> = { NUMBER: '数值', STRING: '文本', BOOLEAN: '布尔', CATEGORY: '类别', COMPOSITION: '配方组成' }
const statusColor: Record<string, string> = { ACTIVE: 'green', PUBLISHED: 'green', FROZEN: 'blue', DRAFT: 'gold', RETIRED: 'default', PAUSED: 'orange' }
const lifecycleLabels: Record<string, string> = { ACTIVE: '启用中', PUBLISHED: '已发布', FROZEN: '已冻结', DRAFT: '草稿', RETIRED: '已停用', PAUSED: '已暂停' }
const eligibilityFunnelLabels: Record<string, string> = {
  SOURCE_OBSERVATIONS: '来源记录', DEDUPLICATED_SAMPLES: '去重后样本', Y_MATCHED: '匹配到目标结果',
  Y_VALID_VALUE: '目标结果有效', X_COMPLETE: '输入条件齐全', MATERIAL_REVIEW_PASSED: '材料与审查通过', TRAINABLE: '最终可训练',
}
const eligibilityReasonLabels: Record<string, string> = {
  MISSING_REQUIRED_X: '缺少必需输入', UNKNOWN_MATERIAL: '材料身份待确认', MATERIAL_NOT_IN_MODEL: '材料不在当前字典',
  TARGET_SEMANTIC_MISMATCH: '没有匹配的目标结果', TARGET_AMBIGUOUS: '目标结果有多个匹配', INVALID_Y_VALUE: '目标结果无效',
  UNSUPPORTED_OBSERVATION_TYPE: '观测类型不支持', SOURCE_INVALIDATED: '来源已失效', SOURCE_NOT_CONFIRMED: '来源尚未确认',
  SOURCE_MAPPING_REQUIRED: '缺少来源语义映射',
  FORMULA_BASIS_UNRESOLVED: '配方比例或基准缺失', UNIT_UNKNOWN: '单位无法确认', POST_EXPERIMENT_FIELD_NOT_ALLOWED: '误用了实验后字段',
  SAMPLE_IDENTITY_CONFLICT: '样本身份有冲突', ANOMALY_REVIEW_REQUIRED: '命中异常规则',
}
const eligibilityStateLabels: Record<string, string> = { TRAINABLE: '可训练', EXCLUDED: '已排除', REVIEW_REQUIRED: '待人工审查' }
const sourceTypeLabels: Record<string, string> = { DATA_CENTER: '数据中心', EXPERIMENT: '实验记录本' }
const lifecycleOptions = ['DRAFT', 'ACTIVE', 'PAUSED', 'RETIRED'].map((value) => ({ value, label: lifecycleLabels[value] ?? value }))

type TargetFormValue = {
  code: string; name: string; category: string; valueType: ValueType; unit?: string; classes?: string
  testMethod?: string; sopCode?: string; testStage?: string; pretreatment?: string
  optimizationDirection?: string; minimum?: number; maximum?: number; materialScope?: string; fixedConditions?: string
}
type FieldFormValue = {
  code: string; name: string; valueType: InputValueType; unit?: string; availabilityStage: AvailabilityStage
  standardFieldDictionaryId?: string; standardFieldCodes?: string
}
type DictionaryFormValue = {
  code: string
  items: Array<{ materialId: string; role: string; token?: string }>
}
type MappingFormValue = {
  targetVersionId: string; sourceType: 'DATA_CENTER' | 'EXPERIMENT'; targetFieldCode: string; sourceAliases?: string
  sourceUnit?: string; transformation?: string; fixedConditions?: string
}
type SchemeFormValue = {
  targetVersionId: string; materialDictionaryVersionId?: string; code: string; name: string
  inputFieldVersionIds: string[]
}
type PolicyFormValue = {
  minimumTrainableSamples: number; minimumIndependentLineages: number; minimumSourceGroups: number; minimumPerClass?: number
  foldCount: number; primaryMetric: string; metricThreshold: number; requireFairComparison: boolean
  autoTrainingEnabled: boolean; dataNature: 'REAL' | 'SYNTHETIC'; retrainMinimumNewSamples: number
  minimumIntervalHours: number; replicateHandling: string; candidateAlgorithms: string[]; seed: number; timeoutMinutes: number
  anomalyRules?: Array<{ fieldCode: string; minimum?: number; maximum?: number }>
}
type AliasFormValue = { materialId: string; alias: string; expectedRevision?: number }
type QualityFormValue = {
  defaultTrustLevel: 'HIGH' | 'MEDIUM' | 'LOW'; defaultExplanation: string
  rules?: Array<{ code: string; priority: number; trustLevel: 'HIGH' | 'MEDIUM' | 'LOW'; explanation: string; domainStatus?: string; warningCode?: string; minimumEvidenceCoverage?: number; formulaMinimum?: number; formulaMaximum?: number; metric?: string; operator?: string; metricValue?: number }>
}

type ModelingSettingsPageProps = {
  embedded?: boolean
  initialTargetId?: string
  openCreateTarget?: boolean
  onCreateTargetOpened?: () => void
  onOpenModels?: (targetId: string) => void
}

export function ModelingSettingsPage({ embedded = false, initialTargetId, openCreateTarget, onCreateTargetOpened, onOpenModels }: ModelingSettingsPageProps = {}) {
  const navigate = useNavigate()
  const { message, modal } = App.useApp()
  const canModel = useAuthStore((state) => state.can('ai.modeling.manage'))
  const canConfig = useAuthStore((state) => state.can('ai.config.manage'))
  const [tab, setTab] = useState('targets')
  const [loading, setLoading] = useState(false)
  const [targets, setTargets] = useState<PredictionTargetSummary[]>([])
  const [targetTotal, setTargetTotal] = useState(0)
  const [targetPage, setTargetPage] = useState(1)
  const [targetKeyword, setTargetKeyword] = useState('')
  const [targetStatus, setTargetStatus] = useState<string>()
  const [targetType, setTargetType] = useState<string>()
  const [targetCategory, setTargetCategory] = useState<string>()
  const [fields, setFields] = useState<InputFieldSummary[]>([])
  const [fieldTotal, setFieldTotal] = useState(0)
  const [fieldPage, setFieldPage] = useState(1)
  const [fieldKeyword, setFieldKeyword] = useState('')
  const [fieldStatus, setFieldStatus] = useState<string>()
  const [fieldType, setFieldType] = useState<string>()
  const [materials, setMaterials] = useState<MaterialReference[]>([])
  const [aliases, setAliases] = useState<MaterialAlias[]>([])
  const [dictionaries, setDictionaries] = useState<MaterialDictionary[]>([])

  const [selectedTarget, setSelectedTarget] = useState<PredictionTargetSummary>()
  const [targetVersions, setTargetVersions] = useState<TargetVersion[]>([])
  const [mappings, setMappings] = useState<SourceMappingVersion[]>([])
  const [mappingSuggestions, setMappingSuggestions] = useState<SourceMappingSuggestions>()
  const [schemes, setSchemes] = useState<InputScheme[]>([])
  const [policies, setPolicies] = useState<TrainingPolicy[]>([])
  const [qualityPolicies, setQualityPolicies] = useState<QualityPolicy[]>([])
  const [eligibilitySummary, setEligibilitySummary] = useState<EligibilitySummary>()
  const [eligibilityPage, setEligibilityPage] = useState<EligibilityPage>()
  const [eligibilityLoading, setEligibilityLoading] = useState(false)
  const [eligibilityDetail, setEligibilityDetail] = useState<EligibilityDetail>()
  const [eligibilityDetailLoading, setEligibilityDetailLoading] = useState(false)
  const [reviewTarget, setReviewTarget] = useState<EligibilityReview>()
  const [reviewForm] = Form.useForm<{ decision: 'KEEP' | 'EXCLUDE' | 'MERGE' | 'KEEP_SEPARATE' | 'REMAP'; reason: string; materialId?: string }>()
  const [selectedField, setSelectedField] = useState<InputFieldSummary>()
  const [fieldVersions, setFieldVersions] = useState<InputFieldVersion[]>([])
  const [targetEditor, setTargetEditor] = useState<{ target?: PredictionTargetSummary; version?: TargetVersion }>()
  const [fieldEditor, setFieldEditor] = useState<{ field?: InputFieldSummary }>()
  const [mappingOpen, setMappingOpen] = useState(false)
  const [schemeOpen, setSchemeOpen] = useState(false)
  const [policyOpen, setPolicyOpen] = useState(false)
  const [qualityOpen, setQualityOpen] = useState(false)
  const [aliasOpen, setAliasOpen] = useState(false)
  const [dictionaryOpen, setDictionaryOpen] = useState(false)
  const [targetForm] = Form.useForm<TargetFormValue>()
  const [fieldForm] = Form.useForm<FieldFormValue>()
  const [mappingForm] = Form.useForm<MappingFormValue>()
  const [schemeForm] = Form.useForm<SchemeFormValue>()
  const [policyForm] = Form.useForm<PolicyFormValue>()
  const [qualityForm] = Form.useForm<QualityFormValue>()
  const [aliasForm] = Form.useForm<AliasFormValue>()
  const [dictionaryForm] = Form.useForm<DictionaryFormValue>()
  const watchedFieldType = Form.useWatch('valueType', fieldForm)
  const openedTargetRef = useRef<string>()

  const report = useCallback((error: unknown) => { void message.error(error instanceof Error ? error.message : '操作失败') }, [message])

  const loadTargets = useCallback(async () => {
    setLoading(true)
    try {
      const result = await modelingApi.targets({ keyword: targetKeyword || undefined, status: targetStatus, valueType: targetType, category: targetCategory, page: targetPage, size: 20 })
      setTargets(result.items); setTargetTotal(result.total)
    } catch (error) { report(error) } finally { setLoading(false) }
  }, [report, targetCategory, targetKeyword, targetPage, targetStatus, targetType])

  const loadFields = useCallback(async () => {
    setLoading(true)
    try {
      const result = await modelingApi.inputFields({ keyword: fieldKeyword || undefined, status: fieldStatus, valueType: fieldType, page: fieldPage, size: 20 })
      setFields(result.items); setFieldTotal(result.total)
    } catch (error) { report(error) } finally { setLoading(false) }
  }, [fieldKeyword, fieldPage, fieldStatus, fieldType, report])

  const loadReferencesAndMaterials = useCallback(async () => {
    try {
      const [materialRows, aliasRows, dictionaryRows] = await Promise.all([
        modelingApi.materials(), modelingApi.materialAliases(), modelingApi.materialDictionaries(),
      ])
      setMaterials(materialRows); setAliases(aliasRows); setDictionaries(dictionaryRows)
    } catch (error) { report(error) }
  }, [report])

  useEffect(() => { void loadTargets() }, [loadTargets])
  useEffect(() => { void loadFields() }, [loadFields])
  useEffect(() => { void loadReferencesAndMaterials() }, [loadReferencesAndMaterials])

  const openTarget = async (row: PredictionTargetSummary) => {
    try {
      const [fresh, versions, sourceMappings, suggestions, inputSchemes, trainingPolicies, qualityRows] = await Promise.all([
        modelingApi.target(row.id), modelingApi.targetVersions(row.id), modelingApi.sourceMappings(row.id),
        modelingApi.sourceMappingSuggestions(row.id), modelingApi.inputSchemes(row.id), modelingApi.trainingPolicies(row.id), predictionApi.qualityPolicies(row.id),
      ])
      setSelectedTarget(fresh); setTargetVersions(versions); setMappings(sourceMappings);setMappingSuggestions(suggestions); setSchemes(inputSchemes); setPolicies(trainingPolicies); setQualityPolicies(qualityRows)
    } catch (error) { report(error) }
  }

  const loadEligibility = useCallback(async (target?: PredictionTargetSummary) => {
    if (!target?.currentVersionId || !target.currentInputSchemeId) { setEligibilitySummary(undefined); setEligibilityPage(undefined); return }
    setEligibilityLoading(true)
    try {
      const [summary, page] = await Promise.all([
        modelingApi.eligibilitySummary(target.currentVersionId, target.currentInputSchemeId),
        modelingApi.eligibilityPage({ targetVersionId: target.currentVersionId, inputSchemeId: target.currentInputSchemeId, page: 1, size: 20 }),
      ])
      setEligibilitySummary(summary); setEligibilityPage(page)
    } catch (error) { report(error) } finally { setEligibilityLoading(false) }
  }, [report])

  useEffect(() => { void loadEligibility(selectedTarget) }, [loadEligibility, selectedTarget])

  const requestEligibility = async () => {
    if (!selectedTarget) return
    try { await modelingApi.reevaluateTarget(selectedTarget.id); void message.success('资格重算已加入后台任务'); await loadEligibility(selectedTarget) }
    catch (error) { report(error) }
  }

  const openEligibilityDetail = async (id: string) => {
    setEligibilityDetailLoading(true)
    try { setEligibilityDetail(await modelingApi.eligibilityDetail(id)) } catch (error) { report(error) } finally { setEligibilityDetailLoading(false) }
  }

  const submitReviewDecision = async () => {
    if (!reviewTarget) return
    try {
      const values = await reviewForm.validateFields()
      await modelingApi.decideReview(reviewTarget.id, { decision: values.decision, reason: values.reason, materialId: values.materialId, expectedRevision: reviewTarget.revision })
      setReviewTarget(undefined); reviewForm.resetFields(); void message.success('审查决定已提交，系统将重新计算资格')
      if (eligibilityDetail?.eligibility.id) await openEligibilityDetail(eligibilityDetail.eligibility.id)
      await loadEligibility(selectedTarget)
    } catch (error) { if (error instanceof Error) report(error) }
  }

  const refreshTarget = async () => {
    if (selectedTarget) await openTarget(selectedTarget)
    await loadTargets()
  }


  const openField = async (row: InputFieldSummary) => {
    try { setSelectedField(row); setFieldVersions(await modelingApi.inputFieldVersions(row.id)) } catch (error) { report(error) }
  }

  const showTargetEditor = (target?: PredictionTargetSummary, version?: TargetVersion) => {
    setTargetEditor({ target, version })
    const definition = version?.definition ?? {}
    const valueDomain = (definition.valueDomain ?? {}) as Record<string, number>
    targetForm.setFieldsValue({
      code: target?.code ?? '', name: target?.name ?? '', category: target?.category ?? '',
      valueType: version?.valueType ?? target?.valueType ?? 'CONTINUOUS', unit: version?.unit,
      classes: version?.classes?.join(', '), testMethod: stringValue(definition.testMethod), sopCode: stringValue(definition.sopCode),
      testStage: stringValue(definition.testStage), pretreatment: stringValue(definition.pretreatment),
      optimizationDirection: stringValue(definition.optimizationDirection), minimum: valueDomain.minimum, maximum: valueDomain.maximum,
      materialScope: stringValue(definition.materialScope), fixedConditions: stringValue(definition.fixedConditions),
    })
  }

  useEffect(() => {
    if (openCreateTarget && canModel) {
      showTargetEditor()
      onCreateTargetOpened?.()
    }
  }, [canModel, onCreateTargetOpened, openCreateTarget])

  useEffect(() => {
    if (!initialTargetId) return
    if (openedTargetRef.current === initialTargetId) return
    const row = targets.find((item) => item.id === initialTargetId)
    openedTargetRef.current = initialTargetId
    if (row) { void openTarget(row); return }
    void (async () => {
      try { const fetched = await modelingApi.target(initialTargetId); await openTarget(fetched) } catch (error) { report(error) }
    })()
  }, [initialTargetId, report, targets])

  const saveTarget = async () => {
    try {
      const v = await targetForm.validateFields()
      const command: TargetCommand = {
        code: v.code, name: v.name, category: v.category, valueType: v.valueType, unit: v.unit,
        classes: split(v.classes), expectedRevision: targetEditor?.target?.revision,
        definition: { testMethod: v.testMethod ?? '', sopCode: v.sopCode ?? '', testStage: v.testStage ?? '', pretreatment: v.pretreatment ?? '', fixedConditions: v.fixedConditions ?? '', materialScope: v.materialScope ?? '', optimizationDirection: v.optimizationDirection ?? '', valueDomain: v.minimum == null || v.maximum == null ? null : { minimum: v.minimum, maximum: v.maximum } },
        observationSemantics: targetEditor?.version?.observationSemantics ?? {},
      }
      if (targetEditor?.version) await modelingApi.updateTargetVersion(targetEditor.version.id, command)
      else if (targetEditor?.target) await modelingApi.createTargetVersion(targetEditor.target.id, command)
      else await modelingApi.createTarget(command)
      setTargetEditor(undefined); targetForm.resetFields(); await refreshTarget(); void message.success('Y定义已保存')
    } catch (error) { if (error instanceof Error) report(error) }
  }

  const saveField = async () => {
    try {
      const v = await fieldForm.validateFields()
      const command: InputFieldCommand = {
        code: v.code, name: v.name, valueType: v.valueType, unit: v.unit, availabilityStage: v.availabilityStage,
        standardFieldDictionaryId: v.standardFieldDictionaryId, expectedRevision: fieldEditor?.field?.revision,
        definition: v.valueType === 'COMPOSITION' ? { standardFieldCodes: ['FORMULA.ITEM.MATERIAL_CODE','FORMULA.ITEM.RATIO'], preserveRecordedTotal: true } : {}, preprocessing: {},
      }
      if (fieldEditor?.field) await modelingApi.createInputFieldVersion(fieldEditor.field.id, command)
      else await modelingApi.createInputField(command)
      setFieldEditor(undefined); fieldForm.resetFields(); await loadFields(); if (selectedField) await openField(selectedField); void message.success('X字段已保存')
    } catch (error) { if (error instanceof Error) report(error) }
  }

  const saveMapping = async () => {
    if (!selectedTarget) return
    try {
      const v = await mappingForm.validateFields()
      await modelingApi.createSourceMapping(selectedTarget.id, {
        targetVersionId: v.targetVersionId, sourceType: v.sourceType,
        mapping: { targetFieldCode: v.targetFieldCode, sourceAliases: split(v.sourceAliases), sourceUnit: v.sourceUnit ?? '', transformation: v.transformation ?? 'IDENTITY', fixedConditions: split(v.fixedConditions) },
        expectedRevision: selectedTarget.revision,
      })
      setMappingOpen(false); mappingForm.resetFields(); await refreshTarget(); void message.success('来源映射草稿已保存')
    } catch (error) { if (error instanceof Error) report(error) }
  }

  const confirmSuggestedMapping = async (suggestion: SourceMappingSuggestion) => {
    if (!selectedTarget || !mappingSuggestions) return
    try {
      const result=await modelingApi.confirmSourceMapping(selectedTarget.id,{targetVersionId:mappingSuggestions.targetVersionId,candidateKey:suggestion.candidateKey,expectedRevision:selectedTarget.revision})
      void message.success(result.recomputeStatus==='DEFERRED'?'数据来源已确认；输入方案和策略就绪后再评估':'数据来源已确认，历史样本正在重新评估')
      await refreshTarget()
    } catch(error){report(error)}
  }

  const saveScheme = async () => {
    if (!selectedTarget) return
    try {
      const v = await schemeForm.validateFields()
      await modelingApi.createInputScheme(selectedTarget.id, {
        targetVersionId: v.targetVersionId, materialDictionaryVersionId:v.materialDictionaryVersionId, code: v.code, name: v.name, expectedRevision: selectedTarget.revision,
        fields: v.inputFieldVersionIds.map((id, ordinal) => ({ inputFieldVersionId: id, required: true, ordinal, override: {} })), preprocessing: {},
      })
      setSchemeOpen(false); schemeForm.resetFields(); await refreshTarget(); void message.success('输入方案草稿已保存')
    } catch (error) { if (error instanceof Error) report(error) }
  }

  const savePolicy = async () => {
    if (!selectedTarget) return
    try {
      const v = await policyForm.validateFields()
      const anomalyRules = (v.anomalyRules ?? []).map((rule) => ({
        fieldCode: rule.fieldCode,
        ...(rule.minimum === undefined ? {} : { minimum: rule.minimum }),
        ...(rule.maximum === undefined ? {} : { maximum: rule.maximum }),
      }))
      await modelingApi.createTrainingPolicy({
        targetId: selectedTarget.id,
        expectedRevision: selectedTarget.revision,
        qualification: {
          minimumTrainableSamples: v.minimumTrainableSamples,
          minimumIndependentLineages: v.minimumIndependentLineages,
          minimumSourceGroups: v.minimumSourceGroups,
          ...(selectedTarget.valueType === 'CONTINUOUS' ? {} : { minimumPerClass: v.minimumPerClass }),
          anomalyRules,
        },
        validation: { foldCount: v.foldCount, primaryMetric: v.primaryMetric, metricThreshold: v.metricThreshold, requireFairComparison: v.requireFairComparison },
        training: {
          autoTrainingEnabled: v.autoTrainingEnabled, dataNature: v.dataNature,
          retrainMinimumNewSamples: v.retrainMinimumNewSamples, minimumIntervalHours: v.minimumIntervalHours,
          replicateHandling: v.replicateHandling, candidateAlgorithms: v.candidateAlgorithms,
          seed: v.seed, timeoutMinutes: v.timeoutMinutes,
        },
      })
      setPolicyOpen(false); policyForm.resetFields(); await refreshTarget(); void message.success('训练策略草稿已保存')
    } catch (error) { if (error instanceof Error) report(error) }
  }

  const saveQualityPolicy = async () => {
    if (!selectedTarget) return
    try {
      const value = await qualityForm.validateFields()
      await predictionApi.createQualityPolicy(selectedTarget.id, {
        defaultTrustLevel: value.defaultTrustLevel, defaultExplanation: value.defaultExplanation,
        expectedRevision: selectedTarget.revision,
        rules: (value.rules ?? []).map((rule) => ({
          code: rule.code, priority: rule.priority, trustLevel: rule.trustLevel, explanation: rule.explanation,
          when: {
            ...(rule.domainStatus ? { domainStatuses: [rule.domainStatus] } : {}),
            ...(rule.warningCode ? { warningCodesAny: [rule.warningCode] } : {}),
            ...(rule.minimumEvidenceCoverage === undefined ? {} : { minimumEvidenceCoverage: rule.minimumEvidenceCoverage }),
            ...(rule.formulaMinimum === undefined && rule.formulaMaximum === undefined ? {} : { formulaTotal: { ...(rule.formulaMinimum === undefined ? {} : { minimum: rule.formulaMinimum }), ...(rule.formulaMaximum === undefined ? {} : { maximum: rule.formulaMaximum }) } }),
            ...(!rule.metric || !rule.operator || rule.metricValue === undefined ? {} : { validationMetrics: [{ metric: rule.metric, operator: rule.operator, value: rule.metricValue }] }),
          },
        })),
      })
      setQualityOpen(false);qualityForm.resetFields();await refreshTarget();void message.success('质量策略草稿已保存')
    } catch (error) { if (error instanceof Error) report(error) }
  }

  const saveAlias = async () => {
    try { const v=await aliasForm.validateFields();await modelingApi.createMaterialAlias(v);setAliasOpen(false);aliasForm.resetFields();await loadReferencesAndMaterials();void message.success('材料别名已保存') }
    catch(error){if(error instanceof Error)report(error)}
  }

  const saveDictionary = async () => {
    try {
      const v=await dictionaryForm.validateFields();const selected=v.items.map((item,index)=>{const material=materials.find((m)=>m.id===item.materialId);return {materialId:item.materialId,role:item.role.trim(),ordinal:index,token:item.token?.trim()||material?.code||item.materialId}})
      await modelingApi.createMaterialDictionary({code:v.code,items:selected,encoder:{type:'ONE_HOT',unknownPolicy:'BLOCK'}})
      setDictionaryOpen(false);dictionaryForm.resetFields();await loadReferencesAndMaterials();void message.success('材料字典草稿已保存')
    }catch(error){if(error instanceof Error)report(error)}
  }

  const targetColumns: ColumnsType<PredictionTargetSummary> = [
    { title: '性能分类', dataIndex: 'category', width: 130, render: (value: string) => <Tag>{value}</Tag> },
    { title: '预测目标 Y', dataIndex: 'name', render: (_: unknown, row) => <div><strong>{row.name}</strong><div className="modeling-muted">{valueTypeLabels[row.valueType]} · {row.currentInputSchemeName ? '已配置输入方案' : '尚未配置输入方案'}</div></div> },
    { title: '结果类型', dataIndex: 'valueType', width: 120, render: (v: ValueType) => valueTypeLabels[v] },
    { title: '定义状态', dataIndex: 'status', width: 105, render: (value: string) => <Tag color={statusColor[value]}>{lifecycleLabels[value] ?? value}</Tag> },
    { title: '当前训练方案', width: 170, render: (_: unknown, row) => row.currentInputSchemeName ?? <Text type="secondary">尚未冻结</Text> },
    { title: '数据与训练', width: 150, render: (_: unknown, row) => <div className="modeling-status-cell"><Text type="secondary">{row.evaluationStatus === 'COMPLETED' ? '已完成' : row.evaluationStatus === 'RUNNING' ? '评估中' : row.evaluationStatus === 'FAILED' ? '评估失败' : '待评估'}</Text><Text type="secondary">{row.trainingStatus === 'ACTIVE' ? '已有正式模型' : '尚未训练'}</Text></div> },
    { title: '操作', width: 90, render: (_: unknown, row) => <Button type="link" onClick={() => void openTarget(row)}>查看配置</Button> },
  ]
  const fieldColumns: ColumnsType<InputFieldSummary> = [
    { title: '输入字段 X', dataIndex: 'name', render: (_: unknown, row) => <div><strong>{row.name}</strong><div className="modeling-code">{row.code}</div></div> },
    { title: '类型', dataIndex: 'valueType', width: 130, render: (v: InputValueType) => fieldTypeLabels[v] },
    { title: '单位', dataIndex: 'unit', width: 100, render: (value?: string) => value || '—' },
    { title: '可用时点', dataIndex: 'availabilityStage', width: 140, render: (value: AvailabilityStage) => <Tag color={value === 'PRE_EXPERIMENT' ? 'blue' : 'orange'}>{value === 'PRE_EXPERIMENT' ? '实验前' : '实验后'}</Tag> },
    { title: '来源状态', render: (_:unknown,row) => row.standardFieldName ? <Tag color="green">已有来源</Tag> : row.valueType==='COMPOSITION'?<Tag color="blue">材料与比例</Tag>:<Text type="warning">待系统匹配</Text> },
    { title: '状态', dataIndex: 'status', width: 100, render: (value: string) => <Tag color={statusColor[value]}>{lifecycleLabels[value] ?? value}</Tag> },
    { title: '操作', width: 80, render: (_: unknown, row) => <Button type="link" onClick={() => void openField(row)}>详情</Button> },
  ]

  const aliasColumns: ColumnsType<MaterialAlias> = [
    { title: '别名', dataIndex: 'alias' },
    { title: 'MDM材料', render: (_: unknown, row) => `${row.materialCode} · ${row.materialName}` },
    { title: '状态', dataIndex: 'status', width: 100, render: (value: string) => <Tag color={statusColor[value]}>{value}</Tag> },
  ]
  const targetVersionColumns: ColumnsType<TargetVersion> = [
    { title: '版本', dataIndex: 'version', width: 80, render: (value: number) => `v${value}` },
    { title: '类型', dataIndex: 'valueType', width: 110, render: (value: ValueType) => valueTypeLabels[value] ?? value },
    { title: '单位', dataIndex: 'unit', width: 80, render: (value?: string) => value || '—' },
    { title: '状态', dataIndex: 'status', width: 100, render: (value: string) => <Tag color={statusColor[value]}>{lifecycleLabels[value] ?? value}</Tag> },
    { title: '操作', render: (_: unknown, version) => <Space>{canModel && version.status === 'DRAFT' && <Button size="small" icon={<EditOutlined />} onClick={() => showTargetEditor(selectedTarget, version)}>编辑</Button>}{canModel && version.status === 'DRAFT' && <Button size="small" onClick={() => void (async () => { try { await modelingApi.publishTargetVersion(version.id, selectedTarget?.revision ?? 0); await refreshTarget(); void message.success('Y版本已发布') } catch (error) { report(error) } })()}>发布</Button>}</Space> },
  ]
  const mappingColumns: ColumnsType<SourceMappingVersion> = [
    { title: '来源', dataIndex: 'sourceType', render: (value: string) => sourceTypeLabels[value] ?? value },
    { title: '已识别字段', render: (_:unknown,row) => display(row.mapping?.sourceFieldCode ?? row.mapping?.targetFieldCode ?? '已配置') },
    { title: '版本', dataIndex: 'version', width: 70, render: (value: number) => `v${value}` },
    { title: '状态', dataIndex: 'status', width: 100, render: (value: string) => <Tag color={statusColor[value]}>{lifecycleLabels[value] ?? value}</Tag> },
    { title: '操作', width: 90, render: (_: unknown, mapping) => canModel && mapping.status === 'DRAFT' ? <Button type="link" onClick={() => void (async () => { try { await modelingApi.publishSourceMapping(mapping.id, selectedTarget?.revision ?? 0); await refreshTarget(); void message.success('来源映射已发布') } catch (error) { report(error) } })()}>发布</Button> : null },
  ]
  const schemeColumns: ColumnsType<InputScheme> = [
    { title: '方案', render: (_: unknown, scheme) => <div>{scheme.name}<div className="modeling-code">{scheme.code} · v{scheme.version}</div></div> },
    { title: '字段', render: (_: unknown, scheme) => scheme.fields.map((field) => field.fieldName ?? field.inputFieldVersionId).join('、') },
    { title: '状态', dataIndex: 'status', width: 100, render: (value: string) => <Tag color={statusColor[value]}>{lifecycleLabels[value] ?? value}</Tag> },
    { title: '操作', width: 150, render: (_: unknown, scheme) => <Space><Button type="link" onClick={() => void (async () => { try { const preview = await modelingApi.previewInputScheme(scheme.id); modal.info({ title: '输入方案预览', content: <div><p>评估状态：待评估</p><p>原因：{preview.unavailableReason}</p>{preview.validationIssues.map((issue) => <p key={issue.code + issue.fieldCode}>{issue.fieldCode ? `${issue.fieldCode}：` : ''}{issue.message}</p>)}</div> }) } catch (error) { report(error) } })()}>预览</Button>{canModel && scheme.status === 'DRAFT' && <Button type="link" onClick={() => void (async () => { try { await modelingApi.freezeInputScheme(scheme.id, scheme.revision); await refreshTarget(); void message.success('方案已冻结，资格重算暂缓') } catch (error) { report(error) } })()}>冻结</Button>}</Space> },
  ]
  const policyColumns: ColumnsType<TrainingPolicy> = [
    { title: '版本', dataIndex: 'version', width: 80, render: (value: number) => `v${value}` },
    { title: '状态', dataIndex: 'status', render: (value: string) => <Tag color={statusColor[value]}>{lifecycleLabels[value] ?? value}</Tag> },
    { title: '策略版本', render: (_: unknown, policy) => <span className="modeling-policy-hash">配置已锁定 · {policy.policyHash.slice(0, 8)}…</span> },
    { title: '操作', width: 80, render: (_: unknown, policy) => canConfig && policy.status === 'DRAFT' ? <Button type="link" onClick={() => void (async () => { try { await modelingApi.publishTrainingPolicy(policy.id, selectedTarget?.revision ?? 0); await refreshTarget(); void message.success('训练策略已发布') } catch (error) { report(error) } })()}>发布</Button> : null },
  ]
  const qualityColumns: ColumnsType<QualityPolicy> = [
    { title: '版本', dataIndex: 'version', width: 75, render: (value: number) => `v${value}` },
    { title: '默认可信等级', dataIndex: 'defaultTrustLevel', width: 130, render: (value: string) => <Tag color={value === 'HIGH' ? 'green' : value === 'MEDIUM' ? 'gold' : 'orange'}>{value === 'HIGH' ? '高' : value === 'MEDIUM' ? '中' : '低'}</Tag> },
    { title: '规则', render: (_: unknown, row) => `${row.rules.length} 条 · ${row.defaultExplanation}` },
    { title: '状态', dataIndex: 'status', width: 90, render: (value: string) => <Tag color={statusColor[value]}>{lifecycleLabels[value] ?? value}</Tag> },
    { title: '操作', width: 80, render: (_: unknown, row) => canConfig && row.status === 'DRAFT' ? <Button type="link" onClick={() => void (async () => { try { await predictionApi.publishQualityPolicy(row.id, selectedTarget?.revision ?? 0); await refreshTarget(); void message.success('质量策略已发布') } catch (error) { report(error) } })()}>发布</Button> : null },
  ]
  const fieldVersionColumns: ColumnsType<InputFieldVersion> = [
    { title: '版本', dataIndex: 'version', width: 70, render: (value: number) => `v${value}` },
    { title: '类型', dataIndex: 'valueType' },
    { title: '来源状态', render: (_:unknown,row) => row.standardFieldName ? '已有来源' : row.valueType==='COMPOSITION'?'材料与比例':'待系统匹配' },
    { title: '状态', dataIndex: 'status', render: (value: string) => <Tag color={statusColor[value]}>{value}</Tag> },
    { title: '操作', render: (_: unknown, version) => canModel && version.status === 'DRAFT' ? <Button type="link" onClick={() => void (async () => { try { await modelingApi.publishInputFieldVersion(version.id, selectedField?.revision ?? 0); await Promise.all([loadFields(), selectedField ? openField(selectedField) : Promise.resolve()]); void message.success('X字段版本已发布') } catch (error) { report(error) } })()}>发布</Button> : null },
  ]

  const categories = useMemo(() => [...new Set(targets.map((item) => item.category))].map((value) => ({ label: value, value })), [targets])
  const publishedFields = fields.filter((item) => item.currentVersionId && item.status === 'ACTIVE')
  const publishedFieldOptions = publishedFields.flatMap((field) => field.currentVersionId
    ? [{ value: field.currentVersionId, label: `${field.name} · ${field.code}` }]
    : [])

  const confirmRetireDictionary = (dictionary: MaterialDictionary) => {
    modal.confirm({
      title: '停用材料字典版本？', content: '停用后历史模型仍保留该版本，但新方案不能再选择它。',
      okText: '停用', okButtonProps: { danger: true },
      onOk: () => { void (async () => { try { await modelingApi.retireMaterialDictionary(dictionary.id, dictionary.revision, '管理员停用'); await loadReferencesAndMaterials(); void message.success('材料字典已停用') } catch (error) { report(error) } })() },
    })
  }

  return <div className={`modeling-settings-page${embedded ? ' modeling-settings-panel' : ''}`}>
    {!embedded && <div className="modeling-header"><div><Title level={2}>建模设置</Title><Text className="modeling-muted">定义模型要预测的 Y，并维护可复用的输入字段库 X。</Text></div><Space><Button icon={<DatabaseOutlined />} onClick={() => navigate('/assistant/model-center/samples')}>样本来源</Button><Tag icon={<SafetyCertificateOutlined />} color="blue">配置版本可追溯</Tag></Space></div>}
    {!canModel && <Alert className="modeling-warning" type="info" showIcon message="当前为只读模式" description="你可以查看全部建模配置，但不能创建、发布或冻结版本。" />}
    <div className="modeling-hierarchy"><div className="modeling-hierarchy-item"><b>预测目标 Y</b><span>模型要预测什么结果</span></div><i>→</i><div className="modeling-hierarchy-item"><b>输入字段库 X</b><span>哪些实验信息可以作为输入</span></div><div className="modeling-hierarchy-note">具体 Y 的输入方案在目标详情中冻结；达到资格后由模型管理承接训练。</div></div>
    <Card className="modeling-card" styles={{ body: { paddingTop: 8 } }}>
      <Tabs activeKey={tab} onChange={setTab} items={[
        { key:'targets',label:<span><ApiOutlined /> 预测目标 Y</span>,children:<>
          <div className="modeling-toolbar"><div className="modeling-toolbar-filters"><Input.Search allowClear placeholder="搜索目标名称或编码" style={{width:240}} onSearch={(value: string)=>{setTargetKeyword(value);setTargetPage(1)}}/><Select allowClear placeholder="状态" style={{width:120}} options={lifecycleOptions} onChange={(value: string | undefined)=>{setTargetStatus(value);setTargetPage(1)}}/><Select allowClear placeholder="结果类型" style={{width:140}} options={Object.entries(valueTypeLabels).map(([value,label])=>({value,label}))} onChange={(value: string | undefined)=>{setTargetType(value);setTargetPage(1)}}/><Select allowClear placeholder="性能分类" style={{width:140}} options={categories} onChange={(value: string | undefined)=>{setTargetCategory(value);setTargetPage(1)}}/></div><Space><Button icon={<DatabaseOutlined />} onClick={() => navigate('/assistant/model-center/samples')}>样本来源</Button><Tag icon={<SafetyCertificateOutlined />} color="blue">配置版本可追溯</Tag></Space></div>
          {targets.length===0&&!loading?<Empty className="modeling-empty" description="当前组织尚未建立预测目标目录"/>:<Table rowKey="id" loading={loading} columns={targetColumns} dataSource={targets} scroll={{x:1080}} pagination={{current:targetPage,pageSize:20,total:targetTotal,onChange:setTargetPage,showSizeChanger:false}}/>}
        </>},
        { key:'fields',label:<span><SettingOutlined /> 输入字段库 X</span>,children:<>
          <div className="modeling-toolbar"><div className="modeling-toolbar-filters"><Input.Search allowClear placeholder="搜索字段名称或编码" style={{width:240}} onSearch={(value: string)=>{setFieldKeyword(value);setFieldPage(1)}}/><Select allowClear placeholder="状态" style={{width:120}} options={lifecycleOptions} onChange={(value: string | undefined)=>{setFieldStatus(value);setFieldPage(1)}}/><Select allowClear placeholder="字段类型" style={{width:140}} options={Object.entries(fieldTypeLabels).map(([value,label])=>({value,label}))} onChange={(value: string | undefined)=>{setFieldType(value);setFieldPage(1)}}/></div>{canModel&&<Button type="primary" icon={<PlusOutlined />} onClick={()=>{setFieldEditor({});fieldForm.resetFields();fieldForm.setFieldValue('availabilityStage','PRE_EXPERIMENT')}}>新增X</Button>}</div>
          <Table rowKey="id" loading={loading} columns={fieldColumns} dataSource={fields} scroll={{x:980}} pagination={{current:fieldPage,pageSize:20,total:fieldTotal,onChange:setFieldPage,showSizeChanger:false}}/>
        </>},
      ]}/>
    </Card>

    <Drawer width={760} title={selectedTarget?.name} open={Boolean(selectedTarget)} onClose={()=>setSelectedTarget(undefined)} destroyOnHidden>
      {selectedTarget&&<><div className="modeling-detail-summary"><div><label>业务编码</label><span>{selectedTarget.code}</span></div><div><label>性能分类</label><span>{selectedTarget.category}</span></div><div><label>当前训练方案</label><span>{selectedTarget.currentInputSchemeName??'尚未冻结'}</span></div><div><label>样本／训练状态</label><span>{selectedTarget.evaluationStatus === 'COMPLETED' ? '已完成' : selectedTarget.evaluationStatus === 'RUNNING' ? '评估中' : selectedTarget.evaluationStatus === 'FAILED' ? '评估失败' : '待评估'}／{selectedTarget.trainingStatus==='ACTIVE'?'已有正式模型':'尚未训练'}</span></div></div><div className="modeling-detail-pipeline"><div className="active"><b>1</b><span>定义 Y</span></div><i>→</i><div className={selectedTarget.currentInputSchemeId?'active':''}><b>2</b><span>冻结输入 X</span></div><i>→</i>{onOpenModels ? <button type="button" className={`modeling-pipeline-link ${selectedTarget.trainingStatus==='ACTIVE'?'active':''}`} onClick={() => onOpenModels(selectedTarget.id)}><b>3</b><span>模型列表</span></button> : <div className={selectedTarget.trainingStatus==='ACTIVE'?'active':''}><b>3</b><span>模型列表</span></div>}</div>
      <Tabs items={[
        {key:'definition',label:'定义',children:<><div className="modeling-section-head"><strong>定义版本</strong>{canModel&&<Button icon={<PlusOutlined/>} onClick={()=>showTargetEditor(selectedTarget)}>新建版本</Button>}</div><Table size="small" rowKey="id" pagination={false} dataSource={targetVersions} columns={targetVersionColumns} /></>},
        {key:'mapping',label:'数据来源',children:<><div className="source-summary-grid"><Card size="small"><Text type="secondary">数据中心找到</Text><Title level={3}>{mappingSuggestions?.dataCenterCount??0}<small> 条</small></Title></Card><Card size="small"><Text type="secondary">实验记录本找到</Text><Title level={3}>{mappingSuggestions?.experimentCount??0}<small> 条</small></Title></Card><Card size="small"><Text type="secondary">待确认</Text><Title level={3}>{mappingSuggestions?.pendingConfirmationCount??0}<small> 项</small></Title></Card></div>{mappingSuggestions?.suggestions.length?<Table size="small" rowKey="candidateKey" pagination={false} dataSource={mappingSuggestions.suggestions} columns={[{title:'识别到的结果',render:(_:unknown,row)=><div><strong>{row.fieldName}</strong><div className="modeling-muted">{[row.testMethod,row.load,row.substrate,row.stage,row.unit].filter(Boolean).join(' · ')||'没有额外测试条件'}</div></div>},{title:'覆盖',render:(_:unknown,row)=>`数据中心 ${row.dataCenterCount} · 实验本 ${row.experimentCount}`},{title:'样本示例',render:(_:unknown,row)=>row.samples.slice(0,2).map((sample)=><div key={`${sample.sourceType}-${sample.sampleName}`}>{sample.value} · {sample.location}</div>)},{title:'操作',width:100,render:(_:unknown,row)=><Button type="link" disabled={!canModel} onClick={()=>void confirmSuggestedMapping(row)}>确认对应</Button>}]} />:<Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="尚未从正式数据或已完成实验中找到相近结果"/>}<Collapse ghost items={[{key:'advanced',label:'高级配置',children:<><div className="modeling-section-head"><strong>已发布适配版本</strong>{canModel&&<Button icon={<PlusOutlined/>} onClick={()=>setMappingOpen(true)}>手工配置</Button>}</div><Table size="small" rowKey="id" pagination={false} dataSource={mappings} columns={mappingColumns}/></>}]} /></>},
        {key:'scheme',label:'训练数据与输入X',children:<>
          <Card size="small" loading={eligibilityLoading} title={<Space><span>资格评估</span>{eligibilitySummary?.evaluationStatus === 'COMPLETED' && <Tag color="green">已完成</Tag>}{eligibilitySummary?.evaluationStatus === 'RUNNING' && <Tag color="blue">评估中</Tag>}{eligibilitySummary?.evaluationStatus === 'FAILED' && <Tag color="red">失败</Tag>}</Space>} extra={<Button size="small" disabled={!canModel || !selectedTarget?.currentInputSchemeId} onClick={()=>void requestEligibility()}>重新评估</Button>}>
            {!eligibilitySummary || eligibilitySummary.evaluationStatus === 'NOT_EVALUATED' ? <Alert type="info" showIcon message="还没有计算训练资格" description="冻结输入方案、来源映射和训练策略后，管理员可以开始评估。评估前不显示虚假的样本数量。"/> : <>
              {eligibilitySummary.evaluationStatus === 'FAILED' && <Alert type="error" showIcon message="资格评估失败" description={eligibilitySummary.unavailableReason ?? '请检查配置后重试。'}/>}
              {eligibilitySummary.evaluationStatus === 'RUNNING' && <Alert type="info" showIcon message="正在计算训练资格" description="后台正在按当前配置逐条检查样本，完成后刷新此页面。"/>}
              {eligibilitySummary.evaluationStatus === 'COMPLETED' && <>
                <div className="eligibility-stat-grid">
                  <div className="eligibility-stat"><span>当前有效样本</span><strong>{eligibilitySummary.total ?? '—'}</strong><small>来源已确认且未失效</small></div>
                  <div className="eligibility-stat success"><span>可以用于训练</span><strong>{eligibilitySummary.trainable ?? '—'}</strong><small>全部门禁通过</small></div>
                  <div className="eligibility-stat danger"><span>暂不纳入</span><strong>{eligibilitySummary.excluded ?? '—'}</strong><small>需要补数据或修正映射</small></div>
                  <div className="eligibility-stat warning"><span>等待人工处理</span><strong>{eligibilitySummary.reviewRequired ?? '—'}</strong><small>材料、异常或身份问题</small></div>
                </div>
                <div className="eligibility-section-title"><strong>资格检查进度</strong><span>每一步都以当前有效样本为分母</span></div>
                <div className="eligibility-funnel">{eligibilitySummary.funnel.map((step) => <div className="eligibility-funnel-row" key={step.code}><span>{eligibilityFunnelLabels[step.code] ?? step.label}</span><div className="eligibility-bar"><i style={{width:`${step.denominator ? Math.min(100, step.count / step.denominator * 100) : 0}%`}} /></div><b>{step.count}<em>/{step.denominator}</em></b></div>)}</div>
                <div className="eligibility-section-title"><strong>输入字段覆盖率</strong><span>各字段单独计算，不能把百分比直接相加</span></div>
                <div className="eligibility-coverage-grid">{eligibilitySummary.fieldCoverage.map((field) => <div className="eligibility-coverage" key={field.fieldCode}><div><strong>{field.fieldName}</strong><small>{field.available}/{field.denominator} 条样本有值</small></div><b>{(field.ratio * 100).toFixed(1)}%</b><span>{field.reasonCodes.map((code) => eligibilityReasonLabels[code] ?? code).join('、') || '没有缺失'}</span></div>)}</div>
                <div className="eligibility-section-title"><strong>需要处理的样本</strong><span>打开样本详情可以追溯来源文件、实验版本和原始坐标</span></div>
                <Table size="small" rowKey="id" pagination={{pageSize:10}} dataSource={eligibilityPage?.items ?? []} columns={[{title:'样本',dataIndex:'logicalSampleKey',render:(v:string)=><span className="eligibility-sample-key">{v?.startsWith('SOURCE:') ? '来源样本' : v}</span>},{title:'来源',dataIndex:'authoritySourceType',render:(v:string)=>sourceTypeLabels[v] ?? v},{title:'资格状态',dataIndex:'state',render:(v:string)=><Tag color={v==='TRAINABLE'?'green':v==='REVIEW_REQUIRED'?'orange':'red'}>{eligibilityStateLabels[v] ?? v}</Tag>},{title:'主要原因',dataIndex:'primaryReasonCode',render:(v?:string)=>v ? (eligibilityReasonLabels[v] ?? v) : '—'},{title:'操作',width:90,render:(_:unknown,row)=> <Button type="link" onClick={()=>void openEligibilityDetail(row.id)}>查看详情</Button>}]}/>
              </>}
            </>}
          </Card>
          <div className="modeling-section-head"><strong>输入方案</strong>{canModel&&<Button icon={<PlusOutlined/>} onClick={()=>setSchemeOpen(true)}>新建方案</Button>}</div><Table size="small" rowKey="id" pagination={false} dataSource={schemes} columns={schemeColumns} /><Divider/><div className="modeling-section-head"><strong>训练策略（高级设置）</strong>{canConfig&&<Button onClick={()=>{policyForm.resetFields();policyForm.setFieldsValue({requireFairComparison:true,autoTrainingEnabled:false,dataNature:'REAL'});setPolicyOpen(true)}}>新建策略</Button>}</div><Table size="small" rowKey="id" pagination={false} dataSource={policies} columns={policyColumns} /><Divider/><div className="modeling-section-head"><div><strong>预测质量策略</strong><div className="modeling-muted">根据验证表现、适用域、证据覆盖和警告决定结果可信等级。</div></div>{canConfig&&<Button onClick={()=>{qualityForm.resetFields();qualityForm.setFieldsValue({defaultTrustLevel:'MEDIUM'});setQualityOpen(true)}}>新建策略</Button>}</div><Table size="small" rowKey="id" pagination={false} dataSource={qualityPolicies} columns={qualityColumns} /></>},
      ]}/></>}
    </Drawer>

    <Drawer width={760} title="样本详情" open={Boolean(eligibilityDetail)} loading={eligibilityDetailLoading} onClose={()=>setEligibilityDetail(undefined)} destroyOnHidden>
      {eligibilityDetail&&<>
        <Descriptions column={2} bordered size="small" items={[
          {key:'sample',label:'逻辑样本',children:typeof eligibilityDetail.sample.logicalSampleKey==='string'&&eligibilityDetail.sample.logicalSampleKey.startsWith('SOURCE:')?'来源样本':display(eligibilityDetail.sample.logicalSampleKey)},
          {key:'source',label:'权威来源',children:sourceTypeLabels[display(eligibilityDetail.sample.authoritySourceType)]??display(eligibilityDetail.sample.authoritySourceType)},
          {key:'status',label:'样本状态',children:display(eligibilityDetail.sample.status)},
          {key:'revision',label:'事实版本',children:display(eligibilityDetail.sample.revisionNo)},
          {key:'evaluated',label:'评估结果',children:<Tag color={eligibilityDetail.eligibility.state==='TRAINABLE'?'green':eligibilityDetail.eligibility.state==='REVIEW_REQUIRED'?'orange':'red'}>{eligibilityStateLabels[eligibilityDetail.eligibility.state]??eligibilityDetail.eligibility.state}</Tag>},
          {key:'reason',label:'主要原因',children:eligibilityDetail.eligibility.primaryReasonCode?eligibilityReasonLabels[eligibilityDetail.eligibility.primaryReasonCode]??eligibilityDetail.eligibility.primaryReasonCode:'—'},
        ]}/>
        <div className="eligibility-detail-section"><strong>原始事实与修正后的值</strong><div className="eligibility-json-grid"><div><small>配方</small><pre>{pretty(eligibilityDetail.sample.composition)}</pre></div><div><small>工艺与条件</small><pre>{pretty({process:eligibilityDetail.sample.process,conditions:eligibilityDetail.sample.conditions,facts:eligibilityDetail.sample.facts})}</pre></div><div><small>测试结果</small><pre>{pretty(eligibilityDetail.sample.observations)}</pre></div></div></div>
        <div className="eligibility-detail-section"><strong>来源定位</strong><pre>{pretty({coordinates:eligibilityDetail.sample.sourceCoordinates,experimentVersionId:eligibilityDetail.sample.experimentVersionId,confirmedSubmissionItemId:eligibilityDetail.sample.confirmedSubmissionItemId,recognitionJobId:eligibilityDetail.sample.recognitionJobId})}</pre></div>
        <div className="eligibility-detail-section"><strong>人工审查</strong>{eligibilityDetail.reviews.length===0?<Text type="secondary">当前没有审查记录</Text>:<>
          {eligibilityDetail.reviews.some((review)=>review.status==='OPEN'||review.status==='IN_REVIEW')&&<Text className="eligibility-review-group" type="secondary">当前待处理</Text>}
          {eligibilityDetail.reviews.filter((review)=>review.status==='OPEN'||review.status==='IN_REVIEW').map((review)=><div className="eligibility-review-row" key={review.id}><div><Tag color="orange">待处理</Tag><span>{eligibilityReasonLabels[review.reasonCode??review.reviewType]??review.reasonCode??review.reviewType}</span></div><Button size="small" onClick={()=>{setReviewTarget(review);reviewForm.setFieldsValue({decision:review.reviewType==='UNKNOWN_MATERIAL'?'REMAP':review.reviewType==='IDENTITY_CONFLICT'?'KEEP_SEPARATE':'KEEP'})}}>处理</Button></div>)}
          {eligibilityDetail.reviews.some((review)=>review.status!=='OPEN'&&review.status!=='IN_REVIEW')&&<Text className="eligibility-review-group" type="secondary">历史记录</Text>}
          {eligibilityDetail.reviews.filter((review)=>review.status!=='OPEN'&&review.status!=='IN_REVIEW').map((review)=><div className="eligibility-review-row" key={review.id}><div><Tag>已处理</Tag><span>{eligibilityReasonLabels[review.reasonCode??review.reviewType]??review.reasonCode??review.reviewType}</span></div></div>)}
        </>}</div>
      </>}
    </Drawer>

    <Drawer width={680} title={selectedField?.name} open={Boolean(selectedField)} onClose={()=>setSelectedField(undefined)} destroyOnHidden>{selectedField&&<><Descriptions column={2} bordered size="small" items={[{key:'code',label:'字段编码',children:selectedField.code},{key:'stage',label:'可用时点',children:selectedField.availabilityStage==='PRE_EXPERIMENT'?'实验前':'实验后'},{key:'source',label:'来源状态',children:selectedField.standardFieldName??(selectedField.valueType==='COMPOSITION'?'材料编码与比例':'系统待匹配')},{key:'status',label:'状态',children:<Tag color={statusColor[selectedField.status]}>{selectedField.status}</Tag>}]} /><div className="modeling-section-head"><strong>字段版本</strong>{canModel&&<Button onClick={()=>{setFieldEditor({field:selectedField});fieldForm.setFieldsValue({code:selectedField.code,name:selectedField.name,valueType:selectedField.valueType,unit:selectedField.unit,availabilityStage:selectedField.availabilityStage,standardFieldDictionaryId:undefined})}}>新建版本</Button>}</div><Table size="small" rowKey="id" pagination={false} dataSource={fieldVersions} columns={fieldVersionColumns} />{selectedField.valueType==='COMPOSITION'&&<><Divider/><div className="modeling-section-head"><div><strong>材料匹配与模型覆盖</strong><div className="modeling-muted">系统按材料编码、名称和有效别名自动识别，只需处理未识别项。</div></div>{canConfig&&<Space><Button onClick={()=>setAliasOpen(true)}>处理未识别材料</Button><Button onClick={()=>setDictionaryOpen(true)}>维护模型覆盖</Button></Space>}</div><Table size="small" rowKey="id" pagination={false} dataSource={aliases.slice(0,8)} columns={aliasColumns}/><Space wrap>{dictionaries.map((dictionary)=><Card size="small" key={dictionary.id} title={`${dictionary.code} · v${dictionary.version}`} extra={<Tag color={statusColor[dictionary.status]}>{dictionary.status}</Tag>}><div>{dictionary.items.length} 种已覆盖材料</div>{canConfig&&dictionary.status==='DRAFT'&&<Button type="link" onClick={()=>void (async()=>{try{await modelingApi.freezeMaterialDictionary(dictionary.id,dictionary.revision);await loadReferencesAndMaterials();void message.success('材料覆盖版本已冻结')}catch(error){report(error)}})()}>冻结</Button>}{canConfig&&dictionary.status!=='RETIRED'&&<Button type="link" danger onClick={()=>confirmRetireDictionary(dictionary)}>停用</Button>}</Card>)}</Space></>}</>}</Drawer>

    <Modal title="处理人工审查" open={Boolean(reviewTarget)} onCancel={()=>{setReviewTarget(undefined);reviewForm.resetFields()}} onOk={()=>void submitReviewDecision()} destroyOnHidden>
      <Alert type="info" showIcon message="决定只影响当前资格结果" description="原始事实不会在这里被修改；需要补值或修正来源时，请回到来源数据修订。"/>
      <Form form={reviewForm} layout="vertical" style={{marginTop:16}}>
        <Form.Item name="decision" label="处理方式" rules={[{required:true}]}><Select options={(reviewTarget?.reviewType==='UNKNOWN_MATERIAL'?[{value:'REMAP',label:'重新匹配材料'}]:reviewTarget?.reviewType==='IDENTITY_CONFLICT'?[{value:'MERGE',label:'确认合并'},{value:'KEEP_SEPARATE',label:'保留为独立样本'}]:[{value:'KEEP',label:'保留样本'},{value:'EXCLUDE',label:'排除样本'}])}/></Form.Item>
        <Form.Item name="materialId" label="匹配到的材料（重新匹配时填写）"><Select allowClear showSearch optionFilterProp="label" options={materials.map((m)=>({value:m.id,label:`${m.code} · ${m.name}`}))}/></Form.Item>
        <Form.Item name="reason" label="处理说明" rules={[{required:true}]}><Input.TextArea rows={3} placeholder="说明判断依据"/></Form.Item>
      </Form>
    </Modal>

    <Modal
      className="target-editor-modal"
      title={<div className="target-editor-title"><strong>{targetEditor?.version ? '编辑预测目标 Y' : targetEditor?.target ? '新建预测目标 Y 版本' : '新增预测目标 Y'}</strong><span>先定义“要预测什么”，再在目标详情中选择并冻结模型输入 X。</span></div>}
      open={Boolean(targetEditor)} onCancel={()=>setTargetEditor(undefined)} onOk={()=>void saveTarget()}
      okText={targetEditor?.version ? '保存版本' : '保存Y草稿'} cancelText="取消" width={1040} destroyOnHidden
    >
      <Form form={targetForm} layout="vertical" className="target-editor-form">
        <section className="target-editor-section">
          <div className="target-editor-section-head"><div><strong>预测目标定义</strong><span>这些内容会随 Y 版本冻结，历史模型不会被修改。</span></div><Tag color="blue">Y 定义</Tag></div>
          <div className="target-editor-grid">
            <Form.Item name="category" label="性能分类" rules={[{required:true,message:'请选择或填写性能分类'}]}><Input placeholder="例如：涂膜性能、材料性能或耐磨" /></Form.Item>
            <Form.Item name="name" label="预测目标名称" rules={[{required:true,message:'请输入预测目标名称'}]}><Input placeholder="例如：PET · 60°光泽 · UV固化后" /></Form.Item>
            <Form.Item name="valueType" label="结果类型" rules={[{required:true}]}><Select options={Object.entries(valueTypeLabels).map(([value,label])=>({value,label}))}/></Form.Item>
            <Form.Item name="unit" label="标准单位 / 等级"><Input placeholder="例如：GU、%、0B–5B" /></Form.Item>
            <Form.Item name="minimum" label="合理范围下限"><InputNumber style={{width:'100%'}} placeholder="未确认可留空" /></Form.Item>
            <Form.Item name="maximum" label="合理范围上限"><InputNumber style={{width:'100%'}} placeholder="未确认可留空" /></Form.Item>
            <Form.Item name="testMethod" label="测试方法" rules={[{required:true,message:'请输入测试方法'}]}><Input placeholder="例如：60度角光泽计" /></Form.Item>
            <Form.Item name="sopCode" label="SOP 编号"><Input placeholder="正式发布前需要绑定" /></Form.Item>
            <Form.Item name="materialScope" label="基材 / 材料范围"><Input placeholder="例如：PET / PC / 按实验记录" /></Form.Item>
            <Form.Item name="testStage" label="测试阶段"><Input placeholder="例如：初始、固化后、水煮后" /></Form.Item>
            <Form.Item name="pretreatment" label="固定前处理 / 测试条件"><Input placeholder="例如：85°C水煮1h；无则填无" /></Form.Item>
            <Form.Item name="fixedConditions" label="固定条件说明"><Input placeholder="只有影响语义匹配的固定条件才填写" /></Form.Item>
            <Form.Item name="optimizationDirection" label="目标方向"><Select allowClear placeholder="请选择" options={[{value:'MAXIMIZE',label:'越大越好'},{value:'MINIMIZE',label:'越小越好'},{value:'TARGET',label:'接近目标'}]}/></Form.Item>
            <Form.Item name="classes" label="类别定义（序数 / 分类）"><Input placeholder="例如：0B, 1B, 2B, 3B, 4B, 5B" /></Form.Item>
            <Form.Item name="code" label="业务编码" rules={[{required:true,message:'请输入业务编码'}]}><Input disabled={Boolean(targetEditor?.target)} placeholder="例如：GLOSS_60_UV_POST" /></Form.Item>
          </div>
        </section>
        <section className="target-editor-section target-input-preview">
          <div className="target-editor-section-head"><div><strong>模型输入 X</strong><span>当前只展示已发布字段；具体选入哪些 X、材料字典和预处理，在目标详情中保存为独立冻结方案。</span></div><Tag color="geekblue">X 方案独立版本化</Tag></div>
          <div className="target-preview-table-wrap">
            <table className="target-preview-table"><thead><tr><th>字段名称</th><th>类型</th><th>单位</th><th>可用时点</th><th>当前状态</th></tr></thead><tbody>
              {publishedFields.slice(0, 8).map((field)=><tr key={field.id}><td><strong>{field.name}</strong><small>{field.code}</small></td><td>{fieldTypeLabels[field.valueType]}</td><td>{field.unit ?? '—'}</td><td>{field.availabilityStage === 'PRE_EXPERIMENT' ? '实验前' : '实验后'}</td><td><Tag color="green">可选入方案</Tag></td></tr>)}
              {!publishedFields.length && <tr><td colSpan={5}><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有已发布的 X 字段" /></td></tr>}
            </tbody></table>
          </div>
          <div className="target-editor-note"><InfoCircleOutlined /> 保存 Y 草稿后，请在该目标详情的“训练数据与输入 X”中创建方案；未冻结方案、未发布映射和未确认 SOP 不会进入资格评估或正式预测。</div>
        </section>
      </Form>
    </Modal>
    <Modal title={fieldEditor?.field?'新建X字段版本':'新增输入字段X'} open={Boolean(fieldEditor)} onCancel={()=>setFieldEditor(undefined)} onOk={()=>void saveField()} destroyOnHidden><Form form={fieldForm} layout="vertical"><Form.Item name="code" label="字段编码" rules={[{required:true}]}><Input disabled={Boolean(fieldEditor?.field)}/></Form.Item><Form.Item name="name" label="字段名称" rules={[{required:true}]}><Input/></Form.Item><Form.Item name="valueType" label="字段类型" rules={[{required:true}]}><Select options={Object.entries(fieldTypeLabels).map(([value,label])=>({value,label}))}/></Form.Item><Form.Item name="unit" label="单位"><Input/></Form.Item><Form.Item name="availabilityStage" label="可用时点" rules={[{required:true}]}><Select options={[{value:'PRE_EXPERIMENT',label:'实验前'},{value:'POST_EXPERIMENT',label:'实验后'}]}/></Form.Item><Alert type="info" showIcon message={watchedFieldType==='COMPOSITION'?'材料组成由系统自动匹配材料与比例来源':'系统会按名称、类型、单位和已确认来源自动匹配输入数据'} description="你只需定义可用于建模的 X；来源绑定由系统完成，无法确认时会提示处理。"/></Form></Modal>
    <Modal title="确认数据来源" open={mappingOpen} onCancel={()=>setMappingOpen(false)} onOk={()=>void saveMapping()} destroyOnHidden><Alert type="info" showIcon message="系统已从数据中心和实验记录本查找匹配结果" description="通常只需确认来源；名称、单位或测试条件存在多个候选时，再补充下面的信息。"/><Form form={mappingForm} layout="vertical" style={{marginTop:16}}><Form.Item name="targetVersionId" label="Y版本" rules={[{required:true}]}><Select options={targetVersions.map((v)=>({value:v.id,label:`v${v.version} · ${v.status}`}))}/></Form.Item><Form.Item name="sourceType" label="来源" rules={[{required:true}]}><Select options={[{value:'DATA_CENTER',label:'数据中心'},{value:'EXPERIMENT',label:'实验记录本'}]}/></Form.Item><Form.Item name="targetFieldCode" label="对应结果" rules={[{required:true}]}><Input placeholder="系统自动匹配；存在多个候选时再确认"/></Form.Item><Form.Item name="sourceAliases" label="文件中的叫法（可选）"><Input/></Form.Item><Form.Item name="sourceUnit" label="单位（可选）"><Input/></Form.Item><Form.Item name="transformation" label="数值处理" initialValue="IDENTITY"><Select options={[{value:'IDENTITY',label:'保持原值'},{value:'NUMERIC',label:'转换为数值'},{value:'CLASS_MAP',label:'映射为等级/类别'}]}/></Form.Item><Form.Item name="fixedConditions" label="测试条件（可选）"><Input placeholder="例如：60°、500g、固化后"/></Form.Item></Form></Modal>
    <Modal title="新建输入方案" open={schemeOpen} onCancel={()=>setSchemeOpen(false)} onOk={()=>void saveScheme()} destroyOnHidden><Form form={schemeForm} layout="vertical"><Form.Item name="targetVersionId" label="已发布Y版本" rules={[{required:true}]}><Select options={targetVersions.filter((version)=>version.status==='PUBLISHED').map((version)=>({value:version.id,label:`v${version.version} · ${version.valueType}`}))}/></Form.Item><Form.Item name="code" label="方案编码" rules={[{required:true}]}><Input/></Form.Item><Form.Item name="name" label="方案名称" rules={[{required:true}]}><Input/></Form.Item><Form.Item name="inputFieldVersionIds" label="已发布输入字段" rules={[{required:true,type:'array',min:1}]}><Select mode="multiple" optionFilterProp="label" options={publishedFieldOptions}/></Form.Item><Form.Item name="materialDictionaryVersionId" label="冻结材料字典（含配方字段时必填）"><Select allowClear optionFilterProp="label" options={dictionaries.filter((dictionary)=>dictionary.status==='FROZEN').map((dictionary)=>({value:dictionary.id,label:`${dictionary.code} · v${dictionary.version}`}))}/></Form.Item></Form></Modal>
    <Modal width={900} title={`新建训练策略${selectedTarget ? ` · ${selectedTarget.name}` : ''}`} open={policyOpen} onCancel={()=>setPolicyOpen(false)} onOk={()=>void savePolicy()} destroyOnHidden>
      <Alert type="info" showIcon message="发布后将按这些规则决定何时训练、如何验证以及是否具备正式启用资格。样本门槛和误差阈值请填写已经确认的业务标准。"/>
      <Form form={policyForm} layout="vertical" className="policy-form">
        <h4>数据门槛</h4><div className="policy-grid">
          <Form.Item name="minimumTrainableSamples" label="最低可训练样本数" rules={[{required:true,message:'请填写样本门槛'}]}><InputNumber min={1} precision={0}/></Form.Item>
          <Form.Item name="minimumIndependentLineages" label="最低独立配方谱系数" rules={[{required:true,message:'请填写谱系门槛'}]}><InputNumber min={1} precision={0}/></Form.Item>
          <Form.Item name="minimumSourceGroups" label="最低独立来源组数" rules={[{required:true,message:'请填写来源门槛'}]}><InputNumber min={1} precision={0}/></Form.Item>
          {selectedTarget?.valueType !== 'CONTINUOUS' && <Form.Item name="minimumPerClass" label="每个类别最低样本数" rules={[{required:true,message:'请填写类别样本门槛'}]}><InputNumber min={1} precision={0}/></Form.Item>}
        </div>
        <h4>重复测量</h4><Form.Item name="replicateHandling" label="同一样本的多次测量如何进入训练" rules={[{required:true,message:'请选择重复测量处理方式'}]}><Select options={(selectedTarget?.valueType === 'CONTINUOUS' ? [['KEEP_GROUPED','分别保留，验证时保持同组'],['MEAN','取平均值'],['MEDIAN','取中位数']] : selectedTarget?.valueType === 'ORDINAL' ? [['KEEP_GROUPED','分别保留，验证时保持同组'],['MEDIAN_GRADE','取中位等级']] : [['KEEP_GROUPED','分别保留，验证时保持同组'],['MAJORITY','取多数类别'],['CONSENSUS_ONLY','仅接受完全一致']]).map(([value,label])=>({value,label}))}/></Form.Item>
        <h4>验证与质量门禁</h4><div className="policy-grid">
          <Form.Item name="foldCount" label="交叉验证折数" rules={[{required:true,message:'请填写2到10折'}]}><InputNumber min={2} max={10} precision={0}/></Form.Item>
          <Form.Item name="primaryMetric" label="主验证指标" rules={[{required:true,message:'请选择验证指标'}]}><Select options={(selectedTarget?.valueType === 'CONTINUOUS' ? [['mae','平均绝对误差（MAE）'],['rmse','均方根误差（RMSE）'],['r2','拟合度（R²）']] : selectedTarget?.valueType === 'ORDINAL' ? [['gradeMae','等级误差'],['plusMinusOneAccuracy','相邻等级准确率'],['spearman','等级相关性']] : [['logLoss','概率损失'],['macroF1','宏平均 F1'],['accuracy','准确率']]).map(([value,label])=>({value,label}))}/></Form.Item>
          <Form.Item name="metricThreshold" label="达到正式资格的指标阈值" rules={[{required:true,message:'请填写已确认的质量阈值'}]}><InputNumber precision={6}/></Form.Item>
          <Form.Item name="requireFairComparison" label="替换现有模型前要求同口径比较" valuePropName="checked"><Switch/></Form.Item>
        </div>
        <h4>训练运行</h4><div className="policy-grid">
          <Form.Item name="dataNature" label="数据性质" rules={[{required:true}]}><Select options={[{value:'REAL',label:'真实业务数据'},{value:'SYNTHETIC',label:'合成测试数据（不可正式启用）'}]}/></Form.Item>
          <Form.Item name="candidateAlgorithms" label="候选算法" rules={[{required:true,message:'至少选择一种算法'}]}><Select mode="multiple" options={(selectedTarget?.valueType === 'CONTINUOUS' ? [['GAUSSIAN_PROCESS','高斯过程'],['RANDOM_FOREST','随机森林'],['LIGHTGBM','LightGBM'],['XGBOOST','XGBoost'],['CATBOOST','CatBoost']] : selectedTarget?.valueType === 'ORDINAL' ? [['ORDINAL_CUMULATIVE_LOGIT','序数累计 Logit']] : [['LOGISTIC_REGRESSION','逻辑回归'],['RANDOM_FOREST','随机森林'],['LIGHTGBM','LightGBM'],['XGBOOST','XGBoost'],['CATBOOST','CatBoost']]).map(([value,label])=>({value,label}))}/></Form.Item>
          <Form.Item name="seed" label="随机种子" rules={[{required:true,message:'请填写随机种子'}]}><InputNumber min={1} precision={0}/></Form.Item>
          <Form.Item name="timeoutMinutes" label="单次训练超时（分钟）" rules={[{required:true,message:'请填写超时时间'}]}><InputNumber min={1} precision={0}/></Form.Item>
          <Form.Item name="retrainMinimumNewSamples" label="至少新增多少样本再重训" rules={[{required:true,message:'请填写新增量'}]}><InputNumber min={0} precision={0}/></Form.Item>
          <Form.Item name="minimumIntervalHours" label="两次训练最短间隔（小时）" rules={[{required:true,message:'请填写间隔'}]}><InputNumber min={0} precision={0}/></Form.Item>
          <Form.Item name="autoTrainingEnabled" label="允许自动创建训练任务" valuePropName="checked"><Switch/></Form.Item>
        </div>
        <h4>异常值审查规则（可选）</h4><Form.List name="anomalyRules">{(fields,{add,remove})=><><Space direction="vertical" className="policy-anomaly-list">{fields.map(({key,name})=><Space key={key} align="baseline"><Form.Item name={[name,'fieldCode']} rules={[{required:true,message:'请选择字段'}]}><Select showSearch placeholder="输入字段" options={publishedFields.map((field)=>({value:field.code,label:field.name}))} style={{width:220}}/></Form.Item><Form.Item name={[name,'minimum']}><InputNumber placeholder="最小值"/></Form.Item><Form.Item name={[name,'maximum']}><InputNumber placeholder="最大值"/></Form.Item><Button type="link" danger onClick={()=>remove(name)}>删除</Button></Space>)}</Space><Button onClick={()=>add()}>添加异常规则</Button></>}</Form.List>
      </Form>
    </Modal>
    <Modal width={900} title={`新建预测质量策略${selectedTarget ? ` · ${selectedTarget.name}` : ''}`} open={qualityOpen} onCancel={()=>setQualityOpen(false)} onOk={()=>void saveQualityPolicy()} destroyOnHidden>
      <Alert type="info" showIcon message="可信等级完全由此版本化策略产生" description="98.8%等配方合计只作为事实警告输入；是否影响可信度，由下面的规则明确决定。"/>
      <Form form={qualityForm} layout="vertical" style={{marginTop:16}}>
        <div className="policy-grid"><Form.Item name="defaultTrustLevel" label="没有规则命中时" rules={[{required:true}]}><Select options={[{value:'HIGH',label:'可信度高'},{value:'MEDIUM',label:'可信度中等'},{value:'LOW',label:'可信度较低'}]}/></Form.Item><Form.Item name="defaultExplanation" label="默认说明" rules={[{required:true,message:'请说明默认等级的依据'}]}><Input placeholder="例如：模型与输入均满足已发布的常规质量要求"/></Form.Item></div>
        <h4>按优先级匹配的规则</h4><Form.List name="rules">{(rows,{add,remove})=><><Space direction="vertical" style={{width:'100%'}}>{rows.map(({key,name})=><Card key={key} size="small" title={`规则 ${name+1}`} extra={<Button type="link" danger onClick={()=>remove(name)}>删除</Button>}><div className="policy-grid"><Form.Item name={[name,'code']} label="规则编码" rules={[{required:true}]}><Input/></Form.Item><Form.Item name={[name,'priority']} label="优先级" rules={[{required:true}]}><InputNumber min={0} precision={0}/></Form.Item><Form.Item name={[name,'trustLevel']} label="输出可信等级" rules={[{required:true}]}><Select options={[{value:'HIGH',label:'高'},{value:'MEDIUM',label:'中'},{value:'LOW',label:'低'}]}/></Form.Item><Form.Item name={[name,'explanation']} label="给研发人员的说明" rules={[{required:true}]}><Input/></Form.Item><Form.Item name={[name,'domainStatus']} label="适用域状态"><Select allowClear options={[{value:'IN_DOMAIN',label:'范围内'},{value:'NEAR_BOUNDARY',label:'接近边界'}]}/></Form.Item><Form.Item name={[name,'warningCode']} label="存在警告"><Select allowClear options={[{value:'FORMULA_TOTAL_WARNING',label:'配方合计不是100%'}]}/></Form.Item><Form.Item name={[name,'minimumEvidenceCoverage']} label="最低输入证据覆盖"><InputNumber min={0} max={1} step={0.05}/></Form.Item><Form.Item name={[name,'formulaMinimum']} label="配方合计下限"><InputNumber/></Form.Item><Form.Item name={[name,'formulaMaximum']} label="配方合计上限"><InputNumber/></Form.Item><Form.Item name={[name,'metric']} label="验证指标"><Input placeholder="例如 mae、accuracy"/></Form.Item><Form.Item name={[name,'operator']} label="指标比较"><Select allowClear options={['LT','LTE','GT','GTE','EQ'].map((value)=>({value,label:value}))}/></Form.Item><Form.Item name={[name,'metricValue']} label="指标值"><InputNumber/></Form.Item></div></Card>)}</Space><Button style={{marginTop:10}} type="dashed" block onClick={()=>add({priority:rows.length*10,trustLevel:'MEDIUM'})}>添加质量规则</Button></>}</Form.List>
      </Form>
    </Modal>
    <Modal title="新增材料别名" open={aliasOpen} onCancel={()=>setAliasOpen(false)} onOk={()=>void saveAlias()} destroyOnHidden><Form form={aliasForm} layout="vertical"><Form.Item name="materialId" label="MDM材料" rules={[{required:true}]}><Select showSearch optionFilterProp="label" options={materials.map((m)=>({value:m.id,label:`${m.code} · ${m.name}`}))}/></Form.Item><Form.Item name="alias" label="受控别名" rules={[{required:true}]}><Input/></Form.Item></Form></Modal>
    <Modal title="新建材料字典" open={dictionaryOpen} onCancel={()=>setDictionaryOpen(false)} onOk={()=>void saveDictionary()} width={760} destroyOnHidden><Form form={dictionaryForm} layout="vertical"><Form.Item name="code" label="字典编码" rules={[{required:true}]}><Input/></Form.Item><Form.List name="items" initialValue={[{role:'MATERIAL'}]}>{(rows,{add,remove})=><><div className="modeling-muted">材料顺序就是编码顺序；角色和令牌会随字典版本冻结。</div>{rows.map(({key:rowKey,name})=><Space key={rowKey} align="start" wrap><Form.Item name={[name,'materialId']} label="MDM材料" rules={[{required:true}]}><Select showSearch optionFilterProp="label" style={{width:280}} options={materials.map((m)=>({value:m.id,label:`${m.code} · ${m.name}`}))}/></Form.Item><Form.Item name={[name,'role']} label="角色" rules={[{required:true}]}><Input placeholder="例如 RESIN" style={{width:150}}/></Form.Item><Form.Item name={[name,'token']} label="编码令牌"><Input placeholder="默认使用材料编码" style={{width:180}}/></Form.Item>{rows.length>1&&<Button danger style={{marginTop:30}} onClick={()=>remove(name)}>删除</Button>}</Space>)}<Button type="dashed" onClick={()=>add({role:'MATERIAL'})}>添加材料</Button></>}</Form.List></Form></Modal>
  </div>
}

function split(value?: string) { return value?.split(/[,，]/).map((item)=>item.trim()).filter(Boolean) ?? [] }
function stringValue(value: unknown) { return typeof value === 'string' ? value : '' }
function pretty(value: unknown) { return value == null || (typeof value === 'object' && Object.keys(value).length===0) ? '—' : JSON.stringify(value, null, 2) }
function display(value: unknown) { return value == null || value === '' ? '—' : typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean' ? String(value) : pretty(value) }

