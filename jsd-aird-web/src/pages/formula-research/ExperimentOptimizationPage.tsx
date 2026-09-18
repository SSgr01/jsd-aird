/* eslint-disable @typescript-eslint/no-explicit-any, @typescript-eslint/no-misused-promises */
import { ArrowRightOutlined, CheckCircleOutlined, ExperimentOutlined, LoadingOutlined, ReloadOutlined, RocketOutlined } from '@ant-design/icons'
import { Alert, App, Button, Card, Checkbox, Col, DatePicker, Descriptions, Divider, Empty, Input, InputNumber, List, Modal, Progress, Row, Segmented, Select, Space, Table, Tag, Typography } from 'antd'
import dayjs from 'dayjs'
import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { optimizationApi, type BaselineType, type DraftLink, type OptimizationBaseline, type OptimizationContext, type OptimizationRun, type OptimizationTarget } from '@/services/ai-rnd/optimization-api'
import { listCategories, type Category } from '@/services/experiments/experiment-api'
import { generateUUID } from '@/utils/uuid'
import './experiment-optimization.css'
import './experiment-optimization-prototype.css'

const { Title, Text, Paragraph } = Typography
const sourceLabel: Record<BaselineType,string> = { EXPERIMENT_VERSION: '已完成实验', DATA_SAMPLE_REVISION: '数据中心样本', RESEARCH_CANDIDATE: '配方预测候选' }
const strategyLabel: Record<string,string> = { CONTROL: '原样对照', CONSERVATIVE: '保守改进', BALANCED: '多目标平衡', EXPLORATORY: '受控探索' }
const strategyReasonLabel: Record<string,string> = {
  MANDATORY_GOAL_NOT_MET: '候选没有达到全部必达目标',
  NO_IN_DOMAIN_CANDIDATE: '没有同时满足目标且处于模型适用域内的候选',
  NO_DISTINCT_FEASIBLE_CANDIDATE: '没有可与已选方案区分的可行候选',
  CONTROL_BASELINE_UNAVAILABLE: '基线评分未完成',
}
const stageLabel: Record<string,string> = { QUEUED: '等待执行', BASELINE_CHECK: '检查基线', BUILDING_SPACE: '构建空间', STRATEGY_SELECTION: '模型评估与策略选择', SUCCEEDED: '整理完成', FAILED: '执行失败' }
const typeLabel: Record<string,string> = { CONTINUOUS: '连续值', ORDINAL: '序数等级', BINARY: '二分类', CATEGORICAL: '多分类' }
const knownMaterialLabels: Record<string,string> = {
  '05060000-0000-0000-0000-000000000031': '树脂（RESIN）',
  '05060000-0000-0000-0000-000000000032': '助剂（ADDITIVE）',
}
const fallbackMaterialLabels = ['树脂', '助剂', '交联剂', '稀释剂']

function resultText(item: any) {
  const result = item?.result ?? item
  if (!result) return '—'
  if (result.resultType === 'CONTINUOUS') return `${Number(result.value).toFixed(2)}${result.unit ? ` ${result.unit}` : ''}`
  if (result.value !== undefined && result.value !== null) return `${result.value}${result.unit ? ` ${result.unit}` : ''}`
  if (result.label) return result.probability == null ? result.label : `${result.label} · ${(Number(result.probability) * 100).toFixed(0)}%`
  if (result.predictedClass) return result.predictedClass
  return '已记录'
}

const inputLabel: Record<string, string> = {
  substrate: '基材', substrateThickness: '基材厚度', temperature: '环境温度', humidity: '环境湿度',
  coatingSolids: '涂料固含', uvEnergy: 'UV能量', UVA: 'UVA光强', uva: 'UVA光强',
  applicationMethod: '施工方式', curingSource: '固化方式', testStage: '测试阶段',
}
const friendlyInputLabel = (code: string) => {
  if (inputLabel[code]) return inputLabel[code]
  const normalized = code.replace(/[-_]/g, '').toLowerCase()
  if (normalized.includes('substrate')) return '基材'
  if (normalized.includes('applicator') || normalized.includes('wirebar')) return '涂布器/绕丝棒'
  if (normalized.includes('temperature') || normalized.includes('temp')) return '环境温度'
  if (normalized.includes('humidity')) return '环境湿度'
  if (normalized.includes('uvenergy')) return 'UV能量'
  if (normalized.includes('uvintensity') || normalized.includes('uva')) return 'UVA光强'
  if (normalized.includes('coatingsolids') || normalized.includes('solidcontent')) return '涂料固含'
  if (normalized.includes('applicationmethod')) return '施工方式'
  if (normalized.includes('curingsource')) return '固化光源'
  if (/^[0-9a-f]{8,}$/i.test(code)) return '其他实验条件'
  return code.replace(/^[a-z]+:/i, '').replace(/[_-]+/g, ' ')
}
const resultLabel: Record<string, string> = { SYN_HARDNESS: '铅笔硬度', GLOSS_60: '60°光泽', UV_DRY: 'UV表干' }
const formatPercent = (value: unknown) => { const number = Number(value); return Number.isFinite(number) ? `${number.toFixed(1).replace(/\.0$/, '')}%` : '—' }

function baselineResults(results: any): any[] {
  const values = Array.isArray(results) ? results : Array.isArray(results?.targets) ? results.targets : (results && typeof results === 'object' ? [results] : [])
  return values.filter((item: any) => {
    const result = item?.result ?? item
    const code = String(item?.targetCode ?? result?.targetCode ?? item?.code ?? result?.code ?? '')
    const name = String(item?.targetName ?? result?.targetName ?? item?.name ?? result?.name ?? '')
    if (/^(STRUCT:|SYNTHETIC|R07|R10|TEST)/i.test(`${code} ${name}`)) return false
    return Boolean(item?.targetName || item?.targetCode || result?.targetName || result?.targetCode || result?.resultType || result?.unit || result?.label || result?.predictedClass)
  })
}

function visibleBaselineInputs(inputs: Record<string, unknown> | undefined) {
  return Object.entries(inputs ?? {}).filter(([code, value]) => {
    if (value === null || value === undefined || value === '') return false
    return !/^(STRUCT:|SYNTHETIC|R07|R10|TEST|[0-9a-f]{24,})/i.test(code)
  }).slice(0, 8)
}

export function ExperimentOptimizationPage() {
  const { message } = App.useApp(); const navigate = useNavigate()
  const [sourceType,setSourceType]=useState<BaselineType>('EXPERIMENT_VERSION'); const [keyword,setKeyword]=useState('')
  const [baselines,setBaselines]=useState<OptimizationBaseline[]>([]); const [baseline,setBaseline]=useState<OptimizationBaseline>()
  const [context,setContext]=useState<OptimizationContext>(); const [targetIds,setTargetIds]=useState<string[]>([])
  const [goals,setGoals]=useState<Record<string,{ operator:string; value:any; minimumProbability?:number }>>({})
  const [targetTotal,setTargetTotal]=useState(100); const [variables,setVariables]=useState<Record<string,{ enabled:boolean; minimum:number; maximum:number }>>({})
  const [inputVariables,setInputVariables]=useState<Record<string,{ enabled:boolean; minimum?:number; maximum?:number }>>({})
  const [runId,setRunId]=useState<string>(); const [run,setRun]=useState<OptimizationRun>(); const [submitting,setSubmitting]=useState(false)
  const [selectedCandidates,setSelectedCandidates]=useState<string[]>([]); const [draftOpen,setDraftOpen]=useState(false); const [categories,setCategories]=useState<Category[]>([]); const [categoryId,setCategoryId]=useState<string>(); const [planDate,setPlanDate]=useState(dayjs()); const [links,setLinks]=useState<DraftLink[]>([]); const [feedback,setFeedback]=useState<any>(); const [templateVersionId,setTemplateVersionId]=useState<string>()

  const loadBaselines=async(type=sourceType,search=keyword)=>{try{const page=await optimizationApi.baselines(type,search);setBaselines(page.items)}catch(e){message.error(e instanceof Error?e.message:'无法读取优化基线')}}
  useEffect(()=>{void loadBaselines();listCategories().then(setCategories).catch(()=>undefined);const remembered=new URLSearchParams(location.search).get('runId')??sessionStorage.getItem('r09.optimizationRunId')??undefined;if(remembered)setRunId(remembered)},[])
  useEffect(()=>{void loadBaselines(sourceType,keyword);setBaseline(undefined);setContext(undefined)},[sourceType])
  useEffect(()=>{if(!runId)return;let active=true;const poll=async()=>{try{const value=await optimizationApi.run(runId);if(!active)return;setRun(value);if(!baseline&&value.frozenBaseline){const b=value.frozenBaseline;const restored={type:b.type,id:b.entityId,versionId:b.versionId,title:b.title,sourceLabel:'冻结基线',actualTotal:b.formula?.recordedTotal??0,materials:b.formula?.components??[],inputs:b.inputs??{},results:b.results??{},contentHash:'',updatedAt:value.updatedAt} as OptimizationBaseline;setBaseline(restored);setTargetTotal(value.frozenRequest?.targetTotal??b.formula?.recordedTotal??100);optimizationApi.context(restored.type,restored.id).then(setContext).catch(()=>undefined)}if(!['SUCCEEDED','FAILED','CANCELLED'].includes(value.executionStatus))window.setTimeout(poll,1000);else{optimizationApi.links(runId).then(setLinks).catch(()=>undefined);optimizationApi.feedback(runId).then(setFeedback).catch(()=>undefined)}}catch{if(active)window.setTimeout(poll,1600)}};void poll();return()=>{active=false}},[runId])
  const selectBaseline=async(item:OptimizationBaseline)=>{setBaseline(item);setRun(undefined);setRunId(undefined);setTargetIds([]);setTemplateVersionId(undefined);setTargetTotal(item.actualTotal);setVariables(Object.fromEntries((item.materials??[]).map((m:any)=>{const ratio=Number(m.ratio??0);return [m.materialId,{enabled:true,minimum:Math.max(0,ratio-5),maximum:ratio+5}]})));try{const detail=await optimizationApi.context(item.type,item.id);setContext(detail);setTemplateVersionId(detail.experimentTemplates?.[0]?.templateVersionId);const resolved=(detail as any).baseline??(detail as any).frozenBaseline;if(resolved&&typeof resolved==='object'){setBaseline(current=>({...current,...resolved,results:resolved.results??current?.results,materials:resolved.materials??current?.materials,inputs:resolved.inputs??current?.inputs,actualTotal:resolved.actualTotal??current?.actualTotal}))}}catch(e){message.error(e instanceof Error?e.message:'无法读取基线条件')}}
  const selectTargets=async(ids:string[])=>{if(!baseline)return;setTargetIds(ids);try{const detail=await optimizationApi.context(baseline.type,baseline.id,ids);setContext(detail);setGoals((current)=>Object.fromEntries(ids.map(id=>{const t=detail.selected.find(x=>x.targetId===id);return [id,current[id]??{operator:t?.valueType==='CONTINUOUS'?'AT_LEAST':'MATCH',value:t?.valueType==='CONTINUOUS'?0:(t?.classes?.[0]??''),minimumProbability:t?.valueType==='CONTINUOUS'?undefined:0.6}]})));setInputVariables(Object.fromEntries(detail.adjustableInputs.map((f:any)=>[f.code,{enabled:false,minimum:typeof detail.frozenBaseline?.inputs?.[f.code]==='number'?detail.frozenBaseline.inputs[f.code]:undefined,maximum:typeof detail.frozenBaseline?.inputs?.[f.code]==='number'?detail.frozenBaseline.inputs[f.code]:undefined}])))}catch(e){message.error(e instanceof Error?e.message:'无法读取模型条件')}}
  const selectedTargets=useMemo(()=>targetIds.map(id=>context?.selected.find(t=>t.targetId===id)??context?.targets.find(t=>t.targetId===id)).filter(Boolean) as OptimizationTarget[],[context,targetIds])
  const materialLabel=(material:any,index:number)=>{
    const candidate=material?.name||context?.materialIntersection?.find((entry:any)=>entry.materialId===material?.materialId)?.name||material?.materialCode||material?.code
    return knownMaterialLabels[material?.materialId]||(candidate&&!/^[0-9a-f]{8}-[0-9a-f-]{27,}$/i.test(String(candidate))?candidate:null)||fallbackMaterialLabels[index]||`材料 ${index+1}`
  }
  const submit=async()=>{if(!baseline||!context){message.warning('请先选择基线');return}if(!targetIds.length){message.warning('请至少选择一个优化目标');return}const variableMaterials=(baseline.materials??[]).filter((m:any)=>variables[m.materialId]?.enabled).map((m:any)=>({materialId:m.materialId,minimum:variables[m.materialId]!.minimum,maximum:variables[m.materialId]!.maximum}));const variableInputs=context.adjustableInputs.filter(f=>inputVariables[f.code]?.enabled).map(f=>({code:f.code,valueType:f.valueType,availabilityTiming:f.availabilityTiming,minimum:inputVariables[f.code]!.minimum,maximum:inputVariables[f.code]!.maximum}));if(!variableMaterials.length&&!variableInputs.length){message.warning('请至少允许调整一个材料或实验前条件');return}setSubmitting(true);try{const accepted=await optimizationApi.submit({baselineRef:{type:baseline.type,id:baseline.id,versionId:baseline.versionId,contentHash:baseline.contentHash||undefined},typedGoals:selectedTargets.map(t=>({targetId:t.targetId,valueType:t.valueType,expectedModelVersionId:t.modelVersionId,operator:goals[t.targetId]?.operator??'MATCH',value:goals[t.targetId]?.value??'',mandatory:true,weight:1,minimumProbability:goals[t.targetId]?.minimumProbability})),targetTotal,variableMaterials,variableInputs,constraints:{},candidateCount:4},generateUUID());setRunId(accepted.runId);sessionStorage.setItem('r09.optimizationRunId',accepted.runId);history.replaceState({},'',`${location.pathname}?runId=${accepted.runId}`)}catch(e){message.error(e instanceof Error?e.message:'实验优化提交失败')}finally{setSubmitting(false)}}
  const createDrafts=async()=>{if(!runId||!selectedCandidates.length||!categoryId){message.warning('请选择方案和实验分类');return}if(!templateVersionId){message.warning('请选择客户实验模板');return}try{const result=await optimizationApi.createDrafts(runId,{candidateIds:selectedCandidates,categoryId,plannedExperimentDate:planDate.format('YYYY-MM-DD'),templateVersionId},generateUUID());setLinks(result.experiments);setDraftOpen(false);message.success(`已创建 ${result.experiments.length} 条实验草稿`)}catch(e){message.error(e instanceof Error?e.message:'创建实验草稿失败')}}
  const refreshFeedback=async()=>{if(runId)setFeedback(await optimizationApi.feedback(runId))}

  return <div className="optimization-page">
    <header className="optimization-header"><div><Text className="optimization-eyebrow">AI研发助手 · 实验优化</Text><Title level={2}>设计下一轮实验</Title><Paragraph type="secondary">从一个已完成基线出发，先确认当前表现，再比较下一轮的对照、保守、平衡和探索方案。</Paragraph></div><Button type="primary" icon={<RocketOutlined />} loading={submitting} disabled={!baseline||!targetIds.length} onClick={submit}>生成下一轮建议</Button></header>
    <div className="prototype-note"><Text strong>基线 → 目标与变量 → 下一轮建议</Text><Text type="secondary"> 生成的方案需要用户选择后才会创建实验草稿，实测结果仍在实验本中回灌。</Text></div>
    {baseline&&<Card className="summary-strip"><div className="sum-item"><span>基材</span><b>{String(baseline.inputs?.substrate??'—')}</b></div><div className="sum-item"><span>UV能量</span><b>{String(baseline.inputs?.uvEnergy??'—')}</b></div><div className="sum-item"><span>UVA光强</span><b>{String(baseline.inputs?.uva??baseline.inputs?.UVA??'—')}</b></div><div className="sum-item"><span>配方总量</span><b>{formatPercent(baseline.actualTotal)}</b></div><div className="sum-item"><span>状态</span><b>已完成</b></div></Card>}
    <Row gutter={[18,18]}>
      <Col xs={24} xl={8}><Card title="选择起始基线" className="optimization-card"><Segmented block value={sourceType} onChange={v=>setSourceType(v as BaselineType)} options={(Object.keys(sourceLabel) as BaselineType[]).map(v=>({value:v,label:sourceLabel[v]}))}/><Input.Search className="baseline-search" value={keyword} onChange={e=>setKeyword(e.target.value)} onSearch={()=>loadBaselines()} placeholder="搜索实验、样本或候选"/><List className="baseline-list" dataSource={baselines} locale={{emptyText:'当前没有可用基线'}} renderItem={item=><List.Item className={baseline?.id===item.id?'selected':''} onClick={()=>selectBaseline(item)}><List.Item.Meta title={<Space><Text strong>{item.title}</Text><Tag>{formatPercent(item.actualTotal)}</Tag></Space>} description={`${item.sourceLabel} · ${item.materials?.length??0} 种材料`}/><ArrowRightOutlined/></List.Item>}/></Card></Col>
      <Col xs={24} xl={16}>{!baseline?<Card className="optimization-card"><Empty description="先从左侧选择一个基线"/></Card>:<>
        {baseline&&<Card title="当前实验表现" className="optimization-card baseline-performance" extra={<Tag color="green">已完成实验</Tag>}><Row gutter={[12,12]}>{baselineResults(baseline.results).length ? baselineResults(baseline.results).map((item:any,index:number)=><Col xs={24} md={12} key={`${item.targetId??item.targetCode??'result'}-${index}`}><div className="performance-item"><Text type="secondary">{item.targetName||resultLabel[item.targetCode]||'测试结果'}</Text><Text strong>{resultText(item)}</Text></div></Col>) : <Col span={24}><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该基线暂时没有可比的结构化测试结果"/></Col>}</Row><Divider orientation="left">实验条件</Divider><Row gutter={[12,12]}>{visibleBaselineInputs(baseline.inputs).map(([code,value])=><Col xs={12} md={8} key={code}><div className="condition-item"><Text type="secondary">{friendlyInputLabel(code)}</Text><Text strong>{String(value)}</Text></div></Col>)}</Row></Card>}
        <Card title="设计下一轮实验" className="optimization-card"><div className="section-caption">优化目标 Y <Text type="secondary">来自已发布的预测目标</Text></div><Select mode="multiple" className="full-control" value={targetIds} onChange={selectTargets} placeholder="选择本轮要优化的性能" options={(context?.targets??[]).map(t=>({value:t.targetId,label:`${t.name} · ${typeLabel[t.valueType]}`,disabled:!t.available}))}/><div className="optimization-goals">{selectedTargets.map(t=><Card size="small" key={t.targetId}><Space className="goal-line"><Tag color="blue">{typeLabel[t.valueType]}</Tag><Text strong>{t.name}</Text><Select value={goals[t.targetId]?.operator} onChange={v=>setGoals(g=>({...g,[t.targetId]:{...(g[t.targetId]??{operator:'MATCH',value:''}),operator:v}}))} options={(t.valueType==='CONTINUOUS'?['AT_LEAST','AT_MOST','MAXIMIZE','MINIMIZE']:['MATCH']).map(v=>({value:v,label:{AT_LEAST:'至少达到',AT_MOST:'不超过',MAXIMIZE:'尽量提高',MINIMIZE:'尽量降低',MATCH:'指定等级/类别'}[v]}))}/>{t.valueType==='CONTINUOUS'?<InputNumber value={goals[t.targetId]?.value} onChange={v=>setGoals(g=>({...g,[t.targetId]:{...(g[t.targetId]??{operator:'MATCH',value:0}),value:v??0}}))}/>:<Select value={goals[t.targetId]?.value} onChange={v=>setGoals(g=>({...g,[t.targetId]:{...(g[t.targetId]??{operator:'MATCH',value:''}),value:v}}))} options={(t.classes??[]).map(v=>({value:v,label:v}))}/>}</Space></Card>)}</div></Card>
        <Card title="允许调整的条件" className="optimization-card" extra={<Space><Text>优化方案总量</Text><InputNumber value={targetTotal} min={0.01} onChange={v=>setTargetTotal(v??baseline.actualTotal)} addonAfter="%"/></Space>}><Alert type="info" showIcon message={`原实验保持 ${formatPercent(baseline.actualTotal)}；调整方案使用 ${formatPercent(targetTotal)}`}/><Divider orientation="left">配方材料</Divider><Table size="small" pagination={false} rowKey={(m:any)=>m.materialId} dataSource={baseline.materials??[]} columns={[{title:'允许调整',render:(_:any,m:any)=><Checkbox checked={variables[m.materialId]?.enabled} onChange={e=>setVariables(v=>({...v,[m.materialId]:{...v[m.materialId],enabled:e.target.checked}}))}/>},{title:'材料',render:(_:any,m:any,index:number)=>materialLabel(m,index)},{title:'原实验比例',render:(_:any,m:any)=>formatPercent(m.ratio)},{title:'可调整比例范围',render:(_:any,m:any)=><Space><InputNumber disabled={!variables[m.materialId]?.enabled} value={variables[m.materialId]?.minimum} onChange={x=>setVariables(v=>({...v,[m.materialId]:{...v[m.materialId],minimum:x??0}}))}/><Text>至</Text><InputNumber disabled={!variables[m.materialId]?.enabled} value={variables[m.materialId]?.maximum} onChange={x=>setVariables(v=>({...v,[m.materialId]:{...v[m.materialId],maximum:x??0}}))}/></Space>} ]}/>{Boolean(context?.adjustableInputs?.length)&&<><Divider orientation="left">可调整的实验前条件</Divider><Row gutter={[12,12]}>{context?.adjustableInputs.map(f=><Col span={12} key={f.code}><Card size="small"><Checkbox checked={inputVariables[f.code]?.enabled} onChange={e=>setInputVariables(v=>({...v,[f.code]:{...(v[f.code]??{enabled:false}),enabled:e.target.checked}}))}>{f.name||friendlyInputLabel(f.code)}</Checkbox><Space className="input-range"><InputNumber disabled={!inputVariables[f.code]?.enabled} value={inputVariables[f.code]?.minimum} onChange={v=>setInputVariables(values=>({...values,[f.code]:{...(values[f.code]??{enabled:true}),minimum:v??undefined}}))}/><Text>至</Text><InputNumber disabled={!inputVariables[f.code]?.enabled} value={inputVariables[f.code]?.maximum} onChange={v=>setInputVariables(values=>({...values,[f.code]:{...(values[f.code]??{enabled:true}),maximum:v??undefined}}))}/></Space></Card></Col>)}</Row></>}</Card>
      </>}</Col>
    </Row>
    {run&&<Card className="optimization-card run-panel" title="下一轮建议" extra={<Space>{run.outcomeStatus&&<Tag color={run.outcomeStatus==='SUCCEEDED'?'green':'gold'}>{run.outcomeStatus==='SUCCEEDED'?'4种策略已生成':run.outcomeStatus==='PARTIAL'?'部分策略可行':'没有可行方案'}</Tag>}<Button icon={<ReloadOutlined/>} onClick={()=>runId&&optimizationApi.run(runId).then(setRun)}>刷新</Button></Space>}><Progress percent={run.progress} status={run.executionStatus==='FAILED'?'exception':'active'} format={()=>stageLabel[run.currentStage??run.executionStatus]??run.currentStage}/>{run.error&&<Alert type="error" showIcon message={run.error.message||run.error.code}/>} {run.missingStrategies?.length>0&&run.executionStatus==='SUCCEEDED'&&<Alert type="warning" showIcon message={`未生成：${run.missingStrategies.map(x=>strategyLabel[x]||x).join('、')}`} description={<Space direction="vertical" size={2}><Text>系统保留真实数量，没有复制其他方案凑数。</Text>{run.missingStrategies.map(strategy=><Text type="secondary" key={strategy}>{strategyLabel[strategy]||strategy}：{strategyReasonLabel[run.missingStrategyReasons?.[strategy]??""]??run.missingStrategyReasons?.[strategy]??"未找到可行候选"}</Text>)}</Space>}/>}<Row gutter={[14,14]} className="candidate-grid">{run.candidates.map(c=><Col xs={24} lg={12} key={c.id}><Card className={`strategy-card strategy-${c.strategy.toLowerCase()}`} title={<Space><Checkbox checked={selectedCandidates.includes(c.id)} onChange={e=>setSelectedCandidates(s=>e.target.checked?[...s,c.id]:s.filter(x=>x!==c.id))}/><Tag>{strategyLabel[c.strategy]}</Tag><Text strong>{c.title}</Text></Space>}><Descriptions size="small" column={1} items={[{key:'total',label:'配方总量',children:`${c.targetTotal}%`},{key:'distance',label:'相对基线变化',children:c.strategy==='CONTROL'?'完全一致':`${(c.baselineDistance*100).toFixed(1)}%`},{key:'domain',label:'适用域',children:c.applicability?.status||'模型内' } ]}/><Divider/><List size="small" dataSource={c.results?.targets??[]} renderItem={(r:any)=><List.Item><Text>{r.targetName||'预测目标'}</Text><Text strong>{resultText(r)}</Text></List.Item>}/>{c.risks?.map(r=><Tag color="gold" key={r}>{r}</Tag>)}</Card></Col>)}</Row>{run.executionStatus==='RUNNING'||run.executionStatus==='QUEUED'?<div className="run-wait"><LoadingOutlined/><Text>后台任务继续运行，刷新或重新登录后仍可恢复。</Text></div>:run.candidates.length?<Button type="primary" icon={<ExperimentOutlined/>} disabled={!selectedCandidates.length} onClick={()=>setDraftOpen(true)}>将选中方案创建为实验草稿</Button>:<Empty description="请调整目标或可变范围后重新运行"/>}</Card>}
    {links.length>0&&<Card className="optimization-card" title="实验草稿与实测回灌" extra={<Button onClick={refreshFeedback}>刷新实测结果</Button>}><List dataSource={links} renderItem={l=><List.Item actions={[<Button type="link" onClick={()=>navigate(l.href)}>打开实验本</Button>]}><List.Item.Meta avatar={<CheckCircleOutlined className="draft-ok"/>} title={l.experimentNo} description={`状态：${l.status}`}/></List.Item>}/>{feedback?.feedbackCount>0?<><Alert type="success" showIcon message={`已匹配 ${feedback.feedbackCount} 条预测与实测对照`}/><Table size="small" pagination={false} rowKey={(row:any,index)=>`${row.candidateId}-${row.targetId}-${index}`} dataSource={Array.isArray(feedback.candidates)?feedback.candidates:[]} columns={[{title:'实验',dataIndex:'experimentNo'},{title:'预测目标',dataIndex:'targetName'},{title:'预测结果',render:(_:unknown,row:any)=>resultText(row.predicted)},{title:'实测结果',render:(_:unknown,row:any)=>resultText(row.observed)},{title:'比较',render:(_:unknown,row:any)=>row.comparisonStatus==='COMPARABLE'?<Tag color="green">可比较</Tag>:<Tag color="gold">{row.reasonCode||'待补录'}</Tag>},{title:'误差',render:(_:unknown,row:any)=>row.metrics?.absoluteError!==undefined?Number(row.metrics.absoluteError).toFixed(3):'—'}]}/></>:<Alert type="info" showIcon message="实验完成并完成事实投影后，这里会显示逐Y预测与实测对照。"/>}</Card>}
    <Modal title="创建实验草稿" open={draftOpen} onCancel={()=>setDraftOpen(false)} onOk={createDrafts} okText="创建草稿"><Space direction="vertical" className="full-control"><Alert type="info" showIcon message={`将创建 ${selectedCandidates.length} 条草稿，使用客户实验模板的原始表格布局。`}/><Select value={templateVersionId} onChange={setTemplateVersionId} placeholder="选择客户实验模板" options={(context?.experimentTemplates??[]).map(t=>({value:t.templateVersionId,label:`${t.name} · 第${t.versionNo}版`}))}/><Select value={categoryId} onChange={setCategoryId} placeholder="选择实验分类" options={categories.filter(c=>c.active).map(c=>({value:c.id,label:c.name}))}/><DatePicker value={planDate} onChange={v=>v&&setPlanDate(v)} className="full-control"/></Space></Modal>
  </div>
}
