import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { App, Button, Card, Checkbox, Drawer, Empty, Form, Input, Modal, Progress, Select, Segmented, Space, Table, Tag, Tooltip, Typography } from 'antd'
import { CheckCircleOutlined, ClockCircleOutlined, ReloadOutlined } from '@ant-design/icons'
import { useAuthStore } from '@/stores/auth-store'
import { trainingApi } from '@/services/ai-rnd/training-api'
import type { ModelDetail, ModelVersion, TrainingJobDetail, TrainingJobSummary } from '@/services/ai-rnd/ai-rnd-types'
import { isModelVersionChanged } from '@/services/http/errors'
import { aiActionFeedback } from '@/services/ai-rnd/action-feedback'
import './model-management.css'

const modelStatus: Record<string, { text: string; color: string }> = {
  ACTIVE: { text: '使用中', color: 'green' }, CANDIDATE: { text: '待检查', color: 'blue' },
  PAUSED: { text: '已暂停', color: 'orange' }, RETIRED: { text: '历史版本', color: 'default' },
  REJECTED: { text: '未通过', color: 'red' },
}
const jobStatus: Record<string, { text: string; color: string }> = {
  QUEUED: { text: '等待开始', color: 'default' }, MATERIALIZING: { text: '准备训练数据', color: 'processing' },
  SNAPSHOT_VALIDATING: { text: '检查训练数据', color: 'processing' }, FOLDING: { text: '划分验证数据', color: 'processing' },
  TRAINING: { text: '训练中', color: 'processing' }, VALIDATING: { text: '验证中', color: 'processing' },
  SUCCEEDED: { text: '训练完成', color: 'green' }, FAILED: { text: '训练失败', color: 'red' }, CANCELLED: { text: '已取消', color: 'default' },
}
const valueType: Record<string, string> = { CONTINUOUS: '连续数值', ORDINAL: '有序等级', BINARY: '二分类', CATEGORICAL: '多分类' }
const metricLabel: Record<string, string> = { mae: '平均绝对误差', rmse: '均方根误差', r2: '拟合度', accuracy: '准确率', macroF1: '宏平均 F1', logLoss: '概率损失', gradeMae: '等级误差', plusMinusOneAccuracy: '相邻等级准确率', spearman: '等级相关性', residualStd: '残差标准差', candidateCount: '参与比较的模型数', intervalCoverage90: '90%预测区间覆盖率' }
const metricOriginalLabel: Record<string, string> = { r2: 'R²', mae: 'MAE', rmse: 'RMSE', residualStd: 'Residual Std', candidateCount: 'Candidate Count', intervalCoverage90: 'Interval Coverage (90%)', accuracy: 'Accuracy', macroF1: 'Macro-F1', logLoss: 'Log Loss', gradeMae: 'Grade MAE', plusMinusOneAccuracy: '±1 Accuracy', spearman: 'Spearman' }
const metricDescription: Record<string, string> = {
  r2: 'R²：模型能解释结果变化的程度，越接近1越好；接近0表示解释力有限。',
  mae: 'MAE：预测值与实际值平均相差多少，单位与预测目标相同，越小越好。',
  rmse: 'RMSE：会更重地惩罚较大的偏差，越小越好。',
  residualStd: '预测误差的波动程度，越小表示预测更稳定。',
  candidateCount: '本次参与比较的模型数量；数量越多比较越充分，1表示只有一个候选。',
  intervalCoverage90: '约90%的预测区间实际覆盖真实值的比例，接近90%较理想；过低可能区间过窄，过高可能区间过宽。',
  accuracy: '预测类别与实际类别相同的比例，越高越好，但要结合各类别数量一起看。',
  macroF1: '各类别F1分数的平均值，兼顾查准率和查全率，越高越好。',
  logLoss: '概率预测与实际结果的差距，越小越好。',
  gradeMae: '预测等级与实际等级平均相差多少级，越小越好。',
  plusMinusOneAccuracy: '预测结果与实际等级相差不超过一级的比例，越高越好。',
  spearman: '预测等级排序与实际排序的一致程度，越接近1越好。',
}
// Only metrics with a defined business meaning are shown to customers.  Build
// metadata such as `rows`, `features` or `durationMs` is not a validation
// result and must not be presented as one.
const customerMetricKeys = new Set(Object.keys(metricLabel))
const modelTypeLabel: Record<string, string> = {
  XGBOOST: '梯度提升树', LIGHTGBM: '轻量梯度提升树', RANDOM_FOREST: '随机森林',
  GAUSSIAN_PROCESS: '高斯过程', CATBOOST: '分类提升树', LOGISTIC_REGRESSION: '逻辑回归',
  ORDINAL_CUMULATIVE_LOGIT: '等级概率模型', DEVELOPMENT_KNN: '相似案例模型',
}
const modelTypeOriginalLabel: Record<string, string> = {
  XGBOOST: 'XGBoost', LIGHTGBM: 'LightGBM', RANDOM_FOREST: 'Random Forest', GAUSSIAN_PROCESS: 'Gaussian Process',
  CATBOOST: 'CatBoost', LOGISTIC_REGRESSION: 'Logistic Regression', ORDINAL_CUMULATIVE_LOGIT: 'Ordinal Cumulative Logit',
  DEVELOPMENT_KNN: 'Development KNN',
}
const modelTypeDescription: Record<string, string> = {
  XGBOOST: '梯度提升树：逐步修正预测误差，适合复杂的非线性关系。',
  LIGHTGBM: '轻量梯度提升树：训练速度较快，适合样本较多的非线性关系。',
  RANDOM_FOREST: '随机森林：综合多棵决策树，结果稳健，适合材料和工艺关系复杂的情况。',
  GAUSSIAN_PROCESS: '高斯过程：在预测结果之外估计不确定性，适合样本较少的情况。',
  CATBOOST: '分类提升树：对类别条件和非线性关系较友好。',
  LOGISTIC_REGRESSION: '逻辑回归：直接估计各类别概率，结果易解释。',
  ORDINAL_CUMULATIVE_LOGIT: '等级概率模型：利用等级的先后关系预测达到各等级的概率。',
  DEVELOPMENT_KNN: '相似案例模型：参考历史上最接近的实验，不等同于通用模型预测。',
}
const categoryLabel: Record<string, string> = {
  UVPU_APPLICATION_FORMULATION: 'UV/PU应用配方', GENERAL_DATA: '通用数据',
}
const stageLabel: Record<string, string> = {
  QUEUED: '等待开始', MATERIALIZING: '准备训练数据', SNAPSHOT_VALIDATING: '检查训练数据',
  FOLDING: '划分验证数据', TRAINING: '训练中', VALIDATING: '验证中',
  SUCCEEDED: '训练完成', FAILED: '训练失败', CANCELLED: '已取消', RUNNING: '运行中',
}
const releaseActionLabel: Record<string, string> = {
  REPLACE: '更新版本', ACTIVATE: '启用', PAUSE: '暂停', ROLLBACK: '回退', RETIRE: '归档', REJECT: '不采用',
}
const running = new Set(['QUEUED', 'MATERIALIZING', 'SNAPSHOT_VALIDATING', 'FOLDING', 'TRAINING', 'VALIDATING'])

function when(value?: string) { return value ? new Date(value).toLocaleString('zh-CN') : '尚未执行' }
const preferredMetrics: Record<string, string[]> = {
  CONTINUOUS: ['mae', 'rmse', 'r2'], ORDINAL: ['gradeMae', 'plusMinusOneAccuracy', 'spearman'],
  BINARY: ['macroF1', 'accuracy', 'logLoss'], CATEGORICAL: ['macroF1', 'accuracy', 'logLoss'],
}
const lowerIsBetter = new Set(['mae', 'rmse', 'gradeMae', 'logLoss'])
function primaryMetric(model: Pick<ModelVersion, 'metrics' | 'valueType'>) {
  const available = Object.entries(model.metrics).filter(([key, value]) => customerMetricKeys.has(key) && typeof value === 'number')
  const ordered = preferredMetrics[model.valueType] ?? []
  const entry = ordered.map((key) => available.find(([candidate]) => candidate === key)).find(Boolean) ?? available[0]
  if (!entry) return { value: '暂无验证指标', detail: '打开详情查看训练结果' }
  const [key, value] = entry
  const direction = lowerIsBetter.has(key) ? '越低越好' : '越高越好'
  return { value: `${metricOriginalLabel[key] ?? key} ${Number(value).toFixed(3)}`, detail: `${metricLabel[key] ?? '验证指标'} · ${direction}` }
}

function modelActionLabel(status: string) {
  if (status === 'CANDIDATE') return '检查结果'
  if (status === 'ACTIVE') return '查看模型'
  if (status === 'REJECTED') return '查看原因'
  if (status === 'RETIRED') return '查看历史'
  return '查看版本'
}

function modelStateHint(model: ModelVersion) {
  if (model.status === 'CANDIDATE') return '检查验证结果后再决定是否启用'
  if (model.status === 'ACTIVE') return '当前用于预测'
  if (model.status === 'PAUSED') return '已暂停，不用于新预测'
  if (model.status === 'REJECTED') return '未通过检查，可查看原因'
  return '历史版本，仅供查看'
}

function modelUsageGuidance(model: ModelVersion) {
  if (model.status === 'CANDIDATE') return model.productionEligible ? '检查验证结果后，再决定是否启用此版本。' : '此版本尚未达到启用条件，请查看验证结果和未通过原因。'
  if (model.status === 'ACTIVE') return '此版本当前用于预测；新的训练结果会保存为独立版本。'
  if (model.status === 'PAUSED') return '此版本已暂停，不会用于新的预测。'
  if (model.status === 'REJECTED') return '此版本未通过检查，可查看验证表现和版本记录了解原因。'
  return '这是历史版本，不会用于新的预测。'
}

function algorithmName(value: string) {
  const original = modelTypeOriginalLabel[value] ?? value
  const chinese = modelTypeLabel[value] ?? '综合预测方法'
  return <span className="algorithm-name"><strong>{original}</strong><span>{chinese}</span></span>
}

function orderedMetrics(metrics: Record<string, unknown>) {
  const preferred = ['r2', 'mae', 'rmse', 'residualStd', 'candidateCount', 'intervalCoverage90']
  const entries = Object.entries(metrics).filter(([key, value]) => typeof value === 'number' && customerMetricKeys.has(key))
  return [...preferred.filter((key) => entries.some(([entryKey]) => entryKey === key)).map((key) => entries.find(([entryKey]) => entryKey === key)!), ...entries.filter(([key]) => !preferred.includes(key))]
}

function metricValue(key: string, value: number) {
  if (key === 'candidateCount') return String(Math.round(value))
  return value.toFixed(3)
}

type ModelManagementPageProps = {
  embedded?: boolean
  focusModelId?: string
  focusTargetId?: string
  view?: 'models' | 'jobs'
  onViewChange?: (view: 'models' | 'jobs') => void
}

export function ModelManagementPage({ embedded = false, focusTargetId, focusModelId, view, onViewChange }: ModelManagementPageProps = {}) {
  const { message } = App.useApp()
  const canOperate = useAuthStore((state) => state.can('ai.training.operate'))
  const canPublish = useAuthStore((state) => state.can('ai.model.publish'))
  const [models, setModels] = useState<ModelVersion[]>([])
  const [jobs, setJobs] = useState<TrainingJobSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [keyword, setKeyword] = useState('')
  const [status, setStatus] = useState<string>()
  const [category, setCategory] = useState<string>()
  const [tab, setTab] = useState<'models' | 'jobs'>(view ?? 'models')
  const [modelDetail, setModelDetail] = useState<ModelDetail>()
  const [jobDetail, setJobDetail] = useState<TrainingJobDetail>()
  const [action, setAction] = useState<{ type: 'activate' | 'pause' | 'rollback'; model: ModelVersion }>()
  const [actionForm] = Form.useForm<{ reason: string }>()
  const focusedTargetRef = useRef<string>()
  const [showHistory, setShowHistory] = useState(false)
  const visibleModels = useMemo(() => {
    if (showHistory || status) return models
    const latest = new Map<string, number>()
    for (const model of models) latest.set(model.targetId, Math.max(latest.get(model.targetId) ?? 0, model.version))
    return models.filter((model) => model.status === 'ACTIVE' || model.version === latest.get(model.targetId))
  }, [models, showHistory, status])

  const report = useCallback((error: unknown) => { void message.error(aiActionFeedback(error, '操作未完成，请重试')) }, [message])
  const load = useCallback(async (quiet = false) => {
    if (!quiet) setLoading(true)
    try {
      const [modelPage, jobPage] = await Promise.all([
        trainingApi.models({ status, category, keyword: keyword || undefined, page: 1, size: 100 }),
        trainingApi.jobs({ keyword: keyword || undefined, page: 1, size: 100 }),
      ])
      setModels(modelPage.items); setJobs(jobPage.items)
    } catch (error) { report(error) } finally { if (!quiet) setLoading(false) }
  }, [category, keyword, report, status])

  useEffect(() => { void load() }, [load])
  const hasRunning = jobs.some((job) => running.has(job.status))
  useEffect(() => { if (!hasRunning) return; const timer = window.setInterval(() => void load(true), 3000); return () => window.clearInterval(timer) }, [hasRunning, load])
  useEffect(() => { if (view && view !== tab) setTab(view) }, [tab, view])

  const activeCount = models.filter((model) => model.status === 'ACTIVE').length
  const candidateCount = models.filter((model) => model.status === 'CANDIDATE').length
  const trainingCount = jobs.filter((job) => running.has(job.status)).length
  const categories = useMemo(() => [...new Set(models.map((model) => model.category).filter(Boolean))], [models])
  const openModel = async (row: ModelVersion) => { try { setModelDetail(await trainingApi.model(row.id)) } catch (error) { report(error) } }
  useEffect(() => {
    if (!focusTargetId || focusedTargetRef.current === (focusModelId ?? focusTargetId) || !models.length) return
    const model = models.find((item) => focusModelId ? item.id === focusModelId : item.targetId === focusTargetId)
    if (model) { focusedTargetRef.current = focusModelId ?? focusTargetId; void openModel(model) }
  }, [focusTargetId, focusModelId, models])
  const openJob = async (row: TrainingJobSummary) => { try { setJobDetail(await trainingApi.job(row.id)) } catch (error) { report(error) } }
  const retryJob = async () => {
    if (!jobDetail) return
    try { await trainingApi.retry(jobDetail.job.id, jobDetail.job.revision); setJobDetail(undefined); await load() } catch (error) { report(error) }
  }
  const cancelJob = async () => {
    if (!jobDetail) return
    try { await trainingApi.cancel(jobDetail.job.id, jobDetail.job.revision, '用户从模型管理页面取消'); setJobDetail(undefined); await load() } catch (error) { report(error) }
  }
  const submitAction = async () => {
    if (!action) return
    try {
      const { reason } = await actionForm.validateFields()
      const active = models.find((model) => model.targetId === action.model.targetId && model.status === 'ACTIVE')
      const input = { expectedRevision: action.model.revision, expectedActiveModelVersionId: active?.id, reason }
      await trainingApi[action.type](action.model.id, input); setAction(undefined); actionForm.resetFields(); setModelDetail(undefined); await load(); void message.success('模型状态已更新')
    } catch (error) {
      if (isModelVersionChanged(error)) {
        setAction(undefined)
        actionForm.resetFields()
        setModelDetail(undefined)
        await load()
        void message.warning('模型版本已更新，列表已刷新，请重新打开当前模型后再操作')
        return
      }
      if (error instanceof Error) { setAction(undefined); report(error) }
    }
  }

  const modelColumns = [
    { title: '预测目标', key: 'target', width: 180, ellipsis: true, render: (_: unknown, row: ModelVersion) => <div className="model-name"><div className="model-name-head"><strong title={row.targetName}>{row.targetName}</strong></div><span>{row.source?.datasetName || categoryLabel[row.category] || '实验数据'} · {valueType[row.valueType] ?? '结果预测'}{row.source?.xColumns?.length ? ` · X：${row.source.xColumns.map(x => x.name).join('、')}` : ''}</span></div> },
    { title: '预测方法', dataIndex: 'modelType', width: 120, render: (value: string) => algorithmName(value) },
    { title: '模型状态', dataIndex: 'status', width: 135, render: (_: string, row: ModelVersion) => { const state = modelStatus[row.status]; return <div className="model-state-cell"><Tag color={state?.color}>{state?.text ?? row.status}</Tag><span>{modelStateHint(row)}</span></div> } },
    { title: '验证结果', key: 'metrics', width: 165, render: (_: unknown, row: ModelVersion) => { const metric = primaryMetric(row); return <div className="model-metric"><strong>{metric.value}</strong><span>{metric.detail}</span></div> } },
    { title: '训练记录', key: 'samples-version', width: 125, render: (_: unknown, row: ModelVersion) => <div className="model-training-meta"><span>{row.trainableSamples} 条 · 第{row.version}版</span><small>{new Date(row.updatedAt).toLocaleDateString('zh-CN')}</small></div> },
    { title: '操作', key: 'action', width: 110, render: (_: unknown, row: ModelVersion) => <Button type="link" onClick={() => void openModel(row)}>{modelActionLabel(row.status)}</Button> },
  ]
  const jobColumns = [
    { title: '预测目标', key: 'target', render: (_: unknown, row: TrainingJobSummary) => <div className="model-name"><strong>{row.targetName}</strong><span>{valueType[row.valueType] ?? '结果预测'}</span></div> },
    { title: '进度', key: 'progress', render: (_: unknown, row: TrainingJobSummary) => <div className="job-progress"><Progress percent={row.progress} size="small" status={row.status === 'FAILED' ? 'exception' : row.status === 'SUCCEEDED' ? 'success' : 'active'} /><span>{jobStatus[row.status]?.text ?? stageLabel[row.stage] ?? '处理中'}</span></div> },
    { title: '尝试次数', dataIndex: 'attemptCount', width: 100, render: (count: number, row: TrainingJobSummary) => `${count} / ${row.maxAttempts}` },
    { title: '开始时间', dataIndex: 'startedAt', width: 170, render: when },
    { title: '', key: 'action', width: 90, render: (_: unknown, row: TrainingJobSummary) => <Button type="link" onClick={() => void openJob(row)}>查看</Button> },
  ]

  return <main className={`model-management-page${embedded ? ' model-management-panel' : ''}`}>
    {!embedded && <header className="model-management-header"><Typography.Title level={2}>模型中心</Typography.Title></header>}

    <section className="model-summary-strip">
      <div><CheckCircleOutlined /><span>使用中</span><strong>{activeCount}</strong></div>
      <div><ClockCircleOutlined /><span>待检查版本</span><strong>{candidateCount}</strong></div>
      <div><ReloadOutlined spin={trainingCount > 0} /><span>正在训练</span><strong>{trainingCount}</strong></div>
    </section>

    <Card className="model-directory" variant="borderless">
      <div className="model-directory-toolbar">
        <Segmented value={tab} onChange={(value) => { const next = value as 'models' | 'jobs'; setTab(next); onViewChange?.(next) }} options={[{ value: 'models', label: '模型目录' }, { value: 'jobs', label: `训练任务${trainingCount ? ` (${trainingCount})` : ''}` }]} />
        <Space wrap><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>{tab==='models'&&<Checkbox checked={showHistory} onChange={(event)=>setShowHistory(event.target.checked)}>显示历史版本</Checkbox>}<Input.Search allowClear placeholder="搜索预测目标" onSearch={setKeyword} style={{ width: 200 }} /><Select allowClear placeholder="性能分类" value={category} onChange={setCategory} options={categories.map((item) => ({ value: item, label: categoryLabel[item] ?? '实验数据' }))} style={{ width: 140 }} />{tab === 'models' && <Select allowClear placeholder="模型状态" value={status} onChange={setStatus} options={Object.entries(modelStatus).map(([value, item]) => ({ value, label: item.text }))} style={{ width: 130 }} />}</Space>
      </div>
      {tab === 'models' ? <Table className="model-list-table" rowKey="id" loading={loading} columns={modelColumns} dataSource={visibleModels} tableLayout="fixed" scroll={{ x: 870 }} pagination={{ pageSize: 12 }} locale={{ emptyText: <Empty description="没有符合条件的模型" /> }} /> : <Table rowKey="id" loading={loading} columns={jobColumns} dataSource={jobs} scroll={{ x: 900 }} pagination={{ pageSize: 12 }} locale={{ emptyText: <Empty description="暂无训练任务" /> }} />}
    </Card>

    <Drawer width={620} open={Boolean(modelDetail)} onClose={() => setModelDetail(undefined)} title={modelDetail ? `${modelDetail.model.targetName} · 第${modelDetail.model.version}版` : ''}>
    {modelDetail && <div className="model-drawer">
        <div className="drawer-lead"><Tag color={modelStatus[modelDetail.model.status]?.color}>{modelStatus[modelDetail.model.status]?.text}</Tag>{algorithmName(modelDetail.model.modelType)}<span>{valueType[modelDetail.model.valueType] ?? '结果预测'} · {modelDetail.model.trainableSamples} 条训练数据</span></div>
        <section className="algorithm-explanation"><h3>算法说明</h3><p><strong>{modelTypeOriginalLabel[modelDetail.model.modelType] ?? modelDetail.model.modelType}（{modelTypeLabel[modelDetail.model.modelType] ?? '综合预测方法'}）</strong>：{modelTypeDescription[modelDetail.model.modelType] ?? '系统根据当前数据自动选择并验证预测方法。'}</p></section>
        <div className="model-notice">{modelUsageGuidance(modelDetail.model)}</div>
        <section><h3>验证表现</h3><p className="metric-intro">查看模型在未参与训练的数据上的预测误差和准确情况。</p><div className="metric-grid">{orderedMetrics(modelDetail.model.metrics).slice(0, 10).map(([key, value]) => <div key={key} className="metric-card"><div className="metric-title"><span><strong>{metricOriginalLabel[key] ?? key}</strong><em>{metricLabel[key] ?? '验证指标'}</em></span><Tooltip title={metricDescription[key] ?? '该指标用于衡量模型表现。'}><span className="metric-help">?</span></Tooltip></div><strong>{metricValue(key, Number(value))}</strong><p>{metricDescription[key] ?? '该指标用于衡量模型表现。'}</p></div>)}</div></section>
        {modelDetail.model.source?.origin === 'WORKBENCH' && <section><h3>训练数据</h3><p>{modelDetail.model.source.datasetName}</p><p>X：{modelDetail.model.source.xColumns?.map(x => x.name).join('、')}</p><p>Y：{modelDetail.model.source.yColumn?.name}{modelDetail.model.source.yColumn?.unit ? `（${modelDetail.model.source.yColumn.unit}）` : ''}</p><Table size="small" rowKey={(row, index) => String(row.id ?? index)} scroll={{ x: 'max-content' }} pagination={{pageSize: 8}} dataSource={(modelDetail.modelCard.trainingRows as Array<{id: string; label?: string; title?: string; sourceDatasetName?: string; values: Record<string, string | number>}>) ?? []} columns={[{ title: '实验记录', render: (_, row) => row.label || row.title || row.sourceDatasetName || modelDetail.model.source?.datasetName }, ...[...(modelDetail.model.source.xColumns ?? []), ...(modelDetail.model.source.yColumn ? [modelDetail.model.source.yColumn] : [])].map(column => ({ title: `${column.name}${column.unit ? `（${column.unit}）` : ''}`, render: (_: unknown, row: {values: Record<string, string | number>}) => String(row.values[column.code] ?? '—') }))]} /></section>}
        <section><h3>版本记录</h3>{modelDetail.releases.length ? modelDetail.releases.map((release) => <div className="release-row" key={release.id}><Tag>{releaseActionLabel[release.action] ?? '状态变更'}</Tag><span>{release.reason}</span><small>{when(release.createdAt)}</small></div>) : <Typography.Text type="secondary">尚无启用或回退记录</Typography.Text>}</section>
        {canPublish && <div className="drawer-actions">{modelDetail.model.status === 'CANDIDATE' && <Button type="primary" disabled={!modelDetail.model.productionEligible && !modelDetail.testActivationAllowed} onClick={() => setAction({ type: 'activate', model: modelDetail.model })}>启用此版本</Button>}{modelDetail.model.status === 'ACTIVE' && <Button onClick={() => setAction({ type: 'pause', model: modelDetail.model })}>暂停使用</Button>}{['PAUSED', 'RETIRED'].includes(modelDetail.model.status) && <Button type="primary" disabled={!modelDetail.model.productionEligible && !modelDetail.testActivationAllowed} onClick={() => setAction({ type: 'rollback', model: modelDetail.model })}>回退到此版本</Button>}</div>}
      </div>}
    </Drawer>

    <Drawer width={620} open={Boolean(jobDetail)} onClose={() => setJobDetail(undefined)} title={jobDetail?.job.targetName ?? '训练任务'}>
      {jobDetail && <div className="model-drawer"><div className="job-detail-progress"><Progress percent={jobDetail.job.progress} status={jobDetail.job.status === 'FAILED' ? 'exception' : jobDetail.job.status === 'SUCCEEDED' ? 'success' : 'active'} /><strong>{jobStatus[jobDetail.job.status]?.text ?? '处理中'}</strong><span>{jobDetail.job.errorMessage}</span></div><section><h3>运行记录</h3>{jobDetail.attempts.map((attempt) => <div className="attempt-row" key={attempt.id}><b>第 {attempt.attemptNo} 次</b><Tag color={attempt.status === 'SUCCEEDED' ? 'green' : attempt.status === 'RUNNING' ? 'processing' : 'red'}>{stageLabel[attempt.status] ?? '处理中'}</Tag><span>{stageLabel[attempt.stage] ?? '系统处理'}</span><small>{when(attempt.startedAt)}</small></div>)}</section><div className="model-notice">训练记录会自动保存，已有结果不会被覆盖。</div>{canOperate && <div className="drawer-actions">{jobDetail.job.status === 'FAILED' && <Button type="primary" onClick={() => { void retryJob() }}>重试</Button>}{running.has(jobDetail.job.status) && <Button danger onClick={() => { void cancelJob() }}>取消任务</Button>}</div>}</div>}
    </Drawer>

    <Modal open={Boolean(action)} title={action?.type === 'pause' ? '暂停模型' : action?.type === 'rollback' ? '回退模型' : '启用此版本'} okText="确认" cancelText="取消" onCancel={() => setAction(undefined)} onOk={() => void submitAction()}><Form form={actionForm} layout="vertical"><Form.Item name="reason" label="操作原因" rules={[{ required: true, message: '请填写操作原因' }]}><Input.TextArea rows={3} maxLength={300} /></Form.Item></Form></Modal>
  </main>
}
