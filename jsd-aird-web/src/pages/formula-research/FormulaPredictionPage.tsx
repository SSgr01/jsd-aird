/* The V2 API deliberately carries typed JSON result unions.  The page keeps
 * the rendering adapter permissive so new result types remain displayable
 * without weakening the API/client boundary. */
/* eslint-disable @typescript-eslint/no-explicit-any, @typescript-eslint/no-unsafe-assignment, @typescript-eslint/no-unsafe-member-access, @typescript-eslint/no-unsafe-call, @typescript-eslint/no-misused-promises */
import { CheckCircleOutlined, ExperimentOutlined, LoadingOutlined, SearchOutlined, WarningOutlined } from '@ant-design/icons'
import { Alert, App, Button, Card, Col, Divider, Empty, Input, InputNumber, Layout, List, Progress, Row, Select, Space, Statistic, Tag, Typography } from 'antd'
import { useEffect, useMemo, useRef, useState } from 'react'
import { formulaDesignApi, type FormulaContext, type FormulaTarget, type ResearchRun } from '@/services/ai-rnd/formula-design-api'
import { generateUUID } from '@/utils/uuid'
import { useAuthStore } from '@/stores/auth-store'
import './formula-prediction.css'

const { Title, Text } = Typography
const typeLabel: Record<string, string> = { CONTINUOUS: '连续值', ORDINAL: '序数等级', BINARY: '二分类', CATEGORICAL: '多分类' }
const knownMaterialLabels: Record<string, string> = {
  '05060000-0000-0000-0000-000000000031': '树脂（RESIN）',
  '05060000-0000-0000-0000-000000000032': '助剂（ADDITIVE）',
}
const stageLabel: Record<string, string> = { QUEUED: '等待执行', CHECKING_CONDITIONS: '检查条件', SEARCHING: '搜索方案', RULE_CHECKING: '规则检查', SUCCEEDED: '已完成', FAILED: '执行失败' }
const operatorLabel = (operator: string, valueType: string) => {
  if ((valueType === 'BINARY' || valueType === 'CATEGORICAL') && operator === 'MATCH') return '指定类别'
  if (valueType === 'ORDINAL' && operator === 'MATCH') return '指定等级'
  return ({ AT_LEAST: '至少达到', AT_MOST: '不超过', RANGE: '区间内', MATCH: '接近', MAXIMIZE: '尽量提高', MINIMIZE: '尽量降低' } as Record<string, string>)[operator] ?? operator
}

function displayTargetResult(targetResult: any) {
  const result = targetResult?.result ?? targetResult
  if (!result) return '—'
  if (result.resultType === 'CONTINUOUS' && result.value !== undefined) return `${Number(result.value).toFixed(2)}${result.unit ? ` ${result.unit}` : ''}`
  if (result.label) return result.probability == null ? result.label : `${result.label} · ${(Number(result.probability) * 100).toFixed(0)}%`
  if (result.predictedClass) return result.predictedClass
  if (result.class) return result.class
  return '已通过'
}

function goalValue(target: FormulaTarget, operator?: string) {
  if (target.valueType !== 'CONTINUOUS') return { label: undefined }
  if (operator === 'RANGE') return [undefined, undefined]
  if (operator === 'MATCH') return { target: undefined, tolerance: undefined }
  return undefined
}

function continuousGoalComplete(operator: string | undefined, value: unknown) {
  if (operator === 'MAXIMIZE' || operator === 'MINIMIZE') return true
  if (operator === 'RANGE') {
    return Array.isArray(value) && value.length === 2 && value[0] != null && value[1] != null && Number(value[0]) <= Number(value[1])
  }
  if (operator === 'MATCH') {
    const match = value as { target?: number; tolerance?: number } | undefined
    return match?.target != null && match?.tolerance != null && Number(match.tolerance) >= 0
  }
  return value != null && Number.isFinite(Number(value))
}

export function FormulaPredictionPage() {
  const { message } = App.useApp()
  const canPredict = useAuthStore((state) => state.can('ai.formula.predict'))
  const [context, setContext] = useState<FormulaContext>()
  const [selectedIds, setSelectedIds] = useState<string[]>([])
  const [goals, setGoals] = useState<Record<string, { operator: string; value: any; mandatory: boolean; minimumProbability?: number }>>({})
  const [targetTotal, setTargetTotal] = useState(100)
  const [materialRatios, setMaterialRatios] = useState<Record<string, number>>({})
  const [materialModes, setMaterialModes] = useState<Record<string, 'FIXED'|'VARIABLE'>>({})
  const [materialBounds, setMaterialBounds] = useState<Record<string, { minimum?: number; maximum?: number }>>({})
  const [inputs, setInputs] = useState<Record<string, any>>({})
  const [inputRanges, setInputRanges] = useState<Record<string, { minimum?: number; maximum?: number; step?: number }>>({})
  const [run, setRun] = useState<ResearchRun>()
  const [submitting, setSubmitting] = useState(false)
  const [runId, setRunId] = useState<string>()
  const selectedIdsRef = useRef<string[]>([])
  const clearRun = () => {
    setRun(undefined)
    setRunId(undefined)
    window.sessionStorage?.removeItem('r08.formulaRunId')
    const query = new URLSearchParams(window.location.search)
    if (query.has('runId')) {
      query.delete('runId')
      const next = query.toString()
      window.history.replaceState({}, '', `${window.location.pathname}${next ? `?${next}` : ''}`)
    }
  }

  useEffect(() => {
    formulaDesignApi.context().then((initial) => {
      if (selectedIdsRef.current.length) return
      setContext(initial)
      const query = new URLSearchParams(window.location.search)
      const freshDemo = query.get('fresh') === '1' || query.get('fresh') === 'demo'
      if (freshDemo) window.sessionStorage?.removeItem('r08.formulaRunId')
      const remembered = freshDemo ? null : query.get('runId') ?? window.sessionStorage?.getItem('r08.formulaRunId') ?? null
      if (remembered) setRunId(remembered)
    }).catch(() => message.error('无法读取配方预测目录'))
  }, [message])
  useEffect(() => {
    if (!runId) return
    let active = true
    const poll = async () => { try { const current = await formulaDesignApi.run(runId); if (!active) return; setRun(current); if (!['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(current.executionStatus)) window.setTimeout(poll, 1000) } catch { if (active) window.setTimeout(poll, 1500) } }
    void poll(); return () => { active = false }
  }, [runId])
  // A refreshed page must restore the frozen target selection from the run;
  // the run is the source of truth after submission, while the context only
  // supplies the editable catalog and current display metadata.
  useEffect(() => {
    if (!run || selectedIdsRef.current.length) return
    const frozen = run.frozenEvidence?.typedGoals
    if (!Array.isArray(frozen)) return
    const ids = frozen.map((goal: any) => String(goal?.targetId ?? '')).filter(Boolean)
    if (!ids.length) return
    selectedIdsRef.current = ids
    setSelectedIds(ids)
    setGoals((current) => Object.fromEntries(ids.map((id: string) => {
      const goal = frozen.find((item: any) => String(item?.targetId) === id)
      return [id, current[id] ?? { operator: goal?.operator ?? 'AT_LEAST', value: goal?.value, mandatory: goal?.mandatory ?? true, minimumProbability: goal?.minimumProbability }]
    })))
    formulaDesignApi.context(ids).then((detail) => setContext((current) => current ? { ...current, ...detail } : detail)).catch(() => undefined)
  }, [run])
  const selectedTargets = useMemo(() => {
    const details = Array.isArray(context?.selected) ? context.selected : []
    const catalog = Array.isArray(context?.targets) ? context.targets : []
    return selectedIds.map((id) => details.find((target) => target.targetId === id) ?? catalog.find((target) => target.targetId === id)).filter((target): target is FormulaTarget => Boolean(target))
  }, [context, selectedIds])
  const materials = Array.isArray(context?.materialIntersection) ? context.materialIntersection : []
  const materialLabel = (material: any, index: number) => {
    const raw = `${material?.name ?? ''} ${material?.code ?? ''}`
    if (/^(R05|R06|R07|R10|SYNTHETIC|TEST|STRUCT:)/i.test(raw.trim())) {
      const role = `${material?.role ?? ''}`.toLowerCase()
      if (role.includes('resin')) return '树脂'
      if (role.includes('additive')) return '助剂'
      if (role.includes('cross')) return '交联剂'
      if (role.includes('dilut')) return '稀释剂'
      return `配方材料${index + 1}`
    }
    return material?.name || knownMaterialLabels[material?.materialId] || material?.code || `材料 ${index + 1}`
  }
  const ratioSum = Object.values(materialRatios).reduce((sum, value) => sum + (Number(value) || 0), 0)
  const availableTargets = Array.isArray(context?.targets) ? context.targets : []
  useEffect(() => {
    if (!materials.length) return
    setMaterialRatios((current) => Object.keys(current).length ? current : Object.fromEntries(materials.slice(0, 3).map((material) => [material.materialId, targetTotal / Math.min(3, materials.length)])))
    setMaterialModes((current) => Object.keys(current).length ? current : Object.fromEntries(materials.map((material) => [material.materialId, 'VARIABLE'])))
    setMaterialBounds((current) => Object.keys(current).length ? current : Object.fromEntries(materials.map((material) => [material.materialId, { minimum: 0, maximum: targetTotal }])))
    setInputs((current) => Object.keys(current).length ? current : Object.fromEntries((context?.requiredInputs ?? []).map((field) => [field.code, field.valueType === 'NUMBER' ? 25 : ''])))
  }, [materials, context?.requiredInputs, targetTotal])
  const selectTargets = (ids: string[]) => {
    clearRun()
    selectedIdsRef.current = ids
    setSelectedIds(ids)
    setGoals((current) => Object.fromEntries(ids.map((id) => {
      const target = availableTargets.find((item) => item.targetId === id)
      const operator=target?.operators?.[0] ?? 'AT_LEAST'
      return [id, current[id] ?? { operator, value: goalValue(target ?? ({ valueType: 'CONTINUOUS' } as FormulaTarget),operator), mandatory: !['MAXIMIZE','MINIMIZE'].includes(operator), minimumProbability: undefined }]
    })))
    if (ids.length) formulaDesignApi.context(ids).then((detail) => {
      setContext((current) => current ? { ...current, ...detail } : detail)
      const selected = Array.isArray(detail.selected) ? detail.selected : []
      setGoals((current) => Object.fromEntries(ids.map((id) => { const target = selected.find((item) => item.targetId === id) ?? availableTargets.find((item) => item.targetId === id); const previous = current[id]; const operators = target?.operators ?? []; const operator=previous?.operator && operators.includes(previous.operator) ? previous.operator : operators[0] ?? 'AT_LEAST'; return [id, { operator, value: previous?.value ?? goalValue(target ?? ({ valueType: 'CONTINUOUS' } as FormulaTarget),operator), mandatory: !['MAXIMIZE','MINIMIZE'].includes(operator), minimumProbability: previous?.minimumProbability }] })))
    }).catch(() => message.error('无法读取目标的模型条件'))
  }
  const runDesign = async () => {
    if (!canPredict || !selectedTargets.length) { message.warning('请先选择至少一个可用研发目标'); return }
    if (!materials.length) { message.error('所选目标没有共同的材料空间'); return }
    if (Math.abs(ratioSum - targetTotal) > 0.00001) { message.warning(`材料比例合计为 ${ratioSum.toFixed(2)}%，需要等于 ${targetTotal}%`); return }
    if (selectedTargets.some((target) => !target.available)) { message.error('存在尚无正式模型或策略的目标'); return }
    for (const target of selectedTargets) {
      const goal=goals[target.targetId]
      if (target.valueType !== 'CONTINUOUS' && (!goal?.value?.label || goal.minimumProbability == null)) { message.warning(`请为“${target.name}”选择等级或类别，并填写最低概率`); return }
      if (target.valueType === 'CONTINUOUS' && !continuousGoalComplete(goal?.operator, goal?.value)) { message.warning(`请完整填写“${target.name}”的目标条件`); return }
    }
    const components = materials
      .filter((m) => (materialRatios[m.materialId] ?? 0) > 0 || materialModes[m.materialId] === 'VARIABLE')
      .map((m) => ({ materialId: m.materialId, ratio: materialRatios[m.materialId] ?? 0, unit: 'PERCENT', amountKnown: true }))
    if (!components.length) { message.warning('请填写至少一种材料比例'); return }
    setSubmitting(true); setRun(undefined)
    try {
      const variableMaterials=materials.filter((m)=>materialModes[m.materialId]==='VARIABLE').map((m)=>({materialId:m.materialId,minimum:materialBounds[m.materialId]?.minimum??0,maximum:materialBounds[m.materialId]?.maximum??targetTotal}))
      const variableInputs=Object.fromEntries(Object.entries(inputRanges).filter(([,range])=>range.minimum!=null&&range.maximum!=null))
      const fixedInputs=Object.fromEntries(Object.entries(inputs).filter(([code])=>!variableInputs[code]))
      const request = { typedGoals: selectedTargets.map((target) => { const goal=goals[target.targetId]!; return { targetId: target.targetId, valueType: target.valueType, expectedModelVersionId: target.modelVersionId, operator: goal.operator, value: goal.value, mandatory: !['MAXIMIZE','MINIMIZE'].includes(goal.operator), weight: 1, minimumProbability: goal.minimumProbability } }), targetTotal, formulaBasis: 'MASS_PERCENT', baselineFormula: { basis: 'MASS_PERCENT', compositionComplete: true, components, recordedTotal: targetTotal }, fixedInputs, searchSpace: { allowedMaterialIds: materials.map((m) => m.materialId), variableMaterials, variableInputs }, constraints: {}, candidateCount: 4 }
      const accepted = await formulaDesignApi.submit(request, generateUUID()); setRunId(accepted.runId); window.sessionStorage?.setItem('r08.formulaRunId', accepted.runId); const query = new URLSearchParams(window.location.search); query.set('runId', accepted.runId); window.history.replaceState({}, '', `${window.location.pathname}?${query.toString()}`)
    } catch (error) { message.error(error instanceof Error ? error.message : '配方搜索提交失败') } finally { setSubmitting(false) }
  }
  const setRatio = (id: string, value: number | null) => { clearRun(); setMaterialRatios((current) => ({ ...current, [id]: value ?? 0 })) }
  const changeGoal = (id: string, patch: Partial<{ operator: string; value: any; mandatory: boolean; minimumProbability?: number }>) => { clearRun(); setGoals((current) => ({ ...current, [id]: { operator: current[id]?.operator ?? 'AT_LEAST', value: current[id]?.value, mandatory: current[id]?.mandatory ?? true, ...patch } })) }
  const changeOperator=(target:FormulaTarget,operator:string)=>changeGoal(target.targetId,{operator,value:goalValue(target,operator),mandatory:!['MAXIMIZE','MINIMIZE'].includes(operator)})
  return <Layout className="formula-prediction-page"><div className="formula-prediction-shell">
    <div className="formula-prediction-header"><div><Text className="eyebrow">AI研发助手 · 配方预测</Text><Title level={2}>AI配方预测</Title><Text type="secondary">先定义希望达到的性能和具体测试项，再确认条件，最后比较候选配方。</Text></div><Space><Tag color="blue">最多 4 个候选</Tag><Button type="primary" icon={<SearchOutlined />} onClick={runDesign} loading={submitting}>开始搜索</Button></Space></div>
    <div className="prototype-note"><Text strong>目标 → 条件 → 候选</Text><Text type="secondary"> 每个目标使用已固定的模型、输入方案和适用域；条件变化后需要重新评估。</Text></div>
    <Row gutter={[18, 18]}>
      <Col xs={24} lg={15}><Card title="研发目标" className="prediction-card" extra={<Text type="secondary">可多选目标</Text>}>
        <Select mode="multiple" value={selectedIds} onChange={selectTargets} placeholder="选择要同时满足的研发目标" className="full-control" optionFilterProp="label" options={availableTargets.map((target) => ({ value: target.targetId, label: `${target.name} · ${typeLabel[target.valueType]}`, disabled: !target.available }))} />
        <div className="target-list">{selectedTargets.length ? selectedTargets.map((target) => {const goal=goals[target.targetId];const preference=['MAXIMIZE','MINIMIZE'].includes(goal?.operator ?? '');return <Card size="small" key={target.targetId} className="goal-card"><div className="goal-heading"><Space><Tag color="geekblue">{typeLabel[target.valueType]}</Tag><Text strong>{target.name}</Text></Space><Tag color="green">模型已固定</Tag></div><Row gutter={[10,10]} align="middle"><Col xs={24} md={7}><Select value={goal?.operator} onChange={(value) => changeOperator(target,value)} className="full-control" options={(target.operators ?? ['AT_LEAST']).map((value) => ({ value, label: operatorLabel(value, target.valueType) }))} /></Col><Col xs={24} md={target.valueType==='CONTINUOUS'?11:9}>{target.valueType!=='CONTINUOUS'?<Select className="full-control" value={goal?.value?.label} onChange={(label)=>changeGoal(target.targetId,{value:{label}})} placeholder={`选择${target.valueType==='ORDINAL'?'等级':'类别'}`} options={(target.classes??[]).map((label)=>({value:label,label}))}/>:preference?<Text type="secondary">作为偏好目标参与排序</Text>:goal?.operator==='RANGE'?<Space.Compact block><InputNumber className="full-control" value={goal?.value?.[0]} onChange={(value)=>changeGoal(target.targetId,{value:[value??undefined,goal?.value?.[1]]})} placeholder="下界"/><InputNumber className="full-control" value={goal?.value?.[1]} onChange={(value)=>changeGoal(target.targetId,{value:[goal?.value?.[0],value??undefined]})} placeholder="上界"/></Space.Compact>:goal?.operator==='MATCH'?<Space.Compact block><InputNumber className="full-control" value={goal?.value?.target} onChange={(value)=>changeGoal(target.targetId,{value:{...goal?.value,target:value??undefined}})} placeholder="目标值"/><InputNumber className="full-control" min={0} value={goal?.value?.tolerance} onChange={(value)=>changeGoal(target.targetId,{value:{...goal?.value,tolerance:value??undefined}})} placeholder="容差"/></Space.Compact>:<InputNumber className="full-control" value={goal?.value} onChange={(value)=>changeGoal(target.targetId,{value:value??undefined})} placeholder="目标值" addonAfter={target.unit}/>}</Col>{target.valueType !== 'CONTINUOUS' && <Col xs={24} md={6}><InputNumber min={0} max={1} step={0.05} value={goal?.minimumProbability} onChange={(value) => changeGoal(target.targetId, { minimumProbability: value ?? undefined })} addonBefore="最低概率" placeholder="必填" className="full-control" /></Col>}</Row></Card>}) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请选择一个或多个目标" />}</div>
      </Card>
      <Card title="2 · 材料与配方空间" className="prediction-card" extra={<Space><Text>目标合计</Text><InputNumber min={0.01} value={targetTotal} onChange={(value) => setTargetTotal(value ?? 100)} addonAfter="%" /></Space>}>
        {materials.length ? <List size="small" dataSource={materials} renderItem={(material, index) => {const mode=materialModes[material.materialId]??'VARIABLE';const bounds=materialBounds[material.materialId]??{};const internalCode=/^(R05|R06|R07|R10|SYNTHETIC|TEST|STRUCT:)/i.test(`${material.code ?? ''}`);return <List.Item><div className="material-space-row"><div><Text strong>{materialLabel(material, index)}</Text><Text type="secondary">{!internalCode && material.code && material.code !== materialLabel(material, index) ? material.code : (material.role || '配方材料')}</Text></div><InputNumber min={0} precision={4} value={materialRatios[material.materialId] ?? 0} onChange={(value) => setRatio(material.materialId, value)} addonAfter="%" /><Select value={mode} onChange={(value)=>setMaterialModes((old)=>({...old,[material.materialId]:value}))} options={[{value:'FIXED',label:'固定'},{value:'VARIABLE',label:'允许调整'}]}/>{mode==='VARIABLE'&&<Space.Compact><InputNumber min={0} precision={4} value={bounds.minimum} onChange={(value)=>setMaterialBounds((old)=>({...old,[material.materialId]:{...bounds,minimum:value??undefined}}))} placeholder="最小"/><InputNumber min={0} precision={4} value={bounds.maximum} onChange={(value)=>setMaterialBounds((old)=>({...old,[material.materialId]:{...bounds,maximum:value??undefined}}))} placeholder="最大"/></Space.Compact>}</div></List.Item>}} /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="选择目标后显示共同材料" />}<Divider /><div className={Math.abs(ratioSum - targetTotal) < 0.00001 ? 'ratio-total valid' : 'ratio-total'}><Text>当前配方合计</Text><Text strong>{ratioSum.toFixed(2)}%</Text>{Math.abs(ratioSum - targetTotal) < 0.00001 ? <Tag color="green">符合目标合计</Tag> : <Tag color="orange">还差 {(targetTotal - ratioSum).toFixed(2)}%</Tag>}</div>
      </Card>
      <Card title="当前共同实验条件" className="prediction-card">{context?.requiredInputs?.length ? <Row gutter={[12, 12]}>{context.requiredInputs.map((field) => {const range=inputRanges[field.code];return <Col xs={24} md={12} key={field.code}><Space direction="vertical" className="full-control" size={4}><Text type="secondary">{field.name || field.code}{field.unit ? `（${field.unit}）` : ''}</Text>{field.valueType === 'NUMBER' ? <><Select value={range?'VARIABLE':'FIXED'} onChange={(mode)=>setInputRanges((old)=>{const next={...old};if(mode==='VARIABLE')next[field.code]={minimum:inputs[field.code] as number|undefined,maximum:inputs[field.code] as number|undefined};else delete next[field.code];return next})} options={[{value:'FIXED',label:'固定值'},{value:'VARIABLE',label:'允许变化'}]}/>{range?<Space.Compact block><InputNumber value={range.minimum} onChange={(value)=>setInputRanges((old)=>({...old,[field.code]:{...range,minimum:value??undefined}}))} placeholder="最小"/><InputNumber value={range.maximum} onChange={(value)=>setInputRanges((old)=>({...old,[field.code]:{...range,maximum:value??undefined}}))} placeholder="最大"/><InputNumber min={0} value={range.step} onChange={(value)=>setInputRanges((old)=>({...old,[field.code]:{...range,step:value??undefined}}))} placeholder="步长"/></Space.Compact>:<InputNumber value={inputs[field.code] ?? undefined} onChange={(value)=>setInputs((current)=>({...current,[field.code]:value}))} placeholder="填写固定条件" className="full-control" />}</> : <Input value={inputs[field.code] ?? ''} onChange={(event)=>setInputs((current)=>({...current,[field.code]:event.target.value}))} placeholder="填写固定条件" />}</Space></Col>})}</Row> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="选择目标后显示模型所需条件" />}</Card>
      </Col>
      <Col xs={24} lg={9}><Card title="研发条件摘要" className="prediction-card confirmation-card"><List size="small"><List.Item><Text>预测目标</Text><Text strong>{selectedTargets.length ? selectedTargets.map((target) => target.name).join('、') : '尚未选择'}</Text></List.Item><List.Item><Text>配方基准</Text><Text strong>质量百分比 · {targetTotal}%</Text></List.Item><List.Item><Text>材料合计</Text><Text strong>{ratioSum.toFixed(2)}%</Text></List.Item><List.Item><Text>模型</Text><Text strong>{selectedTargets.length ? `${selectedTargets.length} 个已固定` : '等待选择目标'}</Text></List.Item></List></Card>
        {run ? <Card title="搜索进度" className="prediction-card run-card"><Progress percent={run.progress} status={run.executionStatus === 'FAILED' ? 'exception' : undefined} /><Space><Tag icon={run.executionStatus === 'SUCCEEDED' ? <CheckCircleOutlined /> : run.executionStatus === 'FAILED' ? <WarningOutlined /> : <LoadingOutlined />}>{stageLabel[run.currentStage || run.executionStatus] || run.executionStatus}</Tag><Text type="secondary">{run.actualCandidateCount}/{run.requestedCandidateCount} 个候选</Text></Space>{run.executionStatus === 'FAILED' && <Alert className="compact-alert" type="error" message="搜索执行失败，请使用新的请求重试" />}</Card> : <Card className="prediction-card empty-result"><ExperimentOutlined className="empty-icon" /><Title level={4}>候选配方方向</Title><Text type="secondary">确认研发目标和实验条件后，候选配方及逐目标模型结果会显示在这里。</Text></Card>}
        {run?.executionStatus === 'SUCCEEDED' && <Card title={run.outcomeStatus === 'BLOCKED' ? '没有可行候选' : '候选对比'} className="prediction-card"><Statistic title="实际候选数量" value={run.actualCandidateCount} suffix={`/ ${run.requestedCandidateCount}`} />{run.outcomeStatus !== 'SUCCEEDED' && <Alert className="compact-alert" type="warning" message={run.shortfallReason || '当前条件下没有足够的可行方案'} description={<div>{run.rejectionSummary&&Object.entries(run.rejectionSummary).filter(([,count])=>count>0).map(([code,count])=><Tag key={code}>{({TARGET_NOT_MET:'目标未达到',OUT_OF_DOMAIN:'超出适用范围',INPUT_MISSING:'输入缺失',MATERIAL_MISMATCH:'材料不匹配',RULE_FAILED:'规则未通过',DUPLICATE:'重复方案'} as Record<string,string>)[code]??code} {count}</Tag>)}{run.actionHints?.length?<div className="adjustment-hints">建议：{run.actionHints.join('；')}</div>:null}</div>} />}{(Array.isArray(run.candidates) ? run.candidates : []).map((candidate) => <Card size="small" className="candidate-card" key={candidate.id}><Space><Tag color="blue">方案 {candidate.candidateNo}</Tag><Text strong>{candidate.title}</Text><Tag color="green">综合分 {Number(candidate.score ?? 0).toFixed(2)}</Tag></Space><div className="formula-summary">{(Array.isArray(candidate.formula?.components) ? candidate.formula.components : []).map((component: any, index: number) => { const material = materials.find((item) => item.materialId === component.materialId); return <Tag key={component.materialId}>{materialLabel(material ?? component, index)} {Number(component.ratio).toFixed(2)}%</Tag> })}</div><div className="target-results">{(Array.isArray(candidate.targetResults?.targets) ? candidate.targetResults.targets : []).map((result: any) => <Tag color={result.status === 'SUCCEEDED' ? 'green' : 'red'} key={`${result.targetId}-${result.status}`}>{result.targetName || '预测目标'}: {displayTargetResult(result)}</Tag>)}</div></Card>)}</Card>}
      </Col>
    </Row>
  </div></Layout>
}
