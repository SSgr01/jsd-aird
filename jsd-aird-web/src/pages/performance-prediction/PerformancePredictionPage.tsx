import { AimOutlined, CheckCircleFilled, CloseCircleFilled, ExperimentOutlined, PlusOutlined, SafetyCertificateOutlined } from '@ant-design/icons'
import { Alert, App, Button, Card, Checkbox, Collapse, Drawer, Empty, Input, InputNumber, Select, Space, Spin, Tag, Typography } from 'antd'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { predictionApi } from '@/services/ai-rnd/prediction-api'
import type { PredictionContext, PredictionResponse, PredictionRunning, TargetSuccess, TypedPrediction } from '@/services/ai-rnd/ai-rnd-types'
import { generateUUID } from '@/utils/uuid'
import './performance-prediction.css'

interface FormulaRow { key: string; materialId?: string; ratio?: number; unit: string }
const unavailableLabels: Record<string, string> = {
  NO_ACTIVE_MODEL: '尚无正式模型', QUALITY_POLICY_NOT_READY: '质量策略未发布',
  DOMAIN_POLICY_NOT_READY: '适用域策略未就绪', TARGET_NOT_READY: '目标定义未发布',
}
const predictionErrorLabels: Record<string, string> = {
  FORMULA_INCOMPLETE: '请确认已录入全部配方成分', FORMULA_AMOUNT_MISSING: '存在尚未填写用量的材料',
  FORMULA_UNIT_UNRESOLVED: '配方用量单位不明确', MISSING_REQUIRED_X: '缺少模型要求的实验前条件',
  MATERIAL_NOT_IN_MODEL: '材料已识别，但当前模型未覆盖', OUT_OF_DOMAIN: '当前配方或条件超出模型适用范围',
}
const resultTypeLabels = { CONTINUOUS: '连续数值', ORDINAL: '有序等级', BINARY: '二分类', CATEGORICAL: '多分类' }
const inputGroupLabels: Record<string, string> = { FORMULA: '配方组成', PROCESS: '工艺条件', CONDITION: '环境与基材', OTHER: '其他实验前条件' }
const trust = { HIGH: { text: '可信度高', color: 'green' }, MEDIUM: { text: '可信度中等', color: 'gold' }, LOW: { text: '可信度较低', color: 'orange' } }
const materialRoleLabels: Record<string, string> = {
  RESIN: '树脂', ADDITIVE: '助剂', CROSSLINKER: '交联剂', SOLVENT: '溶剂',
  PHOTO_INITIATOR: '光引发剂', PIGMENT: '颜料', FILLER: '填料', SUBSTRATE: '基材',
}
function materialLabel(item: { code: string; name?: string; role?: string | null }) {
  if(item.name) return item.code&&item.code!==item.name?`${item.name}（${item.code}）`:item.name
  const name = materialRoleLabels[item.role ?? ''] ?? materialRoleLabels[item.code] ?? '材料'
  return `${name} · ${item.code}`
}

function isRunning(value?: PredictionResponse | PredictionRunning): value is PredictionRunning { return value?.executionStatus === 'RUNNING' }

function PredictionValue({ value }: { value: TypedPrediction }) {
  if (value.resultType === 'CONTINUOUS') return <div className="prediction-value"><strong>{value.value.toFixed(2)}</strong><span>{value.unit}</span>{value.interval && <small>{value.interval.lower.toFixed(2)} ～ {value.interval.upper.toFixed(2)} · {(value.interval.coverageLevel * 100).toFixed(0)}% 区间</small>}</div>
  if (value.resultType === 'BINARY') return <div className="prediction-class"><strong>{value.label}</strong><span>{value.positiveClass} 概率 {(value.probability * 100).toFixed(1)}%</span><ProbabilityRows values={{ [value.positiveClass]: value.probability }} /></div>
  if (value.resultType === 'ORDINAL') return <div className="prediction-class"><strong>{value.label}</strong><span>达到该等级或更高的概率 {((value.thresholdProbability ?? 0) * 100).toFixed(1)}%</span><ProbabilityRows values={value.probabilities} ordered /></div>
  return <div className="prediction-class"><strong>{value.label}</strong><span>分类概率分布</span><ProbabilityRows values={value.probabilities} /></div>
}
function ProbabilityRows({ values, ordered = false }: { values: Record<string, number>; ordered?: boolean }) {
  const rows = Object.entries(values).sort((a, b) => ordered ? 0 : b[1] - a[1])
  return <div className="probability-list">{rows.map(([label, value]) => <div key={label}><span>{label}</span><i><b style={{ width: `${Math.max(2, value * 100)}%` }} /></i><em>{(value * 100).toFixed(1)}%</em></div>)}</div>
}

export function PerformancePredictionPage() {
  const { message } = App.useApp()
  const [catalog, setCatalog] = useState<PredictionContext>()
  const [context, setContext] = useState<PredictionContext>()
  const [targets, setTargets] = useState<string[]>([])
  const [formula, setFormula] = useState<FormulaRow[]>([{ key: generateUUID(), unit: '%' }])
  const [complete, setComplete] = useState(false)
  const [inputs, setInputs] = useState<Record<string, unknown>>({})
  const [result, setResult] = useState<PredictionResponse>()
  const [runningRecord, setRunningRecord] = useState<string>()
  const [loading, setLoading] = useState(true)
  const [predicting, setPredicting] = useState(false)
  const [evidence, setEvidence] = useState<TargetSuccess>()
  const pollRef = useRef<number>()

  const report = useCallback((error: unknown) => { void message.error(error instanceof Error ? error.message : '加载失败') }, [message])
  useEffect(() => { predictionApi.context().then(setCatalog).catch(report).finally(() => setLoading(false)) }, [report])
  useEffect(() => {
    if (!targets.length) { setContext(undefined); return }
    predictionApi.context(targets).then((value) => { setContext(value); const next: Record<string, unknown> = {}; value.requiredInputs.forEach((field) => { next[field.code] = inputs[field.code] }); setInputs(next) }).catch(report)
    // The selected models and X union are refreshed as one pinned preview.
  }, [report, targets.join('|')])

  const total = useMemo(() => formula.reduce((sum, item) => sum + (item.ratio ?? 0), 0), [formula])
  const formulaRequired = Boolean(context?.selected.some((item) => item.formulaRequirement === 'REQUIRED'))
  const incompleteRows = useMemo(() => formulaRequired ? formula.filter((item) => !item.materialId || item.ratio == null) : [], [formula, formulaRequired])
  const requestFingerprint = useMemo(() => JSON.stringify({ targets, formula, inputs, complete }), [complete, formula, inputs, targets])
  useEffect(() => { setResult(undefined) }, [requestFingerprint])

  const stopPolling = useCallback(() => { if (pollRef.current) window.clearTimeout(pollRef.current); pollRef.current = undefined }, [])
  const poll = useCallback(async (id: string) => {
    try {
      const value = await predictionApi.record(id)
      if (isRunning(value)) { pollRef.current = window.setTimeout(() => void poll(id), value.pollAfterMs); return }
      setResult(value);setRunningRecord(undefined);setPredicting(false);localStorage.removeItem('r07-running-prediction')
    } catch (error) { setPredicting(false);report(error) }
  }, [report])
  useEffect(() => { const id=localStorage.getItem('r07-running-prediction');if(id){setRunningRecord(id);setPredicting(true);void poll(id)}return stopPolling }, [poll, stopPolling])

  const predict = async () => {
    if (!targets.length) { void message.warning('请至少选择一个预测目标'); return }
    if (formulaRequired && incompleteRows.length) { void message.warning('请补充标出的材料和用量'); return }
    if (formulaRequired && !complete) { void message.warning('请确认已录入全部配方成分'); return }
    setPredicting(true)
    try {
      const payload = {
        formula: formulaRequired ? { basis: 'MASS_PERCENT' as const, compositionComplete: complete, recordedTotal: Number(total.toFixed(6)), components: formula.map((item) => ({ materialId: item.materialId!, ratio: item.ratio, unit: item.unit, amountKnown: item.ratio != null })) } : undefined,
        inputs, targets: targets.map((targetId) => ({ targetId, expectedModelVersionId: context?.selected.find((item) => item.targetId === targetId)?.modelVersionId })),
      }
      const value=await predictionApi.predict(payload,generateUUID())
      if(isRunning(value)){setRunningRecord(value.predictionRecordId);localStorage.setItem('r07-running-prediction',value.predictionRecordId);void poll(value.predictionRecordId)}
      else{setResult(value);setPredicting(false)}
    } catch(error){setPredicting(false);report(error)}
  }

  const updateFormula=(key:string,patch:Partial<FormulaRow>)=>{setComplete(false);setFormula((rows)=>rows.map((row)=>row.key===key?{...row,...patch}:row))}
  const selectedById=new Map(context?.selected.map((item)=>[item.targetId,item]))
  const canPredict=Boolean(targets.length&&context?.selected.some((item)=>item.available)&&!context?.configurationConflicts?.length&&(!formulaRequired||(complete&&!incompleteRows.length)))

  return <main className="performance-page">
    <header className="performance-header"><div><Typography.Title level={2}>性能预测</Typography.Title><Typography.Text>输入准备实验的配方与条件，同时预测多个性能。</Typography.Text></div><div className="formal-mark"><SafetyCertificateOutlined /><span>正式模型结果</span></div></header>

    <section className="prediction-gates">
      <div className="section-title"><span>选择预测目标</span><small>每个性能独立检查模型、输入、材料和适用范围</small></div>
      <Select mode="multiple" value={targets} onChange={(value)=>{setTargets(value);setComplete(false)}} loading={loading} placeholder="按性能名称选择，可多选" maxTagCount="responsive" optionFilterProp="label" options={catalog?.targets.map((item)=>({ value:item.targetId,label:`${item.name} · ${resultTypeLabels[item.valueType]}${item.formalPredictionAvailable?'':' · 暂不可预测'}`, disabled:item.targetStatus==='RETIRED'||!item.formalPredictionAvailable }))} />
      {targets.length>0&&<div className="gate-grid">{targets.map((id)=>{const item=selectedById.get(id);const base=catalog?.targets.find((target)=>target.targetId===id);const available=item?.available;const reasons=item?.unavailableReasons??base?.unavailableReasons??[];return <div key={id} className={`gate-card ${available?'ready':'blocked'}`}><div>{available?<CheckCircleFilled />:<CloseCircleFilled />}<strong>{item?.name??base?.name??'未知目标'}</strong><Tag>{resultTypeLabels[(item?.valueType??base?.valueType) as keyof typeof resultTypeLabels]}</Tag></div><span>{available?'正式模型、输入方案和策略已固定':reasons.map((reason)=>unavailableLabels[reason]??reason).join('；')}</span></div>})}</div>}
      {!!context?.configurationConflicts?.length&&<Alert type="error" showIcon message="所选模型对同一输入字段的类型或单位要求冲突，请调整目标组合" />}
    </section>

    <div className="prediction-workspace">
      <section className="prediction-inputs">
        <div className="panel-heading"><div><ExperimentOutlined /><span>配方与实验条件</span></div><small>所有用量均按实际值提交</small></div>
        {formulaRequired?<Card size="small" title="配方组成" extra={<span className="formula-total">合计 {total.toFixed(2)}%</span>}>
          <div className="formula-list">{formula.map((row,index)=>{const invalid=!row.materialId||row.ratio==null;return <div className={`formula-row ${invalid?'invalid':''}`} key={row.key}><b>{index+1}</b><Select status={!row.materialId?'error':undefined} showSearch optionFilterProp="label" value={row.materialId} onChange={(materialId)=>updateFormula(row.key,{materialId})} placeholder="选择材料" options={context?.materials.map((item)=>({value:item.materialId,label:materialLabel(item)}))}/><InputNumber status={row.ratio==null?'error':undefined} value={row.ratio} onChange={(ratio)=>updateFormula(row.key,{ratio:ratio??undefined})} min={0} precision={4} placeholder="空白表示未知，0表示未使用" addonAfter={row.unit}/><Button type="text" danger disabled={formula.length===1} onClick={()=>{setComplete(false);setFormula((rows)=>rows.filter((item)=>item.key!==row.key))}}>删除</Button></div>})}</div>
          <Button type="dashed" block icon={<PlusOutlined />} onClick={()=>{setComplete(false);setFormula((rows)=>[...rows,{key:generateUUID(),unit:'%'}])}}>添加材料</Button>
          {!!incompleteRows.length&&<Alert className="formula-warning" type="warning" showIcon message="请填写每种材料的用量；0 表示明确未使用，空白表示未知"/>}
          <Checkbox checked={complete} onChange={(event)=>setComplete(event.target.checked)}>已录入全部配方成分</Checkbox>
        </Card>:<Card size="small"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="所选模型不使用配方组成，可直接填写实验前条件"/></Card>}
        <Card size="small" title="实验前条件" className="condition-card">
          {!context?.requiredInputs.length?<Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="选择可用目标后显示模型需要的条件"/>:<div className="condition-groups">{(['PROCESS','CONDITION','OTHER'] as const).map((group)=><div key={group} className="condition-group"><div className="condition-group-title">{inputGroupLabels[group]}</div><div className="condition-grid">{context.requiredInputs.filter((field)=>field.inputGroup===group || (!field.inputGroup&&group==='OTHER')).map((field)=><label key={field.code}>
            <span>{field.name||field.sourceLabel||field.code}{field.required&&<em>*</em>} {field.unit&&<small>{field.unit}</small>}</span>
            {field.valueType==='NUMBER'?<InputNumber value={inputs[field.code] as number|undefined} onChange={(value)=>setInputs((old)=>({...old,[field.code]:value}))} />:field.valueType==='BOOLEAN'?<Select value={inputs[field.code] as boolean|undefined} onChange={(value)=>setInputs((old)=>({...old,[field.code]:value}))} options={[{value:true,label:'是'},{value:false,label:'否'}]}/>:field.valueType==='CATEGORY'?<Select allowClear value={inputs[field.code] as string|undefined} onChange={(value)=>setInputs((old)=>({...old,[field.code]:value}))} options={(field.allowedValues??(field.encoding?.allowedValues as Array<string|number>|undefined)??[]).map((value)=>({value:String(value),label:String(value)}))} placeholder="请选择"/>:<Input value={inputs[field.code] as string|undefined} onChange={(event)=>setInputs((old)=>({...old,[field.code]:event.target.value||undefined}))} />}
            <small className="input-source-hint">{field.requiredByTargets?.length ? `用于：${field.requiredByTargets.map((target)=>target.targetName).join('、')}` : field.sourceLabel}</small>
          </label>)}</div></div>)}</div>}
          {context?.selected.some((target)=>Object.keys(target.fixedConditions??{}).length>0)&&<Alert type="info" showIcon message="测试方法、阶段和固定条件已按各目标模型固定" description={context.selected.filter((target)=>Object.keys(target.fixedConditions??{}).length>0).map((target)=>`${target.name}：${Object.values(target.fixedConditions??{}).filter(Boolean).join(' · ')}`).join('；')} />}
        </Card>
        <Button className="predict-button" size="large" type="primary" icon={<AimOutlined />} loading={predicting} disabled={!canPredict||Boolean(runningRecord)} onClick={()=>void predict()}>开始性能预测</Button>
      </section>

      <section className="prediction-results">
        <div className="panel-heading"><div><AimOutlined /><span>预测结果</span></div>{result?.outcomeStatus&&<Tag color={result.outcomeStatus==='SUCCEEDED'?'green':result.outcomeStatus==='PARTIAL'?'gold':'default'}>{result.outcomeStatus==='SUCCEEDED'?'全部完成':result.outcomeStatus==='PARTIAL'?'部分完成':'条件不满足'}</Tag>}</div>
        {predicting&&<div className="result-empty"><Spin size="large"/><strong>正在调用固定的正式模型</strong><span>页面刷新后仍会按预测记录恢复状态</span></div>}
        {!predicting&&!result&&<div className="result-empty"><AimOutlined/><strong>结果将在这里逐项展示</strong><span>连续值、等级和分类结果使用各自适合的表达。</span></div>}
          {result&&<div className="result-stack">
          {result.executionStatus==='FAILED'&&<Alert type="error" showIcon message="本次预测未能正常执行" description="模型制品或计算服务存在异常。已保存失败记录，可使用新请求重试。"/>}
          {result.results.map((item)=>{const name=context?.selected.find((target)=>target.targetId===item.targetId)?.name??catalog?.targets.find((target)=>target.targetId===item.targetId)?.name??'预测目标';if(item.status==='SUCCEEDED'){const label=trust[item.quality?.level]??{text:'可信度待定',color:'default'};return <Card key={item.targetId} className="result-card"><div className="result-card-head"><div><CheckCircleFilled/><strong>{name}</strong></div><Space><Tag color={item.applicability.status==='IN_DOMAIN'?'green':'gold'}>{item.applicability.status==='IN_DOMAIN'?'适用范围内':'接近适用边界'}</Tag><Tag color={label.color}>{label.text}</Tag></Space></div><PredictionValue value={item.prediction}/>{item.warnings.filter((warning)=>warning.code!=='FORMULA_TOTAL_WARNING').map((warning)=><Alert key={warning.code} type="warning" showIcon message={warning.message}/>)}<Button type="link" onClick={()=>setEvidence(item)}>查看预测依据</Button></Card>}
            return <Card key={item.targetId} className={`result-card result-${item.status.toLowerCase()}`}><div className="result-card-head"><div><CloseCircleFilled/><strong>{name}</strong></div><Tag color={item.status==='FAILED'?'red':'default'}>{item.status==='FAILED'?'模型异常':'无法预测'}</Tag></div><p>{predictionErrorLabels[item.errorCode]??item.message}</p></Card>})}
        </div>}
      </section>
    </div>
    <Drawer open={Boolean(evidence)} onClose={()=>setEvidence(undefined)} width={560} title="预测依据">{evidence&&<div className="evidence-drawer"><p>该结果固定使用提交瞬间的模型和配置版本。</p><dl><dt>模型版本</dt><dd>{evidence.evidence.modelVersionId}</dd><dt>目标定义</dt><dd>{evidence.evidence.targetVersionId}</dd><dt>输入方案</dt><dd>{evidence.evidence.inputSchemeId}</dd><dt>训练快照</dt><dd>{evidence.evidence.snapshotId}</dd><dt>适用域策略</dt><dd>{evidence.evidence.domainPolicyVersionId}</dd><dt>质量策略</dt><dd>{evidence.evidence.qualityPolicyVersionId}</dd></dl><Collapse ghost items={[{key:'raw',label:'高级信息',children:<pre>{JSON.stringify(evidence,null,2)}</pre>}]} /></div>}</Drawer>
  </main>
}
