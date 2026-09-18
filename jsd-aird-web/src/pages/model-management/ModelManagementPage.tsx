import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { App, Button, Card, Collapse, Drawer, Empty, Form, Input, Modal, Progress, Select, Segmented, Space, Switch, Table, Tag, Typography } from 'antd'
import { CheckCircleOutlined, ClockCircleOutlined, ControlOutlined, PauseCircleOutlined, PlayCircleOutlined, ReloadOutlined } from '@ant-design/icons'
import { useAuthStore } from '@/stores/auth-store'
import { modelingApi } from '@/services/ai-rnd/modeling-api'
import { trainingApi } from '@/services/ai-rnd/training-api'
import type { ModelDetail, ModelVersion, PredictionTargetSummary, TrainingJobDetail, TrainingJobSummary, TrainingSettings } from '@/services/ai-rnd/ai-rnd-types'
import './model-management.css'

const modelStatus: Record<string, { text: string; color: string }> = {
  ACTIVE: { text: '使用中', color: 'green' }, CANDIDATE: { text: '待评估', color: 'blue' },
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
const metricLabel: Record<string, string> = { mae: '平均绝对误差', rmse: '均方根误差', r2: '拟合度', accuracy: '准确率', macroF1: '宏平均 F1', logLoss: '概率损失', gradeMae: '等级误差', plusMinusOneAccuracy: '相邻等级准确率', spearman: '等级相关性' }
const running = new Set(['QUEUED', 'MATERIALIZING', 'SNAPSHOT_VALIDATING', 'FOLDING', 'TRAINING', 'VALIDATING'])

function when(value?: string) { return value ? new Date(value).toLocaleString('zh-CN') : '尚未执行' }
function firstMetric(metrics: Record<string, unknown>) {
  const entry = Object.entries(metrics).find(([key, val]) => typeof val === 'number' && key !== 'candidateCount')
  return entry ? `${metricLabel[entry[0]] ?? entry[0]} ${Number(entry[1]).toFixed(3)}` : '等待验证结果'
}

type ModelManagementPageProps = {
  embedded?: boolean
  focusTargetId?: string
  onOpenSettings?: (targetId: string) => void
  view?: 'models' | 'jobs'
  onViewChange?: (view: 'models' | 'jobs') => void
}

export function ModelManagementPage({ embedded = false, focusTargetId, onOpenSettings, view, onViewChange }: ModelManagementPageProps = {}) {
  const { message } = App.useApp()
  const canOperate = useAuthStore((state) => state.can('ai.training.operate'))
  const canPublish = useAuthStore((state) => state.can('ai.model.publish'))
  const canModelingRead = useAuthStore((state) => state.can('ai.modeling.read'))
  const [settings, setSettings] = useState<TrainingSettings>()
  const [models, setModels] = useState<ModelVersion[]>([])
  const [jobs, setJobs] = useState<TrainingJobSummary[]>([])
  const [targets, setTargets] = useState<PredictionTargetSummary[]>([])
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

  const report = useCallback((error: unknown) => { void message.error(error instanceof Error ? error.message : '操作失败') }, [message])
  const load = useCallback(async (quiet = false) => {
    if (!quiet) setLoading(true)
    try {
      const [setting, modelPage, jobPage, targetPage] = await Promise.all([
        trainingApi.settings(), trainingApi.models({ status, category, keyword: keyword || undefined, page: 1, size: 100 }),
        trainingApi.jobs({ keyword: keyword || undefined, page: 1, size: 100 }),
        canModelingRead ? modelingApi.targets({ page: 1, size: 100 }) : Promise.resolve({ items: [] as PredictionTargetSummary[], total: 0, page: 1, size: 100 }),
      ])
      setSettings(setting); setModels(modelPage.items); setJobs(jobPage.items); setTargets(targetPage.items)
    } catch (error) { report(error) } finally { if (!quiet) setLoading(false) }
  }, [canModelingRead, category, keyword, report, status])

  useEffect(() => { void load() }, [load])
  const hasRunning = jobs.some((job) => running.has(job.status))
  useEffect(() => { if (!hasRunning) return; const timer = window.setInterval(() => void load(true), 3000); return () => window.clearInterval(timer) }, [hasRunning, load])
  useEffect(() => { if (view && view !== tab) setTab(view) }, [tab, view])

  const activeCount = models.filter((model) => model.status === 'ACTIVE').length
  const candidateCount = models.filter((model) => model.status === 'CANDIDATE').length
  const trainingCount = jobs.filter((job) => running.has(job.status)).length
  const modeledTargets = new Set(models.map((model) => model.targetId))
  const waitingDataCount = targets.filter((target) => !modeledTargets.has(target.id)).length
  const categories = useMemo(() => [...new Set((canModelingRead ? targets.map((target) => target.category) : models.map((model) => model.category)).filter(Boolean))], [canModelingRead, models, targets])

  const toggle = async (checked: boolean) => {
    if (!settings) return
    try { setSettings(await trainingApi.updateSettings({ autoLearningEnabled: checked, expectedRevision: settings.revision })); void message.success(checked ? '已开启自动学习新实验' : '已关闭自动学习新实验') } catch (error) { report(error) }
  }
  const evaluate = async () => { try { const result = await trainingApi.evaluate(); void message.success(`已检查 ${result.targetsChecked} 个目标，新建 ${result.jobsCreated} 个训练任务`); await load() } catch (error) { report(error) } }
  const openModel = async (row: ModelVersion) => { try { setModelDetail(await trainingApi.model(row.id)) } catch (error) { report(error) } }
  useEffect(() => {
    if (!focusTargetId || focusedTargetRef.current === focusTargetId || !models.length) return
    const model = models.find((item) => item.targetId === focusTargetId)
    if (model) { focusedTargetRef.current = focusTargetId; void openModel(model) }
  }, [focusTargetId, models])
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
    } catch (error) { if (error instanceof Error) report(error) }
  }

  const modelColumns = [
    { title: '预测目标', key: 'target', render: (_: unknown, row: ModelVersion) => <div className="model-name"><strong>{row.targetName}</strong><span>{row.category || '未分类'} · {valueType[row.valueType]}</span></div> },
    { title: '算法', dataIndex: 'modelType', width: 150, render: (value: string) => value || '—' },
    { title: '模型状态', dataIndex: 'status', width: 120, render: (value: string) => <Tag color={modelStatus[value]?.color}>{modelStatus[value]?.text ?? value}</Tag> },
    { title: '验证结果', key: 'metrics', render: (_: unknown, row: ModelVersion) => <div className="model-metric"><strong>{firstMetric(row.metrics)}</strong><span>{row.comparisonStatus === 'NOT_COMPARABLE' ? '暂无同口径正式模型比较' : row.comparisonStatus === 'BETTER' ? '优于当前模型' : '已有比较结果'}</span></div> },
    { title: '训练数据', dataIndex: 'trainableSamples', width: 110, render: (count: number) => `${count} 条` },
    { title: '版本', dataIndex: 'version', width: 80, render: (version: number) => `V${version}` },
    { title: '更新时间', dataIndex: 'updatedAt', width: 170, render: when },
    { title: '操作', key: 'action', width: 170, render: (_: unknown, row: ModelVersion) => <Space size={0}><Button type="link" onClick={() => void openModel(row)}>查看详情</Button>{onOpenSettings && canModelingRead && <Button type="link" onClick={() => onOpenSettings(row.targetId)}>建模设置</Button>}</Space> },
  ]
  const jobColumns = [
    { title: '预测目标', key: 'target', render: (_: unknown, row: TrainingJobSummary) => <div className="model-name"><strong>{row.targetName}</strong><span>{valueType[row.valueType]}</span></div> },
    { title: '进度', key: 'progress', render: (_: unknown, row: TrainingJobSummary) => <div className="job-progress"><Progress percent={row.progress} size="small" status={row.status === 'FAILED' ? 'exception' : row.status === 'SUCCEEDED' ? 'success' : 'active'} /><span>{jobStatus[row.status]?.text ?? row.stage}</span></div> },
    { title: '尝试次数', dataIndex: 'attemptCount', width: 100, render: (count: number, row: TrainingJobSummary) => `${count} / ${row.maxAttempts}` },
    { title: '开始时间', dataIndex: 'startedAt', width: 170, render: when },
    { title: '', key: 'action', width: 90, render: (_: unknown, row: TrainingJobSummary) => <Button type="link" onClick={() => void openJob(row)}>查看</Button> },
  ]

  return <main className={`model-management-page${embedded ? ' model-management-panel' : ''}`}>
    {!embedded && <header className="model-management-header">
      <div><Typography.Title level={2}>模型管理</Typography.Title><Typography.Text>查看训练进度，比较候选模型，并决定正式使用的版本。</Typography.Text></div>
      <Space><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button><Button type="primary" icon={<PlayCircleOutlined />} disabled={!canOperate} onClick={() => void evaluate()}>立即检查</Button></Space>
    </header>}

    <section className="learning-control">
      <div className="learning-icon"><ControlOutlined /></div>
      <div><strong>自动学习新实验</strong><span>资格数据更新后，系统按各目标已发布的训练规则创建候选模型。</span></div>
      <div className="learning-meta"><small>最近检查</small><b>{when(settings?.lastEvaluatedAt)}</b></div>
      <Switch checked={settings?.autoLearningEnabled} disabled={!canOperate || !settings} onChange={(checked) => void toggle(checked)} />
    </section>

    <section className={`model-summary-strip${canModelingRead ? '' : ' model-summary-strip-limited'}`}>
      <div><CheckCircleOutlined /><span>使用中</span><strong>{activeCount}</strong></div>
      <div><ClockCircleOutlined /><span>候选待评估</span><strong>{candidateCount}</strong></div>
      <div><ReloadOutlined spin={trainingCount > 0} /><span>正在训练</span><strong>{trainingCount}</strong></div>
      {canModelingRead && <div><PauseCircleOutlined /><span>积累数据</span><strong>{waitingDataCount}</strong></div>}
    </section>

    <Card className="model-directory" variant="borderless">
      <div className="model-directory-toolbar">
        <Segmented value={tab} onChange={(value) => { const next = value as 'models' | 'jobs'; setTab(next); onViewChange?.(next) }} options={[{ value: 'models', label: '模型目录' }, { value: 'jobs', label: `训练任务${trainingCount ? ` (${trainingCount})` : ''}` }]} />
        <Space wrap><Input.Search allowClear placeholder="搜索预测目标" onSearch={setKeyword} style={{ width: 220 }} /><Select allowClear placeholder="性能分类" value={category} onChange={setCategory} options={categories.map((item) => ({ value: item, label: item }))} style={{ width: 150 }} />{tab === 'models' && <Select allowClear placeholder="模型状态" value={status} onChange={setStatus} options={Object.entries(modelStatus).map(([value, item]) => ({ value, label: item.text }))} style={{ width: 140 }} />}</Space>
      </div>
      {tab === 'models' ? <Table rowKey="id" loading={loading} columns={modelColumns} dataSource={models} pagination={{ pageSize: 12 }} locale={{ emptyText: <Empty description="尚无模型。请先在建模设置完成数据资格和训练策略。" /> }} /> : <Table rowKey="id" loading={loading} columns={jobColumns} dataSource={jobs} pagination={{ pageSize: 12 }} locale={{ emptyText: <Empty description="暂无训练任务" /> }} />}
    </Card>

    <Drawer width={620} open={Boolean(modelDetail)} onClose={() => setModelDetail(undefined)} title={modelDetail ? `${modelDetail.model.targetName} · V${modelDetail.model.version}` : ''}>
      {modelDetail && <div className="model-drawer">
        <div className="drawer-lead"><Tag color={modelStatus[modelDetail.model.status]?.color}>{modelStatus[modelDetail.model.status]?.text}</Tag><strong>{modelDetail.model.modelType}</strong><span>{valueType[modelDetail.model.valueType]} · {modelDetail.model.trainableSamples} 条训练数据</span></div>
        {!modelDetail.model.productionEligible && <div className="model-notice">该版本尚不具备正式启用条件。测试数据模型不会获得生产资格。</div>}
        <section><h3>验证表现</h3><div className="metric-grid">{Object.entries(modelDetail.model.metrics).filter(([, value]) => typeof value === 'number').slice(0, 8).map(([key, value]) => <div key={key}><span>{metricLabel[key] ?? key}</span><strong>{Number(value).toFixed(3)}</strong></div>)}</div></section>
        <section><h3>版本记录</h3>{modelDetail.releases.length ? modelDetail.releases.map((release) => <div className="release-row" key={release.id}><Tag>{release.action}</Tag><span>{release.reason}</span><small>{when(release.createdAt)}</small></div>) : <Typography.Text type="secondary">尚无启用或回退记录</Typography.Text>}</section>
        <Collapse ghost items={[{ key: 'advanced', label: '高级信息', children: <pre>{JSON.stringify({ snapshot: modelDetail.snapshot, applicabilityDomain: modelDetail.applicabilityDomain, modelCard: modelDetail.modelCard }, null, 2)}</pre> }]} />
        {canPublish && <div className="drawer-actions">{modelDetail.model.status === 'CANDIDATE' && <Button type="primary" disabled={!modelDetail.model.productionEligible} onClick={() => setAction({ type: 'activate', model: modelDetail.model })}>启用此版本</Button>}{modelDetail.model.status === 'ACTIVE' && <Button onClick={() => setAction({ type: 'pause', model: modelDetail.model })}>暂停使用</Button>}{['PAUSED', 'RETIRED'].includes(modelDetail.model.status) && <Button type="primary" disabled={!modelDetail.model.productionEligible} onClick={() => setAction({ type: 'rollback', model: modelDetail.model })}>回退到此版本</Button>}</div>}
      </div>}
    </Drawer>

    <Drawer width={620} open={Boolean(jobDetail)} onClose={() => setJobDetail(undefined)} title={jobDetail?.job.targetName ?? '训练任务'}>
      {jobDetail && <div className="model-drawer"><div className="job-detail-progress"><Progress percent={jobDetail.job.progress} status={jobDetail.job.status === 'FAILED' ? 'exception' : jobDetail.job.status === 'SUCCEEDED' ? 'success' : 'active'} /><strong>{jobStatus[jobDetail.job.status]?.text}</strong><span>{jobDetail.job.errorMessage}</span></div><section><h3>运行记录</h3>{jobDetail.attempts.map((attempt) => <div className="attempt-row" key={attempt.id}><b>第 {attempt.attemptNo} 次</b><Tag color={attempt.status === 'SUCCEEDED' ? 'green' : attempt.status === 'RUNNING' ? 'processing' : 'red'}>{attempt.status}</Tag><span>{attempt.stage}</span><small>{when(attempt.startedAt)}</small></div>)}</section><Collapse ghost items={[{ key: 'advanced', label: '高级信息', children: <pre>{JSON.stringify(jobDetail.frozenConfiguration, null, 2)}</pre> }]} />{canOperate && <div className="drawer-actions">{jobDetail.job.status === 'FAILED' && <Button type="primary" onClick={() => { void retryJob() }}>重试</Button>}{running.has(jobDetail.job.status) && <Button danger onClick={() => { void cancelJob() }}>取消任务</Button>}</div>}</div>}
    </Drawer>

    <Modal open={Boolean(action)} title={action?.type === 'pause' ? '暂停模型' : action?.type === 'rollback' ? '回退模型' : '启用候选模型'} okText="确认" cancelText="取消" onCancel={() => setAction(undefined)} onOk={() => void submitAction()}><Form form={actionForm} layout="vertical"><Form.Item name="reason" label="操作原因" rules={[{ required: true, message: '请填写操作原因' }]}><Input.TextArea rows={3} maxLength={300} /></Form.Item></Form></Modal>
  </main>
}
