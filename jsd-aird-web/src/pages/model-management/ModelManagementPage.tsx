import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { App, Button, Card, Checkbox, Drawer, Empty, Form, Input, Modal, Select, Space, Table, Tag, Typography } from 'antd'
import { CheckCircleOutlined, ClockCircleOutlined, ReloadOutlined } from '@ant-design/icons'
import { useAuthStore } from '@/stores/auth-store'
import { modelRegistryApi } from '@/services/ai-rnd/model-registry-api'
import type { ModelDetail, ModelVersion } from '@/services/ai-rnd/ai-rnd-types'
import { isModelVersionChanged } from '@/services/http/errors'
import { aiActionFeedback } from '@/services/ai-rnd/action-feedback'
import { TrainingEvaluation } from '@/components/ai-rnd/TrainingEvaluation'
import './model-management.css'

const modelStatus: Record<string, { text: string; color: string }> = {
  ACTIVE: { text: '使用中', color: 'green' },
  PAUSED: { text: '已暂停', color: 'orange' }, RETIRED: { text: '历史版本', color: 'default' },
}
const valueType: Record<string, string> = { CONTINUOUS: '连续数值', ORDINAL: '有序等级', BINARY: '二分类', CATEGORICAL: '多分类' }
const metricLabel: Record<string, string> = { mae: '平均绝对误差', rmse: '均方根误差', r2: '拟合度', accuracy: '准确率', macroF1: '宏平均 F1', logLoss: '概率损失', gradeMae: '等级误差', plusMinusOneAccuracy: '相邻等级准确率', spearman: '等级相关性', residualStd: '残差标准差', candidateCount: '参与比较的模型数', intervalCoverage90: '90%预测区间覆盖率' }
const modelTypeLabel: Record<string, string> = {
  LINEAR_REGRESSION: '线性回归', RIDGE: '岭回归',
  XGBOOST: '梯度提升树', LIGHTGBM: '轻量梯度提升树', RANDOM_FOREST: '随机森林',
  GAUSSIAN_PROCESS: '高斯过程', CATBOOST: '分类提升树', LOGISTIC_REGRESSION: '逻辑回归',
  ORDINAL_CUMULATIVE_LOGIT: '等级概率模型', DEVELOPMENT_KNN: '相似案例模型',
}
const modelTypeOriginalLabel: Record<string, string> = {
  LINEAR_REGRESSION: 'Linear Regression', RIDGE: 'Ridge',
  XGBOOST: 'XGBoost', LIGHTGBM: 'LightGBM', RANDOM_FOREST: 'Random Forest', GAUSSIAN_PROCESS: 'Gaussian Process',
  CATBOOST: 'CatBoost', LOGISTIC_REGRESSION: 'Logistic Regression', ORDINAL_CUMULATIVE_LOGIT: 'Ordinal Cumulative Logit',
  DEVELOPMENT_KNN: 'Development KNN',
}
const categoryLabel: Record<string, string> = {
  UVPU_APPLICATION_FORMULATION: 'UV/PU应用配方', GENERAL_DATA: '通用数据',
}
const releaseActionLabel: Record<string, string> = {
  REPLACE: '更新版本', ACTIVATE: '启用', PAUSE: '暂停', ROLLBACK: '回退', RETIRE: '归档', REJECT: '不采用',
}

function when(value?: string) { return value ? new Date(value).toLocaleString('zh-CN') : '—' }
const customerMetricKeys = new Set(Object.keys(metricLabel))
const metricOriginalLabel: Record<string,string> = { mae:'MAE', rmse:'RMSE', r2:'R²', macroF1:'Macro F1', accuracy:'Accuracy', logLoss:'Log Loss' }
function metricValue(key: string, value: number) { return ['accuracy','macroF1'].includes(key) ? `${(value*100).toFixed(1)}%` : value.toFixed(3) }
const preferredMetrics: Record<string, string[]> = {
  CONTINUOUS: ['mae', 'rmse', 'r2'], ORDINAL: ['gradeMae', 'plusMinusOneAccuracy', 'spearman'],
  BINARY: ['macroF1', 'accuracy', 'logLoss'], CATEGORICAL: ['macroF1', 'accuracy', 'logLoss'],
}
const lowerIsBetter = new Set(['mae', 'rmse', 'gradeMae', 'logLoss'])
function primaryMetric(model: Pick<ModelVersion, 'metrics' | 'valueType' | 'source'>) {
  const available = Object.entries(model.metrics).filter(([key, value]) => customerMetricKeys.has(key) && typeof value === 'number')
  const ordered = preferredMetrics[model.valueType] ?? []
  const entry = ordered.map((key) => available.find(([candidate]) => candidate === key)).find(Boolean) ?? available[0]
  if (!entry) return { value: '暂无验证指标', detail: '打开详情查看训练结果' }
  const [key, value] = entry
  const direction = lowerIsBetter.has(key) ? '越低越好' : '越高越好'
  const unit = (key === 'mae' || key === 'rmse') ? model.source?.yColumn?.unit : undefined
  return { value: `${metricOriginalLabel[key] ?? key} ${metricValue(key, Number(value))}${unit ? ` ${unit}` : ''}`, detail: `${metricLabel[key] ?? '验证指标'} · ${direction}` }
}

function modelActionLabel(status: string) {
  if (status === 'ACTIVE') return '查看模型'
  if (status === 'RETIRED') return '查看历史'
  return '查看版本'
}

function modelStateHint(model: ModelVersion) {
  if (model.status === 'ACTIVE') return '当前用于预测'
  if (model.status === 'PAUSED') return '已暂停，不用于新预测'
  return '历史版本，仅供查看'
}

function algorithmName(value: string) {
  const original = modelTypeOriginalLabel[value] ?? value
  const chinese = modelTypeLabel[value] ?? '综合预测方法'
  return <span className="algorithm-name"><strong>{original}</strong><span>{chinese}</span></span>
}

type ModelManagementPageProps = {
  embedded?: boolean
  focusModelId?: string
  focusTargetId?: string
}

export function ModelManagementPage({ embedded = false, focusTargetId, focusModelId }: ModelManagementPageProps = {}) {
  const { message } = App.useApp()
  const canPublish = useAuthStore((state) => state.can('ai.model.publish'))
  const [models, setModels] = useState<ModelVersion[]>([])
  const [loading, setLoading] = useState(true)
  const [keyword, setKeyword] = useState('')
  const [status, setStatus] = useState<string>()
  const [category, setCategory] = useState<string>()
  const [modelDetail, setModelDetail] = useState<ModelDetail>()
  const [action, setAction] = useState<{ type: 'pause' | 'rollback'; model: ModelVersion }>()
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
      const modelPage = await modelRegistryApi.models({ status, category, keyword: keyword || undefined, page: 1, size: 100 })
      setModels(modelPage.items)
    } catch (error) { report(error) } finally { if (!quiet) setLoading(false) }
  }, [category, keyword, report, status])

  useEffect(() => { void load() }, [load])

  const activeCount = models.filter((model) => model.status === 'ACTIVE').length
  const categories = useMemo(() => [...new Set(models.map((model) => model.category).filter(Boolean))], [models])
  const openModel = async (row: Pick<ModelVersion, 'id'>) => { try { setModelDetail(await modelRegistryApi.model(row.id)) } catch (error) { report(error) } }
  useEffect(() => {
    if (!focusTargetId || focusedTargetRef.current === (focusModelId ?? focusTargetId) || !models.length) return
    const model = models.find((item) => focusModelId ? item.id === focusModelId : item.targetId === focusTargetId)
    if (model) { focusedTargetRef.current = focusModelId ?? focusTargetId; void openModel(model) }
  }, [focusTargetId, focusModelId, models])
  const submitAction = async () => {
    if (!action) return
    try {
      const { reason } = await actionForm.validateFields()
      const active = models.find((model) => model.targetId === action.model.targetId && model.status === 'ACTIVE')
      const input = { expectedRevision: action.model.revision, expectedActiveModelVersionId: active?.id, reason }
      await modelRegistryApi[action.type](action.model.id, input); setAction(undefined); actionForm.resetFields(); setModelDetail(undefined); await load(); void message.success('模型状态已更新')
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

  return <main className={`model-management-page${embedded ? ' model-management-panel' : ''}`}>
    {!embedded && <header className="model-management-header"><Typography.Title level={2}>模型中心</Typography.Title></header>}

    <section className="model-summary-strip">
      <div><CheckCircleOutlined /><span>使用中</span><strong>{activeCount}</strong></div>
      <div><ClockCircleOutlined /><span>已暂停</span><strong>{models.filter(model => model.status === 'PAUSED').length}</strong></div>
      <div><ReloadOutlined /><span>历史版本</span><strong>{models.filter(model => model.status === 'RETIRED').length}</strong></div>
    </section>

    <Card className="model-directory" variant="borderless">
      <div className="model-directory-toolbar">
        <Space wrap><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button><Checkbox checked={showHistory} onChange={(event)=>setShowHistory(event.target.checked)}>显示历史版本</Checkbox><Input.Search allowClear placeholder="搜索预测目标" onSearch={setKeyword} style={{ width: 200 }} /><Select allowClear placeholder="性能分类" value={category} onChange={setCategory} options={categories.map((item) => ({ value: item, label: categoryLabel[item] ?? '实验数据' }))} style={{ width: 140 }} /><Select allowClear placeholder="模型状态" value={status} onChange={setStatus} options={Object.entries(modelStatus).map(([value, item]) => ({ value, label: item.text }))} style={{ width: 130 }} /></Space>
      </div>
      <Table className="model-list-table" rowKey="id" loading={loading} columns={modelColumns} dataSource={visibleModels} tableLayout="fixed" scroll={{ x: 870 }} pagination={{ pageSize: 12 }} locale={{ emptyText: <Empty description="没有符合条件的模型" /> }} />
    </Card>

    <Drawer width={620} open={Boolean(modelDetail)} onClose={() => setModelDetail(undefined)} title={modelDetail ? `${modelDetail.model.targetName} · 第${modelDetail.model.version}版` : ''}>
    {modelDetail && <div className="model-drawer">
        <div className="drawer-lead"><Tag color={modelStatus[modelDetail.model.status]?.color}>{modelStatus[modelDetail.model.status]?.text}</Tag>{algorithmName(modelDetail.model.modelType)}<span>{valueType[modelDetail.model.valueType] ?? '结果预测'} · {modelDetail.model.trainableSamples} 条训练数据</span></div>
        <section><h3>模型表现</h3><TrainingEvaluation metrics={modelDetail.model.metrics} numeric={modelDetail.model.valueType === 'CONTINUOUS'} unit={modelDetail.model.source?.yColumn?.unit} /></section>
        <section><h3>训练数据</h3><p>{modelDetail.model.source?.datasetName}</p><p>X：{modelDetail.model.source?.xColumns?.map(x => x.name).join('、')}</p><p>Y：{modelDetail.model.source?.yColumn?.name}{modelDetail.model.source?.yColumn?.unit ? `（${modelDetail.model.source?.yColumn.unit}）` : ''}</p><Table size="small" rowKey={(row, index) => String(row.id ?? index)} scroll={{ x: 'max-content' }} pagination={{pageSize: 8}} dataSource={(modelDetail.modelCard.trainingRows as Array<{id: string; label?: string; title?: string; sourceDatasetName?: string; values: Record<string, string | number>}>) ?? []} columns={[{ title: '实验记录', render: (_, row) => row.label || row.title || row.sourceDatasetName || modelDetail.model.source?.datasetName }, ...[...(modelDetail.model.source?.xColumns ?? []), ...(modelDetail.model.source?.yColumn ? [modelDetail.model.source?.yColumn] : [])].map(column => ({ title: `${column.name}${column.unit ? `（${column.unit}）` : ''}`, render: (_: unknown, row: {values: Record<string, string | number>}) => String(row.values[column.code] ?? '—') }))]} /></section>
        <section><h3>版本记录</h3>{modelDetail.releases.length ? modelDetail.releases.map((release) => <div className="release-row" key={release.id}><Tag>{releaseActionLabel[release.action] ?? '状态变更'}</Tag><span>{release.reason}</span><small>{when(release.createdAt)}</small></div>) : <Typography.Text type="secondary">尚无启用或回退记录</Typography.Text>}</section>
        {canPublish && <div className="drawer-actions">{modelDetail.model.status === 'ACTIVE' && <Button onClick={() => setAction({ type: 'pause', model: modelDetail.model })}>暂停使用</Button>}{['PAUSED', 'RETIRED'].includes(modelDetail.model.status) && <Button type="primary" onClick={() => setAction({ type: 'rollback', model: modelDetail.model })}>回退到此版本</Button>}</div>}
      </div>}
    </Drawer>

    <Modal open={Boolean(action)} title={action?.type === 'pause' ? '暂停模型' : '回退模型'} okText="确认" cancelText="取消" onCancel={() => setAction(undefined)} onOk={() => void submitAction()}><Form form={actionForm} layout="vertical"><Form.Item name="reason" label="操作原因" rules={[{ required: true, message: '请填写操作原因' }]}><Input.TextArea rows={3} maxLength={300} /></Form.Item></Form></Modal>
  </main>
}
