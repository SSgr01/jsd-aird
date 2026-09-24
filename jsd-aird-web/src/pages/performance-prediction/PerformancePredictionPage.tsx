import { AimOutlined, CheckCircleFilled, CloseCircleFilled, ExperimentOutlined, PlusOutlined, SafetyCertificateOutlined } from '@ant-design/icons'
import { Alert, App, Button, Card, Checkbox, Drawer, Empty, Input, InputNumber, Select, Space, Spin, Tag, Typography } from 'antd'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { predictionApi } from '@/services/ai-rnd/prediction-api'
import type { PredictionContext, PredictionResponse, PredictionRunning, TargetSuccess, TypedPrediction } from '@/services/ai-rnd/ai-rnd-types'
import { generateUUID } from '@/utils/uuid'
import { RegisteredTargetsNotice } from '@/components/registered-targets-notice/RegisteredTargetsNotice'
import { isModelVersionChanged } from '@/services/http/errors'
import './performance-prediction.css'

interface FormulaRow { key: string; materialId?: string; ratio?: number; unit: string }
const unavailableLabels: Record<string, string> = {
  NO_ACTIVE_MODEL: '尚无正式模型', QUALITY_POLICY_NOT_READY: '质量策略未发布',
  DOMAIN_POLICY_NOT_READY: '适用域策略未就绪', TARGET_NOT_READY: '目标定义未发布',
}
const predictionErrorLabels: Record<string, string> = {
  FORMULA_INCOMPLETE: '请确认已录入全部配方成分', FORMULA_AMOUNT_MISSING: '存在尚未填写用量的材料',
  FORMULA_RATIO_MISSING: '有材料比例为空：空白表示未知，请补充；明确未使用请填0',
  FORMULA_RATIO_NEGATIVE: '材料比例不能为负数，请检查该材料的用量',
  FORMULA_RATIO_NOT_NUMERIC: '材料比例必须是数字，请检查输入内容',
  FORMULA_TOTAL_NON_POSITIVE: '配方合计必须大于0，当前无法归一化',
  FORMULA_UNIT_UNRESOLVED: '配方用量单位不明确', MISSING_REQUIRED_X: '缺少模型要求的实验前条件',
  MATERIAL_NOT_IN_MODEL: '材料已识别，但当前模型未覆盖', OUT_OF_DOMAIN: '当前配方或实验条件超出模型覆盖范围，请调整输入后重试',
  COMPUTE_UNAVAILABLE: '预测服务暂时未连接；请稍后重试，模型和输入设置不会丢失',
  MODEL_ARTIFACT_UNAVAILABLE: '模型文件暂时不可用，请联系管理员检查模型制品',
  MODEL_CONTRACT_MISMATCH: '当前模型版本与预测输入不匹配，请刷新后重试',
}
const resultTypeLabels = { CONTINUOUS: '连续数值', ORDINAL: '有序等级', BINARY: '二分类', CATEGORICAL: '多分类' }
const targetOptionLabel = (target: { name: string; valueType: string; unit?: string | null }) => `${target.name} · ${resultTypeLabels[target.valueType as keyof typeof resultTypeLabels] ?? '预测目标'}${target.unit ? `（${target.unit}）` : ''}`
const inputGroupLabels: Record<string, string> = { FORMULA: '配方组成', PROCESS: '工艺条件', CONDITION: '环境与基材', OTHER: '其他实验前条件' }
const trust = { HIGH: { text: '可信度高', color: 'green' }, MEDIUM: { text: '可信度中等', color: 'gold' }, LOW: { text: '可信度较低', color: 'orange' } }
const materialRoleLabels: Record<string, string> = {
  RESIN: '树脂', ADDITIVE: '助剂', CROSSLINKER: '交联剂', SOLVENT: '溶剂',
  PHOTO_INITIATOR: '光引发剂', PIGMENT: '颜料', FILLER: '填料', SUBSTRATE: '基材',
}
const inputFieldLabels: Record<string, string> = {
  COATING_SOLIDS_PCT: '涂料固含', COATING_SOLIDS: '涂料固含', TEMPERATURE_C: '温度', HUMIDITY_RH_PCT: '湿度',
  UVA_INTENSITY_MW_CM2: 'UVA光强', UV_ENERGY_MJ_CM2: 'UV能量', APPLICATOR_SPEC_UM: '绕丝棒规格',
  APPLICATION_METHOD: '施工方式', CURING_SOURCE: '固化光源', SUBSTRATE: '基材',
}
function materialLabel(item: { code: string; name?: string; role?: string | null }) {
  if(item.name) return item.code&&item.code!==item.name?`${item.name}（${item.code}）`:item.name
  const name = materialRoleLabels[item.role ?? ''] ?? materialRoleLabels[item.code] ?? '材料'
  return `${name} · ${item.code}`
}
function categoryOptions(field: any) {
  const values = field.allowedValues ?? (field.encoding?.allowedValues as Array<string | number> | undefined)
  if (Array.isArray(values) && values.length) return values.map((value) => ({ value: String(value), label: String(value) }))
  const code = String(field.code ?? '').toLowerCase().replace(/[-_]/g, '')
  const name = String(field.name ?? field.sourceLabel ?? '')
  if (code.includes('substrate') || code.includes('base') || name.includes('基材') || name.includes('膜')) return [{ value: 'PET_100UM_OPTICAL', label: 'PET 100μm光学膜' }, { value: 'PC_170UM', label: 'PC 170μm膜' }, { value: 'PMMA_PC_COMPOSITE_064', label: 'PMMA/PC复合板' }]
  if (code.includes('applicationmethod') || code.includes('coatingmethod') || name.includes('施工') || name.includes('涂布')) return [{ value: 'WIRE_BAR_ROLL_COAT_40UM', label: '40μm绕丝棒涂布' }, { value: 'SPRAY_COATING', label: '喷涂' }]
  if (code.includes('curingsource') || code.includes('curesource') || name.includes('固化') || name.includes('光源')) return [{ value: 'MERCURY_LAMP_UV', label: '汞灯UV' }, { value: 'LED_UV', label: 'LED UV' }]
  return []
}
function FriendlyCategorySelect({ field, value, onChange }: { field: any; value: unknown; onChange: (value: string | undefined) => void }) {
  const options = categoryOptions(field)
  const selected = options.find((option) => String(option.value) === String(value))
  return <Select allowClear value={selected?.label} onChange={(label) => onChange(options.find((option) => option.label === label)?.value)} options={options.map((option) => ({ value: option.label, label: option.label }))} placeholder="请选择" />
}
function fixedConditionLabel(value: unknown) {
  const labels: Record<string, string> = {
    UVPU_APPLICATION_FORMULATION: 'UV/PU应用配方',
    UV_IMMEDIATE: 'UV固化后即时测试',
    NONE: '无特殊前处理',
    STANDARD: '标准条件',
  }
  return labels[String(value)] ?? String(value)
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

  // Customer SQL metrics are the current local development scope. Keep legacy
  // UV/PU targets available, but present the newly trained customer metrics
  // first so the page does not default to the old warping workflow.
  const orderedTargets = useMemo(() => {
    const items = catalog?.targets ?? []
    return [...items].sort((a, b) => {
      const customerOrder = ['修复速度', '涂层伸长率', '断裂伸长率', '拉伸强度', '剥离力', '弯曲韧性', '耐酸雨', '耐溶剂', '耐防晒霜', '耐芥末酱发黄']
      const rank = (target: typeof a) => {
        if (target.code.startsWith('CUSTOMER.TEST.')) {
          const index = customerOrder.indexOf(target.name)
          // Keep the ten enabled customer-SQL models together. Other imported
          // customer fields remain available/searchable, but must not push the
          // active metrics apart in the first viewport of the selector.
          return index >= 0 ? index : target.formalPredictionAvailable ? 20 : 30
        }
        if (target.formalPredictionAvailable) return 21
        return target.registered ? 31 : 32
      }
      const byRank = rank(a) - rank(b)
      return byRank || a.name.localeCompare(b.name, 'zh-CN')
    })
  }, [catalog?.targets])

  const report = useCallback((error: unknown) => { void message.error(error instanceof Error ? error.message : '加载失败') }, [message])
  useEffect(() => { predictionApi.context().then(setCatalog).catch(report).finally(() => setLoading(false)) }, [report])
  useEffect(() => {
    if (targets.length || !orderedTargets.length) return
    const firstCustomerTarget = orderedTargets.find((target) => target.code.startsWith('CUSTOMER.TEST.') && target.formalPredictionAvailable)
    if (firstCustomerTarget) setTargets([firstCustomerTarget.targetId])
  }, [orderedTargets, targets.length])
  useEffect(() => {
    if (!targets.length) { setContext(undefined); return }
    predictionApi.context(targets).then((value) => { setContext(value); const next: Record<string, unknown> = {}; value.requiredInputs.forEach((field) => { next[field.code] = inputs[field.code] }); setInputs(next) }).catch(report)
    // The selected models and X union are refreshed as one pinned preview.
  }, [report, targets.join('|')])

  const formulaRequired = Boolean(context?.selected.some((item) => item.formulaRequirement === 'REQUIRED'))
  const formulaMaterialKey = useMemo(() => context?.materials?.map((item) => item.materialId).join('|') ?? '', [context?.materials])
  useEffect(() => {
    if (!formulaRequired || !formulaMaterialKey || !context?.materials?.length) return
    setFormula((rows) => {
      const existing = new Map(rows.filter((row) => row.materialId).map((row) => [row.materialId!, row]))
      return context.materials.map((material) => {
        const row = existing.get(material.materialId)
        return row ?? { key: generateUUID(), materialId: material.materialId, ratio: 0, unit: '%' }
      })
    })
    setComplete(false)
  }, [context?.materials, formulaMaterialKey, formulaRequired])

  const total = useMemo(() => formula.reduce((sum, item) => sum + (item.ratio ?? 0), 0), [formula])
  const incompleteRows = useMemo(() => formulaRequired ? formula.filter((item) => !item.materialId || item.ratio == null) : [], [formula, formulaRequired])
  const negativeRows = useMemo(() => formula.filter((item) => item.ratio != null && Number(item.ratio) < 0), [formula])
  const requestFingerprint = useMemo(() => JSON.stringify({ targets, formula, inputs, complete }), [complete, formula, inputs, targets])
  useEffect(() => { setResult(undefined) }, [requestFingerprint])

  const stopPolling = useCallback(() => { if (pollRef.current) window.clearTimeout(pollRef.current); pollRef.current = undefined }, [])
  const poll = useCallback(async (id: string) => {
    try {
      const value = await predictionApi.record(id)
      if (isRunning(value)) { pollRef.current = window.setTimeout(() => void poll(id), value.pollAfterMs); return }
      setResult(value);setRunningRecord(undefined);setPredicting(false);localStorage.removeItem('r07-running-prediction')
    } catch (error) {
      setPredicting(false)
      if (isModelVersionChanged(error)) {
        void message.warning('模型版本已更新，页面将刷新，请重新提交预测')
        window.setTimeout(() => window.location.reload(), 0)
        return
      }
      report(error)
    }
  }, [report])
  useEffect(() => { const id=localStorage.getItem('r07-running-prediction');if(id){setRunningRecord(id);setPredicting(true);void poll(id)}return stopPolling }, [poll, stopPolling])

  const predict = async () => {
    if (!targets.length) { void message.warning('请至少选择一个预测目标'); return }
    if (formulaRequired && negativeRows.length) { void message.warning(`有 ${negativeRows.length} 项材料比例为负数，请先修正`); return }
    if (formulaRequired && incompleteRows.length) { void message.warning('存在空白材料比例：空白表示未知，请补充；明确未使用请填0'); return }
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
    } catch(error){
      setPredicting(false)
      if (isModelVersionChanged(error)) {
        void message.warning('模型版本已更新，页面将刷新，请重新提交预测')
        window.setTimeout(() => window.location.reload(), 0)
        return
      }
      report(error)
    }
  }

  const updateFormula=(key:string,patch:Partial<FormulaRow>)=>{setComplete(false);setFormula((rows)=>rows.map((row)=>row.key===key?{...row,...patch}:row))}
  const selectedById=new Map(context?.selected.map((item)=>[item.targetId,item]))
  const selectedTargetLabels = targets.map((id) => catalog?.targets.find((item) => item.targetId === id)).filter(Boolean).map((target) => targetOptionLabel(target!))
  const registeredTargets = catalog?.targets.filter((item) => item.registered) ?? []
  const targetOptionGroups = useMemo(() => {
    const customerNames = new Set(['修复速度', '涂层伸长率', '断裂伸长率', '拉伸强度', '剥离力', '弯曲韧性', '耐酸雨', '耐溶剂', '耐防晒霜', '耐芥末酱发黄'])
    const toOption = (item: (typeof orderedTargets)[number]) => ({
      value: targetOptionLabel(item),
      label: `${targetOptionLabel(item)}${item.registered ? ' · 已登记，数据不足' : item.formalPredictionAvailable ? '' : ' · 暂不可预测'}`,
      disabled: item.targetStatus === 'RETIRED' || !item.formalPredictionAvailable,
    })
    const customer = orderedTargets.filter((item) => item.code.startsWith('CUSTOMER.TEST.') && customerNames.has(item.name))
    const available = orderedTargets.filter((item) => !customer.includes(item) && item.formalPredictionAvailable)
    const unavailable = orderedTargets.filter((item) => !customer.includes(item) && !item.formalPredictionAvailable)
    return [
      { label: '客户测试指标（已训练）', options: customer.map(toOption) },
      { label: '其他可预测目标', options: available.map(toOption) },
      { label: '暂不可预测目标', options: unavailable.map(toOption) },
    ].filter((group) => group.options.length)
  }, [orderedTargets])
  const canPredict=Boolean(targets.length&&context?.selected.some((item)=>item.available)&&!context?.configurationConflicts?.length&&(!formulaRequired||(complete&&!incompleteRows.length)))

  return <main className="performance-page">
    <header className="performance-header"><div><Typography.Title level={2}>性能预测</Typography.Title><Typography.Text>输入准备实验的配方与条件，同时预测多个性能。</Typography.Text></div><div className="formal-mark"><SafetyCertificateOutlined /><span>正式模型结果</span></div></header>

    <section className="prediction-gates">
      <div className="section-title"><span>选择预测目标</span><small>每个性能独立检查模型、输入、材料和适用范围</small></div>
      <Select mode="multiple" value={selectedTargetLabels} onChange={(labels)=>{const ids=labels.map((label)=>orderedTargets.find((item)=>targetOptionLabel(item)===label)?.targetId).filter((id): id is string => Boolean(id));setTargets(ids);setComplete(false)}} loading={loading} placeholder="按性能名称选择，可多选" maxTagCount="responsive" optionFilterProp="label" options={targetOptionGroups} />
      <RegisteredTargetsNotice targets={registeredTargets} />
      {targets.length>0&&<div className="gate-grid">{targets.map((id)=>{const item=selectedById.get(id);const base=catalog?.targets.find((target)=>target.targetId===id);const available=item?.available;const reasons=item?.unavailableReasons??base?.unavailableReasons??[];return <div key={id} className={`gate-card ${available?'ready':'blocked'}`}><div>{available?<CheckCircleFilled />:<CloseCircleFilled />}<strong>{item?.name??base?.name??'未知目标'}</strong><Tag>{resultTypeLabels[(item?.valueType??base?.valueType) as keyof typeof resultTypeLabels]}</Tag></div><span>{available?'正式模型、输入方案和策略已固定':reasons.map((reason)=>unavailableLabels[reason]??reason).join('；')}</span></div>})}</div>}
      {!!context?.configurationConflicts?.length&&<Alert type="error" showIcon message="所选模型对同一输入字段的类型或单位要求冲突，请调整目标组合" />}
    </section>

    <div className="prediction-workspace">
      <section className="prediction-inputs">
        <div className="panel-heading"><div><ExperimentOutlined /><span>配方与实验条件</span></div><small>所有用量均按实际值提交</small></div>
        {formulaRequired?<Card size="small" title="配方组成" extra={<span className="formula-total">合计 {total.toFixed(2)}%</span>}>
          <div className="formula-list">{formula.map((row,index)=>{const invalid=!row.materialId||row.ratio==null;return <div className={`formula-row ${invalid?'invalid':''}`} key={row.key}><b>{index+1}</b><Select status={!row.materialId?'error':undefined} showSearch optionFilterProp="label" value={row.materialId} onChange={(materialId)=>updateFormula(row.key,{materialId})} placeholder="选择材料" options={context?.materials.map((item)=>({value:item.materialId,label:materialLabel(item)}))}/><InputNumber status={row.ratio==null?'error':undefined} value={row.ratio} onChange={(ratio)=>updateFormula(row.key,{ratio:ratio??undefined})} min={0} precision={4} placeholder="空白表示未知，0表示未使用" addonAfter={row.unit}/><Button type="text" danger disabled={formula.length===1} onClick={()=>{setComplete(false);setFormula((rows)=>rows.filter((item)=>item.key!==row.key))}}>删除</Button></div>})}</div>
          <Typography.Text type="secondary" className="formula-hint">系统已列出当前模型支持的材料；明确未使用请填 0，空白表示未知，不能直接参与预测。合计不是100%时，系统会按相对比例统一归一化到100%，原始比例仍会保留。</Typography.Text>
          <Button type="dashed" block icon={<PlusOutlined />} onClick={()=>{setComplete(false);setFormula((rows)=>[...rows,{key:generateUUID(),unit:'%'}])}}>添加材料</Button>
          {!!negativeRows.length&&<Alert className="formula-warning" type="error" showIcon message="发现负数比例：材料比例不能小于0，请先修正"/>}
          {!!incompleteRows.length&&<Alert className="formula-warning" type="warning" showIcon message="存在空白比例：空白表示未知，请补充；0 表示明确未使用"/>}
          {!negativeRows.length&&!incompleteRows.length&&Math.abs(total-100)>0.00001&&<Alert className="formula-warning" type="info" showIcon message={`当前合计 ${total.toFixed(2)}%，系统将按相对比例归一化到100%`}/>}
          <Checkbox checked={complete} onChange={(event)=>setComplete(event.target.checked)}>已录入全部配方成分</Checkbox>
        </Card>:<Card size="small"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="所选模型不使用配方组成，可直接填写实验前条件"/></Card>}
        <Card size="small" title="实验前条件" className="condition-card">
          {!context?.requiredInputs.length?<Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="选择可用目标后显示模型需要的条件"/>:<div className="condition-groups">{(['PROCESS','CONDITION','OTHER'] as const).map((group)=><div key={group} className="condition-group"><div className="condition-group-title">{inputGroupLabels[group]}</div><div className="condition-grid">{context.requiredInputs.filter((field)=>field.inputGroup===group || (!field.inputGroup&&group==='OTHER')).map((field)=><label key={field.code}>
            <span>{field.name||field.sourceLabel||inputFieldLabels[field.code]||'实验条件'}{field.required&&<em>*</em>} {field.unit&&<small>{field.unit}</small>}</span>
            {field.valueType==='NUMBER'?<InputNumber value={inputs[field.code] as number|undefined} onChange={(value)=>setInputs((old)=>({...old,[field.code]:value}))} />:field.valueType==='BOOLEAN'?<Select value={inputs[field.code] as boolean|undefined} onChange={(value)=>setInputs((old)=>({...old,[field.code]:value}))} options={[{value:true,label:'是'},{value:false,label:'否'}]}/>:field.valueType==='CATEGORY'?<FriendlyCategorySelect field={field} value={inputs[field.code]} onChange={(value)=>setInputs((old)=>({...old,[field.code]:value}))}/>:<Input value={inputs[field.code] as string|undefined} onChange={(event)=>setInputs((old)=>({...old,[field.code]:event.target.value||undefined}))} />}
            <small className="input-source-hint">{field.requiredByTargets?.length ? `用于：${field.requiredByTargets.map((target)=>target.targetName).join('、')}` : ''}</small>
          </label>)}</div></div>)}</div>}
          {context?.selected.some((target)=>Object.keys(target.fixedConditions??{}).length>0)&&<Alert type="info" showIcon message="测试方法、阶段和固定条件已按各目标模型固定" description={context.selected.filter((target)=>Object.keys(target.fixedConditions??{}).length>0).map((target)=>`${target.name}：${Object.values(target.fixedConditions??{}).filter(Boolean).map(fixedConditionLabel).join(' · ')}`).join('；')} />}
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
    <Drawer open={Boolean(evidence)} onClose={()=>setEvidence(undefined)} width={560} title="预测依据">{evidence&&<div className="evidence-drawer"><p>该结果使用当前已确认的实验数据和适用范围规则计算。模型版本、训练数据和输入条件均在本次提交时固定。</p><dl><dt>结果状态</dt><dd>已完成</dd><dt>适用范围</dt><dd>{evidence.applicability.status==='IN_DOMAIN'?'适用范围内':'接近适用边界'}</dd><dt>可信度</dt><dd>{trust[evidence.quality?.level]?.text??'待确认'}</dd></dl><p className="evidence-note">如需查看详细技术记录，请联系管理员。</p></div>}</Drawer>
  </main>
}
