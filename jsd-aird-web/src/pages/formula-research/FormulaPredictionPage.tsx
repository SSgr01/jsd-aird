import { ExperimentOutlined, ReloadOutlined } from '@ant-design/icons'
import { Alert, Button, Collapse, Input, InputNumber, Select, Spin, Table, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { formulaDesignApi, type FormulaContext, type FormulaMaterial, type FormulaRequest, type FormulaTarget, type ResearchRun } from '@/services/ai-rnd/formula-design-api'
import { HttpError } from '@/services/http/errors'
import { useAuthStore } from '@/stores/auth-store'
import { generateUUID } from '@/utils/uuid'
import { targetLabels } from '../performance-prediction/performance-prediction-utils'
import { FormulaGoalEditor } from './FormulaGoalEditor'
import { FormulaCandidateTable } from './FormulaCandidateTable'
import { buildFormulaRequest, businessValue, categoryOptions, contextForRun, defaultGoal, fieldName, formatPredictionNumber, formulaFailure, formulaFingerprint, formulaInputError, formulaMaterialName, formulaRunNotice, formulaSpaceError, formulaUnavailable, goalError, materialSettingError, readFormulaDraft, restoreFormulaForm, syncFormulaForm, type FormulaDraft, type FormulaForm, type InputSetting, type MaterialSetting } from './formula-prediction-utils'
import './formula-prediction.css'

const terminal = (run: ResearchRun) => ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(run.executionStatus)
const updateRunUrl = (id: string) => { const query = new URLSearchParams(window.location.search); query.set('runId', id); window.history.replaceState({}, '', `${window.location.pathname}?${query.toString()}`) }
const clearMissingRunUrl = (id?: string) => { const query = new URLSearchParams(window.location.search); if (id && query.get('runId') === id) { query.delete('runId'); window.history.replaceState({}, '', `${window.location.pathname}${query.size ? `?${query.toString()}` : ''}`) } }
const snapshotContext = (context: FormulaContext): FormulaContext => ({ ...context, targets: context.targets.filter(target => context.selected.some(selected => selected.targetId === target.targetId)) })
export function FormulaPredictionPage() {
  const user = useAuthStore(state => state.user), permission = useAuthStore(state => state.can('ai.formula.predict'))
  const storageKey = user ? `formula-prediction:v3:${user.organizationId}:${user.userId}` : undefined
  return <FormulaPredictionWorkbench key={storageKey ?? 'anonymous'} storageKey={storageKey} permission={permission} />
}
function FormulaPredictionWorkbench({ storageKey, permission }: { storageKey?: string; permission: boolean }) {
  const [draft, setDraft] = useState<FormulaDraft>(() => readFormulaDraft(storageKey))
  const [recordId, setRecordId] = useState(() => {
    const id = new URLSearchParams(window.location.search).get('runId') ?? (!draft.completed && !draft.pending ? sessionStorage.getItem('r08.formulaRunId') : null)
    return id && id !== draft.pending?.id && id !== draft.completed?.run.id ? id : undefined
  })
  const [catalog, setCatalog] = useState<FormulaContext>(), [context, setContext] = useState<FormulaContext>()
  const [catalogLoading, setCatalogLoading] = useState(true), [contextLoading, setContextLoading] = useState(false)
  const [catalogError, setCatalogError] = useState(''), [contextError, setContextError] = useState(''), [runError, setRunError] = useState(''), [storageError, setStorageError] = useState('')
  const [running, setRunning] = useState<ResearchRun>(), [refresh, setRefresh] = useState(0), [touched, setTouched] = useState<Record<string, boolean>>({})
  const [openSections, setOpenSections] = useState<string[]>([])
  const { form, pending, completed } = draft
  const busy = Boolean(pending || recordId), selectionKey = form.targets.join('|')
  const inputContext = pending?.context ?? context
  const patchForm = (patch: Partial<FormulaForm>) => { setRunError(''); setDraft(current => ({ ...current, form: { ...current.form, ...patch } })) }
  const touch = (key: string) => setTouched(current => ({ ...current, [key]: true }))
  useEffect(() => {
    if (!storageKey) return
    try { localStorage.setItem(storageKey, JSON.stringify(draft)); setStorageError('') } catch { setStorageError('输入暂未保存，刷新前请保留当前页面。') }
  }, [draft, storageKey])
  useEffect(() => {
    let active = true
    setCatalogLoading(true); setCatalogError('')
    formulaDesignApi.context().then(value => { if (active) setCatalog(value) }).catch(() => { if (active) setCatalogError('预测指标加载失败，请重试。') }).finally(() => { if (active) setCatalogLoading(false) })
    return () => { active = false }
  }, [refresh])
  useEffect(() => {
    if (!form.targets.length) { setContext(undefined); setContextLoading(false); setContextError(''); return }
    let active = true
    setContext(undefined); setContextLoading(true); setContextError('')
    formulaDesignApi.context(form.targets).then(value => {
      if (!active) return
      setContext(value)
      setDraft(current => ({ ...current, context: value, form: current.pending ? current.form : syncFormulaForm(current.form, value) }))
      const defaults = syncFormulaForm(form, value)
      if (value.materialIntersection.some(material => materialSettingError(defaults.materials[material.materialId]))) setOpenSections(current => [...new Set([...current, 'materials'])])
      if (value.requiredInputs.some(field => formulaInputError(field, defaults.inputs[field.code]))) setOpenSections(current => [...new Set([...current, 'inputs'])])
    }).catch(() => { if (active) setContextError('实验输入加载失败，请刷新模型信息。') }).finally(() => { if (active) setContextLoading(false) })
    return () => { active = false }
  }, [selectionKey, refresh])
  useEffect(() => {
    if (!recordId) return
    let active = true
    setRunError('')
    formulaDesignApi.run(recordId).then(run => {
      if (!active) return
      const restoredContext = contextForRun(run, draft.context)
      if (!restoredContext) { setRunError('方案输入信息未能读取，请保留当前输入后重试。'); return }
      const restoredForm = restoreFormulaForm(run.frozenEvidence ?? {})
      setDraft({ form: restoredForm, context: restoredContext, ...(terminal(run) ? { completed: { run, form: restoredForm, context: restoredContext } } : { pending: { key: `record:${run.id}`, id: run.id, request: run.frozenEvidence as FormulaRequest, context: restoredContext } }) })
    }).catch(error => { if (active) { setRunError('这条方案无法恢复，已保留当前输入，请重新生成。'); if (error instanceof HttpError && [403, 404, 410].includes(error.status ?? 0)) clearMissingRunUrl(recordId) } }).finally(() => { if (active) { setRecordId(undefined); sessionStorage.removeItem('r08.formulaRunId') } })
    return () => { active = false }
  }, [recordId])
  useEffect(() => {
    if (!pending) return
    let active = true, timer: number | undefined
    setRunError('')
    const poll = async () => {
      try {
        if (!pending.id) {
          const accepted = await formulaDesignApi.submit(pending.request, pending.key)
          if (!active) return
          setDraft(current => current.pending?.key === pending.key ? { ...current, pending: { ...pending, id: accepted.runId } } : current)
          updateRunUrl(accepted.runId); return
        }
        const run = await formulaDesignApi.run(pending.id)
        if (!active) return
        setRunError('')
        if (!terminal(run)) { setRunning(run); timer = window.setTimeout(() => { void poll() }, 1000); return }
        const resultContext = contextForRun(run, pending.context) ?? pending.context
        setRunning(undefined)
        setDraft(current => current.pending?.key === pending.key ? { ...current, pending: undefined, completed: { run, form: current.form, context: resultContext } } : current)
      } catch (error) {
        if (!active) return
        const transient = !(error instanceof HttpError) || error.status == null || [408, 500, 502, 503, 504].includes(error.status)
        if (transient) { setRunError('正在重新连接生成进度'); timer = window.setTimeout(() => { void poll() }, 3000); return }
        setRunError(error instanceof HttpError && [403, 404, 410].includes(error.status ?? 0) ? '这条方案无法恢复，请使用已保存的输入重新生成。' : formulaFailure(error instanceof HttpError ? error.code : undefined))
        if (error instanceof HttpError && [403, 404, 410].includes(error.status ?? 0)) clearMissingRunUrl(pending.id)
        setDraft(current => current.pending?.key === pending.key ? { ...current, pending: undefined } : current)
        if (error instanceof HttpError && error.code === 'MODEL_VERSION_CHANGED') setRefresh(value => value + 1)
      }
    }
    void poll()
    return () => { active = false; if (timer) window.clearTimeout(timer) }
  }, [pending?.key, pending?.id])
  const knownTargets = useMemo(() => {
    const targets = new Map<string, FormulaTarget>()
    for (const source of [completed?.context.targets, draft.context?.targets, catalog?.targets]) source?.forEach(target => targets.set(target.targetId, target))
    return [...targets.values()]
  }, [catalog, draft.context, completed])
  const labels = useMemo(() => targetLabels(knownTargets), [knownTargets]), captions = useMemo(() => targetLabels(knownTargets, false), [knownTargets])
  const options = [
    { label: '预测指标', options: knownTargets.filter(target => target.available) },
    { label: '暂不可预测', options: knownTargets.filter(target => !target.available) },
  ].filter(group => group.options.length).map(group => ({ label: group.label, options: group.options.sort((a, b) => a.name.localeCompare(b.name, 'zh-CN')).map(target => ({ value: target.targetId, selectedLabel: labels.get(target.targetId) ?? target.name, label: `${labels.get(target.targetId) ?? target.name}${target.available ? '' : ` · ${formulaUnavailable(target)}`}`, disabled: !target.available && !form.targets.includes(target.targetId) })) }))
  const selected = inputContext?.selected ?? []
  const materialErrors = inputContext?.materialIntersection.filter(material => materialSettingError(form.materials[material.materialId])) ?? []
  const inputErrors = inputContext?.requiredInputs.filter(field => formulaInputError(field, form.inputs[field.code])) ?? []
  const goalErrors = selected.filter(target => goalError(target, form.goals[target.targetId]))
  const spaceError = inputContext && selected.every(target => target.available) ? formulaSpaceError(form, inputContext) : ''
  const unavailable = selected.filter(target => !target.available)
  const ready = Boolean(inputContext && form.targets.length && selected.length === form.targets.length && !contextLoading && !contextError && !unavailable.length && !inputContext.configurationConflicts?.length)
  const missing = goalErrors.length + materialErrors.length + inputErrors.length
  const canGenerate = permission && ready && !missing && !spaceError && !busy
  const comparisonContext = inputContext ?? draft.context
  const changed = Boolean(completed && formulaFingerprint(form, comparisonContext) !== formulaFingerprint(completed.form, completed.context))
  const patchGoal = (id: string, patch: Partial<FormulaForm['goals'][string]>) => patchForm({ goals: { ...form.goals, [id]: { ...form.goals[id]!, ...patch } } })
  const patchMaterial = (id: string, patch: Partial<MaterialSetting>) => patchForm({ materials: { ...form.materials, [id]: { ...form.materials[id]!, ...patch } } })
  const patchInput = (code: string, patch: Partial<InputSetting>) => patchForm({ inputs: { ...form.inputs, [code]: { ...form.inputs[code]!, ...patch } } })
  const start = () => {
    if (!canGenerate || !inputContext) return
    setRunError(''); setRunning(undefined)
    setDraft(current => ({ ...current, pending: { key: generateUUID(), request: buildFormulaRequest(current.form, inputContext), context: snapshotContext(inputContext) } }))
  }
  const goalColumns: ColumnsType<FormulaTarget> = [
    { title: '目标性能', width: '30%', render: (_, target) => <div className="formula-target-name"><strong>{target.name}</strong>{(target.testMethod || target.testStage) && <small>{[businessValue(target.testMethod), businessValue(target.testStage), businessValue(target.category)].filter(Boolean).join(' · ')}</small>}</div> },
    { title: '目标条件', render: (_, target) => <FormulaGoalEditor target={target} goal={form.goals[target.targetId] ?? defaultGoal(target)} caption={captions.get(target.targetId) ?? target.name} disabled={busy} touched={Boolean(touched[`goal:${target.targetId}`])} onBlur={() => touch(`goal:${target.targetId}`)} onChange={patch => patchGoal(target.targetId, patch)} /> },
  ]
  const materialColumns: ColumnsType<FormulaMaterial> = [
    { title: '材料', width: 180, render: (_, material, index) => <div className="formula-target-name"><strong>{formulaMaterialName(material, index)}</strong>{material.supportedRanges?.length ? <small>训练记录 {material.supportedRanges.map(range => `${formatPredictionNumber(range.minimum)}～${formatPredictionNumber(range.maximum)}%`).join(' / ')}</small> : null}</div> },
    { title: '比例设置', width: 130, render: (_, material, index) => <Select aria-label={`${formulaMaterialName(material, index)}比例设置`} value={form.materials[material.materialId]?.mode ?? 'VARIABLE'} disabled={busy} onChange={mode => patchMaterial(material.materialId, { mode })} options={[{ value: 'VARIABLE', label: '待预测' }, { value: 'FIXED', label: '固定' }]} /> },
    ...(['ratio', 'minimum', 'maximum'] as const).map(key => ({ title: ({ ratio: '固定比例（%）', minimum: '最小比例（%）', maximum: '最大比例（%）' })[key], width: 145, render: (_: unknown, material: FormulaMaterial, index: number) => {
      const setting = form.materials[material.materialId], relevant = key === 'ratio' ? setting?.mode === 'FIXED' : setting?.mode !== 'FIXED'
      return relevant ? <InputNumber aria-label={`${formulaMaterialName(material, index)}${({ ratio: '固定比例', minimum: '最小比例', maximum: '最大比例' })[key]}`} value={setting?.[key]} disabled={busy} onBlur={() => touch(`material:${material.materialId}`)} status={touched[`material:${material.materialId}`] && materialSettingError(setting) ? 'error' : undefined} onChange={value => patchMaterial(material.materialId, { [key]: value ?? undefined })} /> : '—'
    } })),
  ]
  const resultStatus = completed ? busy ? '上次结果' : changed ? '需重新生成' : completed.run.executionStatus === 'FAILED' ? '未完成' : completed.run.outcomeStatus === 'BLOCKED' ? '无可行方案' : completed.run.outcomeStatus === 'PARTIAL' ? '部分完成' : '已完成' : ''
  const stageText = ({ QUEUED: '等待生成', CHECKING_CONDITIONS: '检查输入', SEARCHING: '生成配方', RULE_CHECKING: '检查方案' } as Record<string, string>)[running?.currentStage ?? ''] ?? '正在生成配方'
  return <main className="formula-prediction-page">
    <header className="formula-prediction-header"><Typography.Title level={2}>AI配方预测</Typography.Title><Link to="/assistant/model-center">模型中心</Link></header>
    <section className="formula-target-panel" aria-label="目标选择"><label htmlFor="formula-targets">目标性能</label><Select id="formula-targets" aria-label="目标性能" mode="multiple" value={form.targets} options={options} maxTagCount="responsive" showSearch optionFilterProp="label" optionLabelProp="selectedLabel" placeholder="选择要达到的性能，可多选" disabled={busy} loading={catalogLoading} onChange={targets => { patchForm({ targets }); setTouched({}) }} />
      {catalogError && <Alert type="error" showIcon message={catalogError} action={<Button size="small" onClick={() => setRefresh(value => value + 1)}>重试</Button>} />}
      {!catalogLoading && !catalogError && !knownTargets.some(target => target.available) && !form.targets.length && <small className="formula-field-hint">当前没有可用于配方预测的指标，请在模型中心检查对应模型。</small>}
    </section>
    {storageError && <Alert className="formula-page-notice" type="warning" showIcon message={storageError} />}
    {runError && !pending && <Alert className="formula-page-notice" type="error" showIcon message={runError} />}
    {recordId ? <div className="formula-loading"><Spin /><span>正在读取方案</span></div> : !form.targets.length && !completed ? <div className="formula-start"><ExperimentOutlined /><span>选择目标性能后设置目标值</span></div> : <>
      {form.targets.length > 0 && <section className="formula-input-panel" aria-label="配方预测输入"><div className="formula-panel-heading"><h3>目标与条件</h3><Button type="text" size="small" disabled={busy || contextLoading} icon={<ReloadOutlined />} onClick={() => setRefresh(value => value + 1)}>刷新模型信息</Button></div>
        {contextError ? <Alert type="error" showIcon message={contextError} /> : contextLoading && !pending ? <div className="formula-loading"><Spin /><span>正在读取所需输入</span></div> : inputContext && <>
          {unavailable.length > 0 && <Alert className="formula-input-notice" type="warning" showIcon message={unavailable.map(target => `${captions.get(target.targetId) ?? target.name}：${formulaUnavailable(target)}`).join('；')} />}
          {inputContext.configurationConflicts?.length ? <Alert className="formula-input-notice" type="error" showIcon message={inputContext.configurationConflicts.some(item => item.fieldCode === 'FORMULA_MATERIALS') ? '所选模型使用的材料不同，请分别生成配方。' : '所选指标的实验条件类型、单位或可选类别不同，请调整指标组合。'} /> : null}
          {selected.length !== form.targets.length && <Alert className="formula-input-notice" type="warning" showIcon message="部分指标已不可用，请取消后重新选择。" />}
          <Table size="small" rowKey="targetId" columns={goalColumns} dataSource={selected} pagination={false} scroll={{ x: 'max-content' }} />
          <Collapse className="formula-constraints" activeKey={openSections} onChange={keys => setOpenSections(typeof keys === 'string' ? [keys] : keys)} items={[
            ...(inputContext.materialIntersection.length ? [{ key: 'materials', label: `材料比例限制 · ${inputContext.materialIntersection.length} 种材料 · 合计 100%`, children: <>
              <Table size="small" rowKey="materialId" columns={materialColumns} dataSource={inputContext.materialIntersection} pagination={false} scroll={{ x: 'max-content' }} />
              {inputContext.materialIntersection.filter(material => touched[`material:${material.materialId}`] && materialSettingError(form.materials[material.materialId])).map((material, index) => <div role="alert" className="formula-field-error" key={material.materialId}>{formulaMaterialName(material, index)}：{materialSettingError(form.materials[material.materialId])}</div>)}
              {spaceError && <div role="alert" className="formula-field-error">{spaceError}</div>}
            </> }] : []),
            ...(inputContext.requiredInputs.length ? [{ key: 'inputs', label: `实验条件 · ${inputContext.requiredInputs.length} 项${inputErrors.length ? ` · ${inputErrors.length} 项待填写` : ''}`, children: <div className="formula-condition-grid">{inputContext.requiredInputs.map(field => {
              const setting: InputSetting = form.inputs[field.code] ?? { mode: 'FIXED' }, error = formulaInputError(field, setting), invalid = touched[`input:${field.code}`] && error
              const props = { disabled: busy, 'aria-label': fieldName(field), onBlur: () => touch(`input:${field.code}`), status: invalid ? 'error' as const : undefined }
              return <div className="formula-condition-field" key={field.code}><label>{fieldName(field)}{field.unit && <span>（{field.unit}）</span>}{field.required && <em aria-label="必填">*</em>}</label>
                {field.valueType === 'NUMBER' ? <><Select aria-label={`${fieldName(field)}设置方式`} disabled={busy} value={setting.mode} options={[{ value: 'VARIABLE', label: '范围内预测' }, { value: 'FIXED', label: '固定值' }]} onChange={mode => patchInput(field.code, { mode })} />{setting.mode === 'VARIABLE' ? <div className="formula-condition-range">{(['minimum', 'maximum', 'step'] as const).map(key => <label className="formula-small-field" key={key}><span>{({ minimum: '最小值', maximum: '最大值', step: '步长（可选）' })[key]}</span><InputNumber {...props} aria-label={`${fieldName(field)}${({ minimum: '最小值', maximum: '最大值', step: '步长' })[key]}`} value={setting[key]} onChange={value => patchInput(field.code, { [key]: value ?? undefined })} /></label>)}</div> : <InputNumber {...props} value={typeof setting.value === 'number' ? setting.value : undefined} onChange={value => patchInput(field.code, { value: value ?? undefined })} />}</> : field.valueType === 'CATEGORY' ? <Select {...props} value={typeof setting.value === 'string' || typeof setting.value === 'number' ? String(setting.value) : undefined} options={categoryOptions(field)} onChange={value => patchInput(field.code, { value })} placeholder="请选择" /> : field.valueType === 'BOOLEAN' ? <Select {...props} value={typeof setting.value === 'boolean' ? setting.value : undefined} options={[{ value: true, label: '是' }, { value: false, label: '否' }]} onChange={value => patchInput(field.code, { value })} placeholder="请选择" /> : <Input {...props} value={typeof setting.value === 'string' ? setting.value : ''} onChange={event => patchInput(field.code, { value: event.target.value || undefined })} />}
                {invalid && <small role="alert" className="formula-field-error">{error}</small>}
                {field.supportedRanges?.length ? <small className="formula-field-hint">训练记录 {field.supportedRanges.map(range => `${formatPredictionNumber(range.minimum)}～${formatPredictionNumber(range.maximum)}${field.unit ?? ''}`).join(' / ')}</small> : null}
              </div>
            })}</div> }] : []),
            ...(selected.length > 1 ? [{ key: 'weights', label: '多目标权重', children: <div className="formula-weight-grid">{selected.map(target => <label className="formula-small-field" key={target.targetId}><span>{captions.get(target.targetId) ?? target.name}</span><InputNumber aria-label={`${captions.get(target.targetId) ?? target.name}权重`} disabled={busy} value={form.goals[target.targetId]?.weight ?? 1} onBlur={() => touch(`goal:${target.targetId}`)} onChange={weight => patchGoal(target.targetId, { weight: weight ?? 1 })} /></label>)}</div> }] : []),
          ]} />
          {spaceError && !inputContext.materialIntersection.length && <Alert className="formula-input-notice" type="warning" showIcon message={spaceError} />}
          <div className="formula-submit-row"><span>{missing ? `还有 ${missing} 项目标或输入待填写` : spaceError ? '请调整材料比例范围' : !permission ? '当前账号没有配方预测权限' : ''}</span><Button aria-label="生成配方方案" type="primary" icon={<ExperimentOutlined />} loading={Boolean(pending)} disabled={!canGenerate} onClick={start}>生成配方方案</Button></div>
        </>}
      </section>}
      {(completed || pending) && <section className="formula-results-panel" aria-label="配方方案"><div className="formula-panel-heading"><h3>配方方案{completed && !pending ? ` · ${completed.run.actualCandidateCount} 个` : ''}</h3>{completed && <Tag color={changed || completed.run.outcomeStatus === 'PARTIAL' ? 'gold' : completed.run.executionStatus === 'FAILED' ? 'red' : 'default'}>{resultStatus}</Tag>}</div>
        {pending && <div className="formula-loading" role="status"><Spin /><span>{runError || stageText}{running ? ` · ${running.progress}%` : ''}</span></div>}
        {completed && <>{formulaRunNotice(completed.run) && <Alert className="formula-result-notice" type={completed.run.executionStatus === 'FAILED' ? 'error' : 'warning'} showIcon message={formulaRunNotice(completed.run)} />}{completed.run.candidates.length > 0 && <FormulaCandidateTable completed={completed} stale={changed} />}</>}
      </section>}
    </>}
  </main>
}
