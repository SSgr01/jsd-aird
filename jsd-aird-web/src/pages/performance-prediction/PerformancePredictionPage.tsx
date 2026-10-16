import { AimOutlined, DeleteOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { Alert, Button, Checkbox, Empty, Input, InputNumber, Select, Spin, Table, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import type { PredictionContext, PredictionResponse, PredictionRunning, TypedPrediction } from '@/services/ai-rnd/ai-rnd-types'
import { predictionApi } from '@/services/ai-rnd/prediction-api'
import { HttpError } from '@/services/http/errors'
import { useAuthStore } from '@/stores/auth-store'
import { generateUUID } from '@/utils/uuid'
import { buildPredictionRequest, businessValue, categoryOptions, fieldName, formatPredictionNumber, formulaError, inputError, materialName, predictionFailure, predictionFingerprint, predictionNotes, readPredictionDraft, targetAvailable, targetLabels, unavailableReason, type FormulaRow, type PredictionDraft, type PredictionForm } from './performance-prediction-utils'
import './performance-prediction.css'

const inputGroups = { FORMULA: '配方相关条件', PROCESS: '工艺条件', CONDITION: '环境与基材', OTHER: '实验条件' }
function isRunning(value: PredictionResponse | PredictionRunning): value is PredictionRunning { return value.executionStatus === 'RUNNING' }
function requestError(error: unknown, fallback: string) { return error instanceof HttpError ? predictionFailure(error.code) : fallback }
function PredictionValue({ value }: { value: TypedPrediction }) {
  if (value.resultType === 'CONTINUOUS') return <strong className="performance-result-number">{formatPredictionNumber(value.value)}</strong>
  const probability = value.resultType === 'BINARY' ? `${value.positiveClass} 概率 ${(value.probability * 100).toFixed(1)}%` : value.resultType === 'ORDINAL' && value.thresholdProbability != null ? `达到该等级或更高 ${(value.thresholdProbability * 100).toFixed(1)}%` : ''
  const probabilities = value.resultType === 'BINARY' ? {} : value.probabilities
  return <div className="performance-class-result"><strong>{value.label}</strong>{probability && <small>{probability}</small>}{Object.entries(probabilities).map(([label, chance]) => <small key={label}>{label} {(chance * 100).toFixed(1)}%</small>)}</div>
}
function intervalText(value: TypedPrediction, extrapolated: boolean) {
  return value.resultType === 'CONTINUOUS' && value.interval && !extrapolated ? `${formatPredictionNumber(value.interval.lower)}～${formatPredictionNumber(value.interval.upper)}（${(value.interval.coverageLevel * 100).toFixed(0)}%）` : '—'
}
export function PerformancePredictionPage() {
  const user = useAuthStore(state => state.user)
  const storageKey = user ? `performance-prediction:v2:${user.organizationId}:${user.userId}` : undefined
  return <PerformancePredictionWorkbench key={storageKey ?? 'anonymous'} storageKey={storageKey} />
}
function PerformancePredictionWorkbench({ storageKey }: { storageKey?: string }) {
  const [searchParams, setSearchParams] = useSearchParams()
  const requestedTarget = searchParams.get('targetId')
  const [draft, setDraft] = useState<PredictionDraft>(() => {
    const saved = readPredictionDraft(storageKey)
    return requestedTarget && !saved.pending ? { ...saved, form: { ...saved.form, targets: [requestedTarget] } } : saved
  })
  useEffect(() => {
    if (!requestedTarget || draft.pending) return
    setDraft(current => ({ ...current, form: { ...current.form, targets: [requestedTarget] } }))
    const next = new URLSearchParams(searchParams); next.delete('targetId'); setSearchParams(next, { replace: true })
  }, [requestedTarget, Boolean(draft.pending), searchParams, setSearchParams])
  const [catalog, setCatalog] = useState<PredictionContext>()
  const [context, setContext] = useState<PredictionContext>()
  const [catalogLoading, setCatalogLoading] = useState(true)
  const [contextLoading, setContextLoading] = useState(false)
  const [catalogError, setCatalogError] = useState('')
  const [contextError, setContextError] = useState('')
  const [runError, setRunError] = useState('')
  const [storageError, setStorageError] = useState('')
  const [refresh, setRefresh] = useState(0)
  const [touched, setTouched] = useState<Record<string, boolean>>({})
  const { form, pending, completed } = draft
  const busy = Boolean(pending)
  const patchForm = (patch: Partial<PredictionForm>) => setDraft(current => ({ ...current, form: { ...current.form, ...patch } }))
  useEffect(() => {
    if (!storageKey) return
    try { localStorage.setItem(storageKey, JSON.stringify(draft)); setStorageError('') }
    catch { setStorageError('输入暂未保存，刷新前请保留当前页面。') }
  }, [draft, storageKey])
  useEffect(() => {
    let active = true
    setCatalogLoading(true); setCatalogError('')
    predictionApi.context().then(value => { if (active) setCatalog(value) }).catch(() => { if (active) setCatalogError('预测指标加载失败，请重试。') }).finally(() => { if (active) setCatalogLoading(false) })
    return () => { active = false }
  }, [refresh])
  const selectionKey = form.targets.join('|')
  useEffect(() => {
    if (!form.targets.length) { setContext(undefined); setContextLoading(false); setContextError(''); return }
    let active = true
    setContext(undefined); setContextLoading(true); setContextError('')
    predictionApi.context(form.targets).then(value => {
      if (!active) return
      setContext(value)
      if (value.selected.some(target => target.formulaRequirement === 'REQUIRED')) {
        setDraft(current => {
          const known = new Map(current.form.formula.filter(row => row.materialId).map(row => [row.materialId, row]))
          const formula = [...current.form.formula.filter(row => !row.materialId), ...value.materials.filter(material => known.has(material.materialId) || !current.form.materialIds?.includes(material.materialId)).map(material => known.get(material.materialId) ?? { key: generateUUID(), materialId: material.materialId, unit: '%' })]
          const changed = formula.map(row => row.materialId).join('|') !== current.form.formula.map(row => row.materialId).join('|')
          return { ...current, form: { ...current.form, formula, materialIds: value.materials.map(material => material.materialId), complete: changed ? false : current.form.complete } }
        })
      }
    }).catch(() => { if (active) setContextError('实验输入加载失败，请刷新模型信息。') }).finally(() => { if (active) setContextLoading(false) })
    return () => { active = false }
  }, [selectionKey, refresh])
  useEffect(() => {
    if (!pending) return
    let active = true
    let timer: number | undefined
    setRunError('')
    const poll = async () => {
      try {
        const response = pending.id ? await predictionApi.record(pending.id) : await predictionApi.predict(pending.submission.request, pending.key)
        if (!active) return
        setRunError('')
        if (isRunning(response)) {
          if (!pending.id) setDraft(current => current.pending?.key === pending.key ? { ...current, pending: { ...pending, id: response.predictionRecordId } } : current)
          else timer = window.setTimeout(() => { void poll() }, Math.max(response.pollAfterMs, 500))
          return
        }
        setDraft(current => current.pending?.key === pending.key ? { ...current, pending: undefined, completed: { response, submission: pending.submission } } : current)
      } catch (error) {
        if (!active) return
        const uncertainSubmission = !pending.id && (!(error instanceof HttpError) || error.status == null || [408, 500, 502, 504].includes(error.status))
        if (uncertainSubmission || pending.id && (!(error instanceof HttpError) || ![403, 404, 410].includes(error.status ?? 0))) {
          setRunError(pending.id ? '预测进度暂未读到，正在重新连接。' : '预测请求暂未确认，正在重新连接。'); timer = window.setTimeout(() => { void poll() }, 3000); return
        }
        setRunError(error instanceof HttpError && [403, 404, 410].includes(error.status ?? 0) ? '这条预测记录无法恢复，请使用已保存的输入重新预测。' : requestError(error, '预测提交失败，请稍后重试。'))
        setDraft(current => current.pending?.key === pending.key ? { ...current, pending: undefined } : current)
        if (error instanceof HttpError && error.code === 'MODEL_VERSION_CHANGED') setRefresh(value => value + 1)
      }
    }
    void poll()
    return () => { active = false; if (timer) window.clearTimeout(timer) }
  }, [pending?.key, pending?.id])
  const labels = useMemo(() => targetLabels(catalog?.targets ?? []), [catalog])
  const orderedTargets = useMemo(() => [...(catalog?.targets ?? [])].sort((a, b) => Number(targetAvailable(b)) - Number(targetAvailable(a)) || a.name.localeCompare(b.name, 'zh-CN')), [catalog])
  const availableCount = orderedTargets.filter(targetAvailable).length
  const targetOptions = [
    { label: '预测指标', options: orderedTargets.filter(targetAvailable) },
    { label: '暂不可预测', options: orderedTargets.filter(target => !targetAvailable(target)) },
  ].filter(group => group.options.length).map(group => ({ label: group.label, options: group.options.map(target => ({ value: target.targetId, selectedLabel: target.modelSource ? `${target.name}${target.unit ? `（${target.unit}）` : ''}` : labels.get(target.targetId) ?? target.name, label: `${labels.get(target.targetId) ?? target.name}${targetAvailable(target) ? '' : ` · ${unavailableReason(target)}`}`, disabled: !targetAvailable(target) && !form.targets.includes(target.targetId) })) }))
  const formulaRequired = Boolean(context?.selected.some(target => target.formulaRequirement === 'REQUIRED'))
  const total = form.formula.reduce((sum, row) => sum + (row.ratio ?? 0), 0)
  const converted = formulaRequired && total > 0 && Math.abs(total - 100) > 0.000001
  const inputErrors = (context?.requiredInputs ?? []).filter(field => inputError(field, form.inputs[field.code]))
  const formulaErrors = formulaRequired ? form.formula.filter(row => formulaError(row, form.formula)) : []
  const selectionReady = Boolean(context && form.targets.length && !contextLoading && !contextError && form.targets.every(id => context.selected.some(target => target.targetId === id && target.available)) && !context.configurationConflicts?.length)
  const canPredict = selectionReady && !inputErrors.length && (!formulaRequired || Boolean(form.formula.length && !formulaErrors.length && form.complete && total > 0))
  const requestContext = context ?? completed?.submission.context
  const currentRequest = requestContext ? buildPredictionRequest(form, requestContext) : undefined
  const changedResult = Boolean(completed && currentRequest && predictionFingerprint(currentRequest) !== predictionFingerprint(completed.submission.request))
  const fixedConditions = (context?.selected ?? []).flatMap(target => { const values = Object.values(target.fixedConditions ?? {}).map(businessValue).filter(Boolean); return values.length ? [`${target.name}：${[...new Set(values)].join(' · ')}`] : [] })
  const startPrediction = () => {
    if (!canPredict || !context || !currentRequest || busy) return
    setRunError('')
    const submission = { request: currentRequest, context: { ...context, targets: context.targets.filter(target => form.targets.includes(target.targetId)) } }
    setDraft(current => ({ ...current, pending: { key: generateUUID(), submission } }))
  }
  const updateFormula = (key: string, patch: Partial<FormulaRow>) => patchForm({ formula: form.formula.map(row => row.key === key ? { ...row, ...patch } : row), complete: patch.materialId !== undefined ? false : form.complete })
  const touch = (code: string) => setTouched(current => ({ ...current, [code]: true }))
  const formulaColumns: ColumnsType<FormulaRow> = [
    { title: '材料', width: '48%', render: (_, row, index) => <Select aria-label={`材料 ${index + 1}`} disabled={busy} showSearch optionFilterProp="label" value={row.materialId} onChange={(materialId: string) => updateFormula(row.key, { materialId })} placeholder="选择材料" className="performance-material-select" options={(context?.materials ?? []).filter(material => material.materialId === row.materialId || !form.formula.some(item => item.materialId === material.materialId)).map(material => ({ value: material.materialId, label: materialName(material) }))} /> },
    { title: '录入比例（%）', render: (_, row) => <div className="performance-formula-value"><InputNumber aria-label={`${materialName(context?.materials.find(material => material.materialId === row.materialId))}比例`} disabled={busy} value={row.ratio} precision={6} onBlur={() => touch(row.key)} onChange={ratio => updateFormula(row.key, { ratio: ratio ?? undefined })} placeholder="未使用填 0" status={touched[row.key] && formulaError(row, form.formula) ? 'error' : undefined} />{touched[row.key] && formulaError(row, form.formula) && <small className="performance-field-error" role="alert">{formulaError(row, form.formula)}</small>}</div> },
    ...(converted ? [{ title: '换算比例（%）', render: (_: unknown, row: FormulaRow) => row.ratio == null ? '—' : formatPredictionNumber(row.ratio / total * 100) }] : []),
    { title: '', width: 44, render: (_, row) => <Button type="text" disabled={busy} icon={<DeleteOutlined />} aria-label={`删除${materialName(context?.materials.find(material => material.materialId === row.materialId))}`} onClick={() => patchForm({ formula: form.formula.filter(item => item.key !== row.key), complete: false })} /> },
  ]
  const resultContext = completed?.submission.context
  const resultLabels = useMemo(() => targetLabels(resultContext?.targets ?? [], false), [resultContext])
  const hasInterval = completed?.response.results.some(item => item.status === 'SUCCEEDED' && intervalText(item.prediction, item.applicability.status === 'OUT_OF_DOMAIN') !== '—')
  const hasNotes = resultContext && completed?.response.results.some(item => predictionNotes(item, resultContext).length > 0)
  const resultColumns: ColumnsType<PredictionResponse['results'][number]> = [
    { title: '指标', width: '24%', render: (_, item) => <strong>{resultLabels.get(item.targetId) ?? resultContext?.selected.find(target => target.targetId === item.targetId)?.name ?? '预测指标'}</strong> },
    { title: '预测结果', render: (_, item) => item.status === 'SUCCEEDED' ? <PredictionValue value={item.prediction} /> : '—' },
    { title: '单位', width: 65, render: (_, item) => item.status === 'SUCCEEDED' && item.prediction.resultType === 'CONTINUOUS' ? item.prediction.unit || '—' : '—' },
    ...hasInterval ? [{ title: '预测区间', render: (_: unknown, item: PredictionResponse['results'][number]) => item.status === 'SUCCEEDED' ? intervalText(item.prediction, item.applicability.status === 'OUT_OF_DOMAIN') : '—' }] : [],
    ...hasNotes ? [{ title: '说明', width: '27%', render: (_: unknown, item: PredictionResponse['results'][number]) => <div className={item.status === 'SUCCEEDED' ? 'performance-result-note' : 'performance-field-error'}>{resultContext ? predictionNotes(item, resultContext).map(note => <div key={note}>{note}</div>) : '—'}</div> }] : [],
  ]
  const single = completed?.response.results.length === 1 ? completed.response.results[0] : undefined
  const succeededCount = completed?.response.results.filter(item => item.status === 'SUCCEEDED').length ?? 0
  const resultStatus = busy ? '上次结果' : changedResult ? '输入已修改，需重新预测' : !succeededCount ? '未完成' : succeededCount < (completed?.response.results.length ?? 0) ? '部分完成' : '已完成'
  return <main className="performance-page">
    <header className="performance-header"><div><Typography.Title level={2}>性能预测</Typography.Title><Typography.Text type="secondary">选择指标并填写预测输入。</Typography.Text></div><Link to="/assistant/model-center">模型中心</Link></header>
    <section className="performance-target-panel" aria-label="预测指标选择"><label htmlFor="performance-targets">预测指标</label><Select id="performance-targets" aria-label="预测指标" mode="multiple" value={form.targets} disabled={busy} loading={catalogLoading} options={targetOptions} maxTagCount="responsive" optionFilterProp="label" optionLabelProp="selectedLabel" onChange={(targets: string[]) => { patchForm({ targets, complete: false }); setTouched({}) }} placeholder="选择要预测的性能，可多选" />
      {!catalogLoading && !catalogError && !availableCount && !form.targets.length && <div className="performance-catalog-note">当前没有可预测的指标。请在模型中心检查对应模型的状态和使用设置。</div>}
      {catalogError && <Alert type="error" showIcon message={catalogError} action={<Button size="small" onClick={() => setRefresh(value => value + 1)}>重试</Button>} />}
    </section>
    {storageError && <Alert className="performance-page-notice" type="warning" showIcon message={storageError} />}{runError && <Alert className="performance-page-notice" type={pending ? 'info' : 'error'} showIcon message={runError} />}
    {!form.targets.length && !completed ? <section className="performance-start"><AimOutlined /><strong>选择预测指标后填写实验输入</strong></section> : <div className={`performance-workspace${form.targets.length > 1 || (completed?.response.results.length ?? 0) > 1 ? ' performance-workspace-multiple' : ''}`}>
      <section className="performance-input-panel" aria-label="实验输入"><div className="performance-panel-heading"><h3>实验输入</h3><Button type="text" icon={<ReloadOutlined />} disabled={busy || contextLoading} onClick={() => setRefresh(value => value + 1)}>刷新模型信息</Button></div>
        {contextError && <Alert type="error" showIcon message={contextError} />}
        {contextError ? null : contextLoading ? <div className="performance-loading"><Spin /><span>正在读取所需输入</span></div> : !context || !form.targets.length ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="先选择预测指标" /> : <>
          {context.configurationConflicts?.length ? <Alert type="error" showIcon message="所选指标的输入类型或单位不同，请调整指标组合。" /> : null}
          {context.selected.some(target => !target.available) && <Alert className="performance-page-notice" type="warning" showIcon message={context.selected.filter(target => !target.available).map(target => `${target.name} · ${unavailableReason(target)}`).join('；')} />}
          {form.targets.some(id => !context.selected.some(target => target.targetId === id)) && <Alert type="warning" showIcon message="所选指标已不可用，请重新选择预测指标。" />}
          {formulaRequired && <div className="performance-formula-section"><div className="performance-section-heading"><h4>配方组成</h4><span>合计 {formatPredictionNumber(total)}%</span></div><Table size="small" rowKey="key" pagination={false} columns={formulaColumns} dataSource={form.formula} scroll={{ x: 'max-content' }} />
            <div className="performance-formula-footer">{context.materials.some(material => !form.formula.some(row => row.materialId === material.materialId)) && <Button type="text" icon={<PlusOutlined />} disabled={busy} onClick={() => patchForm({ formula: [...form.formula, { key: generateUUID(), unit: '%' }], complete: false })}>添加材料</Button>}<Checkbox disabled={busy} checked={form.complete} onChange={event => patchForm({ complete: event.target.checked })}>配方已完整</Checkbox></div>
            {converted && <div className="performance-field-hint">预测按 100% 换算，录入比例保留。</div>}{!formulaErrors.length && total <= 0 && <div className="performance-field-error">配方合计必须大于 0。</div>}
          </div>}
          {Object.entries(inputGroups).map(([group, title]) => { const fields = context.requiredInputs.filter(field => (field.inputGroup ?? 'OTHER') === group); return fields.length ? <fieldset className="performance-condition-group" key={group}><legend>{title}</legend><div className="performance-condition-grid">{fields.map(field => {
            const name = fieldName(field), value = form.inputs[field.code], error = inputError(field, value), invalid = touched[field.code] && error
            const setValue = (next: unknown) => patchForm({ inputs: { ...form.inputs, [field.code]: next } })
            const ranges = [...new Map((field.supportedRanges ?? []).map(range => [`${range.minimum}:${range.maximum}`, range])).values()]
            const outside = typeof value === 'number' && ranges.some(range => value < range.minimum || value > range.maximum)
            const inputProps = { id: `performance-input-${field.code}`, 'aria-label': name, disabled: busy, onBlur: () => touch(field.code), status: invalid ? 'error' as const : undefined }
            return <div className="performance-condition-field" key={field.code}><label htmlFor={inputProps.id}>{name}{field.unit && <span>（{field.unit}）</span>}{field.required && <em aria-label="必填">*</em>}</label>
              {field.valueType === 'NUMBER' ? <InputNumber {...inputProps} value={value as number | undefined} onChange={setValue} /> : field.valueType === 'BOOLEAN' ? <Select {...inputProps} value={value as boolean | undefined} onChange={setValue} options={[{ value: true, label: '是' }, { value: false, label: '否' }]} placeholder="请选择" /> : field.valueType === 'CATEGORY' ? <Select {...inputProps} allowClear value={typeof value === 'string' || typeof value === 'number' ? String(value) : undefined} onChange={setValue} options={categoryOptions(field)} placeholder="请选择" /> : <Input {...inputProps} value={value as string | undefined} onChange={event => setValue(event.target.value || undefined)} />}
              {invalid && <small className="performance-field-error" role="alert">{error}</small>}
              {ranges.length > 0 && <small className={`performance-field-hint${outside ? ' performance-range-notice' : ''}`}>历史实验范围：{ranges.map(range => `${formatPredictionNumber(range.minimum)}～${formatPredictionNumber(range.maximum)}${field.unit ?? ''}`).join(' / ')}{outside ? ' · 超出历史范围，结果需要实验验证' : ''}</small>}
              {context.selected.length > 1 && field.requiredByTargets?.length && field.requiredByTargets.length < context.selected.length ? <small className="performance-field-hint">用于 {field.requiredByTargets.map(target => target.targetName).join('、')}</small> : null}
            </div>
          })}</div></fieldset> : null })}
          {fixedConditions.length > 0 && <div className="performance-fixed-conditions"><strong>固定测试条件</strong>{fixedConditions.map(value => <span key={value}>{value}</span>)}</div>}
          <div className="performance-submit-row"><span>{inputErrors.length + formulaErrors.length > 0 ? `还有 ${inputErrors.length + formulaErrors.length} 项输入待填写` : formulaRequired && !form.complete ? '请确认配方已完整' : ''}</span><Button aria-label="开始预测" type="primary" icon={<AimOutlined />} loading={busy} disabled={!canPredict || busy} onClick={startPrediction}>开始预测</Button></div>
        </>}
      </section>
      <section className="performance-results-panel" aria-label="预测结果"><div className="performance-panel-heading"><h3>预测结果</h3>{completed && <Tag color={changedResult || resultStatus === '部分完成' ? 'gold' : resultStatus === '未完成' ? 'red' : 'default'}>{resultStatus}</Tag>}</div>
        {busy && <div className="performance-loading" role="status"><Spin /><span>正在预测</span></div>}{!completed && !busy && <div className="performance-result-placeholder">完成输入后，结果将在这里展示。</div>}
        {completed && resultContext && <>
          {!completed.response.results.length ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="本次未返回预测结果，请重新预测。" /> : single ? <div className={`performance-single-result${single.status !== 'SUCCEEDED' ? ' performance-single-error' : ''}`}><h4>{resultLabels.get(single.targetId) ?? resultContext.selected.find(target => target.targetId === single.targetId)?.name ?? '预测指标'}</h4>
            {single.status === 'SUCCEEDED' ? <><div className="performance-single-value"><PredictionValue value={single.prediction} />{single.prediction.resultType === 'CONTINUOUS' && <span>{single.prediction.unit}</span>}</div>{intervalText(single.prediction, single.applicability.status === 'OUT_OF_DOMAIN') !== '—' && <div className="performance-field-hint">预测区间 {intervalText(single.prediction, single.applicability.status === 'OUT_OF_DOMAIN')}</div>}</> : <strong>未得到预测值</strong>}
            {predictionNotes(single, resultContext).map(note => <div className={single.status === 'SUCCEEDED' ? 'performance-result-note' : 'performance-field-error'} key={note}>{note}</div>)}
          </div> : <Table size="middle" rowKey="targetId" pagination={false} columns={resultColumns} dataSource={completed.response.results} scroll={{ x: 650 }} />}
        </>}
      </section>
    </div>}
  </main>
}
