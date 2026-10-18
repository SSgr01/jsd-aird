import { XYColumnPicker, SelectedDataIssues } from '@/components/ai-rnd/XYColumnPicker'
import { ArrowLeftOutlined, ArrowRightOutlined, CheckCircleFilled, ExperimentOutlined, ReloadOutlined } from '@ant-design/icons'
import { Alert, App, Button, Card, Checkbox, Empty, Input, Progress, Space, Table, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { Key } from 'react'

import { modelingWorkbenchApi, type ModelingWorkbenchRun } from '@/services/ai-rnd/modeling-workbench-api'
import type { HistoryDataset, HistoryDatasetOption, HistoryDatasetRow } from '@/services/ai-rnd/optimization-api'
import { HttpError } from '@/services/http/errors'
import { useAuthStore } from '@/stores/auth-store'
import { TrainingEvaluation } from '@/components/ai-rnd/TrainingEvaluation'
import { WorkbenchDataChecks } from '@/components/ai-rnd/WorkbenchDataChecks'
import { checkWorkbenchData } from '@/services/ai-rnd/workbench-data-checks'
import { mergeHistoryDatasets, selectedRowIdsByDataset } from '@/services/ai-rnd/history-dataset-utils'
import { workbenchFailureMessage, workbenchRegistrationMessage, workbenchRequestMessage } from './modeling-workbench-feedback'
import { TestMetricCatalogManager } from './TestMetricCatalogManager'
import './modeling-workbench.css'

const { Paragraph, Text, Title } = Typography
const stepTitles = ['选择数据', '选择 X / Y', '训练结果']
const algorithmLabels: Record<string, string> = {
  LINEAR_REGRESSION: '线性回归 Linear Regression',
  RIDGE: '岭回归 Ridge',
  GAUSSIAN_PROCESS: '高斯过程 Gaussian Process',
  RANDOM_FOREST: '随机森林 Random Forest',
  LIGHTGBM: 'LightGBM',
  XGBOOST: 'XGBoost',
  CATBOOST: 'CatBoost',
  LOGISTIC_REGRESSION: '逻辑回归 Logistic Regression',
}
type SavedDraft = { datasetId?: string; datasetIds?: string[]; selectedRowIdsByDataset?: Record<string, string[]>; xColumns?: string[]; yColumn?: string; runId?: string; activeStep?: number }
type PendingTraining = { key: string; request: { datasetIds: string[]; selectedRowIdsByDataset: Record<string, string[]>; xColumns: string[]; yColumn: string }; runId?: string }

function cellText(value: unknown) {
  if (typeof value === 'number') return Number.isInteger(value) ? String(value) : String(Number(value.toFixed(4)))
  if (typeof value === 'string' && value.trim()) return value
  return '—'
}

export type ModelingWorkbenchPageProps = {
  canManageCatalog?: boolean
  canRegister?: boolean
  onGoPredict?: (targetId: string) => void
  onOpenModel?: (targetId: string, modelId: string) => void
  canTrain?: boolean
}

export function ModelingWorkbenchPage(props: ModelingWorkbenchPageProps) {
  const user = useAuthStore(state => state.user)
  const storageKey = user ? `ai-modeling-workbench:v2:${user.organizationId}:${user.userId}` : 'ai-modeling-workbench:v2:anonymous'
  return <ModelingWorkbenchPageInner key={storageKey} storageKey={storageKey} {...props} />
}

function ModelingWorkbenchPageInner({ canManageCatalog = false, canRegister = false, onGoPredict, onOpenModel, canTrain = true, storageKey }: ModelingWorkbenchPageProps & { storageKey: string }) {
  const { message } = App.useApp()
  const draftKey = `${storageKey}:draft`
  const operationKey = `${storageKey}:pending-training`
  const [datasets, setDatasets] = useState<HistoryDatasetOption[]>([])
  const [datasetKeyword, setDatasetKeyword] = useState('')
  const [selectedDatasetIds, setSelectedDatasetIds] = useState<string[]>([])
  const [datasetCache, setDatasetCache] = useState<Record<string, HistoryDataset>>({})
  const [frozenDataset, setFrozenDataset] = useState<HistoryDataset>()
  const [selectedRowIds, setSelectedRowIds] = useState<Record<string, string[]>>({})
  const [xColumns, setXColumns] = useState<string[]>([])
  const [yColumn, setYColumn] = useState('')
  const [activeStep, setActiveStep] = useState(0)
  const [runId, setRunId] = useState<string>()
  const [pendingTraining, setPendingTraining] = useState<PendingTraining | undefined>(() => {
    try { return JSON.parse(window.localStorage.getItem(operationKey) ?? 'null') as PendingTraining | null ?? undefined }
    catch { return undefined }
  })
  const restoredRun = useRef<string>()
  const userNavigated = useRef(false)
  const [run, setRun] = useState<ModelingWorkbenchRun>()
  const [loadingDatasets, setLoadingDatasets] = useState(false)
  const [loadingDataset, setLoadingDataset] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [registering, setRegistering] = useState(false)
  const [registrationError, setRegistrationError] = useState('')
  const registerModel = async () => {
    if (!run || run.status !== 'SUCCEEDED' || registering || !canRegister || run.registration) return
    setRegistering(true); setRegistrationError('')
    try {
      const registration = await modelingWorkbenchApi.register(run.id, `register:${run.id}`)
      setRun(current => current?.id === run.id ? { ...current, registration } : current)
      message.success('模型已注册，可用于预测')
    } catch (error) { setRegistrationError(workbenchRegistrationMessage(error)) }
    finally { setRegistering(false) }
  }
  const [loadError, setLoadError] = useState('')
  const [runLoadError, setRunLoadError] = useState('')
  const [draftReady, setDraftReady] = useState(false)

  const loadDatasets = useCallback(async (keyword = '') => {
    setLoadingDatasets(true)
    setLoadError('')
    try { setDatasets((await modelingWorkbenchApi.datasets(keyword)).items) }
    catch (error) { setLoadError(workbenchRequestMessage(error)) }
    finally { setLoadingDatasets(false) }
  }, [])

  const navigateToStep = (step: number) => {
    userNavigated.current = true
    setActiveStep(step)
  }

  const sourceDatasets = useMemo(() => selectedDatasetIds.map(id => datasetCache[id]).filter((item): item is HistoryDataset => Boolean(item)), [datasetCache, selectedDatasetIds])
  const liveDataset = useMemo(() => sourceDatasets.length === selectedDatasetIds.length ? mergeHistoryDatasets(sourceDatasets) : undefined, [selectedDatasetIds, sourceDatasets])
  const dataset = runId && frozenDataset ? frozenDataset : liveDataset

  const toggleDataset = (ids: Key[]) => {
    if (pendingTraining) return
    const nextIds = ids.map(String)
    if (nextIds.join('|') === selectedDatasetIds.join('|')) return
    setSelectedDatasetIds(nextIds)
    setRun(undefined); setRunId(undefined); setFrozenDataset(undefined)
    setPendingTraining(undefined); window.localStorage.removeItem(operationKey)
  }

  useEffect(() => {
    const missing = selectedDatasetIds.filter(id => !datasetCache[id])
    if (!missing.length) { setLoadingDataset(false); return }
    let cancelled = false
    setLoadingDataset(true)
    setLoadError('')
    void Promise.all(missing.map(id => modelingWorkbenchApi.dataset(id))).then(values => {
      if (cancelled) return
      setDatasetCache(current => ({ ...current, ...Object.fromEntries(values.map(value => [value.id, value])) }))
    }).catch(error => {
      if (!cancelled) setLoadError(workbenchRequestMessage(error))
    }).finally(() => {
      if (!cancelled) setLoadingDataset(false)
    })
    return () => { cancelled = true }
  }, [datasetCache, selectedDatasetIds])

  useEffect(() => {
    if (!dataset) return
    const availableCodes = new Set(dataset.columns.map(column => column.code))
    const staleRows = !runId && Object.entries(selectedRowIds).some(([id, ids]) => selectedDatasetIds.includes(id) && ids.some(rowId => !dataset.rows.some(row => row.id === rowId)))
    const staleFields = !runId && (xColumns.some(code => !availableCodes.has(code)) || Boolean(yColumn && !availableCodes.has(yColumn)))
    if (staleRows || staleFields) {
      message.warning({ key: 'modeling-sample-refresh', content: staleRows ? '实验数据已按样品重新整理，请重新勾选记录并确认 X/Y。' : '所选数据表中有字段已变化，请检查并重新确认 X/Y。' })
      setXColumns(current => current.filter(code => availableCodes.has(code)))
      setYColumn(current => availableCodes.has(current) ? current : '')
      setActiveStep(staleRows ? 0 : 1)
    }
    setSelectedRowIds(current => {
      let changed = false
      const next = { ...current }
      for (const id of dataset.sourceDatasetIds ?? []) {
        const available = dataset.rows.filter(row => row.sourceDatasetId === id).map(row => row.id)
        if (!Object.prototype.hasOwnProperty.call(next, id)) {
          next[id] = available
          changed = true
        } else {
          const filtered = next[id]!.filter(rowId => available.includes(rowId))
          if (filtered.length !== next[id]!.length) { next[id] = filtered; changed = true }
        }
      }
      return changed ? next : current
    })
  }, [dataset])

  useEffect(() => {
    let cancelled = false
    const restore = async () => {
      let saved: SavedDraft = {}
      try { saved = JSON.parse(window.localStorage.getItem(draftKey) ?? '{}') as SavedDraft } catch { /* discard malformed local draft */ }
      setLoadingDatasets(true)
      try {
        const response = await modelingWorkbenchApi.datasets()
        if (cancelled) return
        setDatasets(response.items)
        const savedDatasetIds = saved.datasetIds ?? (saved.datasetId ? [saved.datasetId] : [])
        const availableIds = savedDatasetIds.filter(id => response.items.some(item => item.id === id))
        if (availableIds.length) {
          setSelectedDatasetIds(availableIds)
          setSelectedRowIds(saved.selectedRowIdsByDataset ?? {})
          setXColumns(saved.xColumns ?? [])
          setYColumn(saved.yColumn ?? '')
          if (!userNavigated.current) setActiveStep(saved.activeStep === 0 ? 0 : saved.activeStep === 1 ? 1 : saved.runId ? 2 : 1)
        }
        if (saved.runId) setRunId(saved.runId)
      } catch (error) {
        if (!cancelled) setLoadError(workbenchRequestMessage(error))
      } finally {
        if (!cancelled) { setLoadingDatasets(false); setDraftReady(true) }
      }
    }
    void restore()
    return () => { cancelled = true }
  }, [])

  useEffect(() => {
    if (!draftReady) return
    const draft: SavedDraft = { datasetIds: selectedDatasetIds, selectedRowIdsByDataset: selectedRowIds, xColumns, yColumn, runId, activeStep }
    window.localStorage.setItem(draftKey, JSON.stringify(draft))
  }, [activeStep, draftReady, runId, selectedDatasetIds, selectedRowIds, xColumns, yColumn])

  useEffect(() => {
    setRunLoadError('')
    if (!runId) { setRun(undefined); return }
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined
    const poll = async () => {
      try {
        const current = await modelingWorkbenchApi.run(runId)
        if (cancelled) return
        setRun(current)
        setRunLoadError('')
        if (current.datasetSnapshot?.rows && restoredRun.current !== current.id) {
          restoredRun.current = current.id
          const snapshot = current.datasetSnapshot
          setFrozenDataset(snapshot)
          const ids = snapshot.sourceDatasetIds ?? [snapshot.id]
          setSelectedDatasetIds(ids)
          setSelectedRowIds(current.selectedRowIdsByDataset ?? {})
          setXColumns(current.xColumns.map(column => column.code)); setYColumn(current.yColumn.code)
        }
        if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(current.status)) {
          setPendingTraining(undefined); window.localStorage.removeItem(operationKey)
        }
        if (current.status === 'QUEUED' || current.status === 'RUNNING') timer = setTimeout(() => void poll(), 1300)
      } catch (error) {
        if (!cancelled) {
          if (error instanceof HttpError && error.status === 404) {
            setRun(undefined); setRunId(undefined); setFrozenDataset(undefined); setPendingTraining(undefined); setSubmitting(false)
            window.localStorage.removeItem(operationKey)
            setLoadError('这条训练任务已不存在，已保留建模设置。')
            setActiveStep(0)
            return
          }
          setRunLoadError(workbenchRequestMessage(error))
          if (!(error instanceof HttpError && [401, 403, 404].includes(error.status ?? 0))) timer = setTimeout(() => void poll(), 2500)
        }
      }
    }
    void poll()
    return () => { cancelled = true; if (timer) clearTimeout(timer) }
  }, [runId])

  const selectedIdsByDataset = useMemo(() => selectedRowIdsByDataset(selectedDatasetIds, dataset?.rows ?? [], selectedRowIds), [dataset?.rows, selectedDatasetIds, selectedRowIds])
  const selectedRows = useMemo(() => dataset?.rows.filter(row => selectedIdsByDataset[row.sourceDatasetId ?? dataset.id]?.includes(row.id)) ?? [], [dataset, selectedIdsByDataset])
  const allRowsSelected = dataset ? dataset.rows.length > 0 && selectedRows.length === dataset.rows.length : false
  const someRowsSelected = selectedRows.length > 0 && !allRowsSelected
  const selectAllRows = (checked: boolean) => {
    if (!dataset) return
    const sourceIds = dataset.sourceDatasetIds ?? [dataset.id]
    setSelectedRowIds(current => ({
      ...current,
      ...Object.fromEntries(sourceIds.map(id => [
        id,
        checked ? dataset.rows.filter(row => (row.sourceDatasetId ?? dataset.id) === id).map(row => row.id) : [],
      ])),
    }))
  }
  const selectRow = (row: HistoryDatasetRow, checked: boolean) => {
    if (!dataset) return
    const sourceId = row.sourceDatasetId ?? dataset.id
    setSelectedRowIds(current => {
      const ids = new Set(current[sourceId] ?? dataset.rows
        .filter(item => (item.sourceDatasetId ?? dataset.id) === sourceId)
        .map(item => item.id))
      if (checked) ids.add(row.id)
      else ids.delete(row.id)
      return { ...current, [sourceId]: [...ids] }
    })
  }
  const selectedDatasetView = useMemo<HistoryDataset | undefined>(() => dataset ? ({
    ...dataset,
    rows: selectedRows,
    columns: dataset.columns.map(column => ({
      ...column,
      filledCount: selectedRows.filter(row => row.values[column.code] !== undefined && row.values[column.code] !== null && row.values[column.code] !== '').length,
    })),
  }) : undefined, [dataset, selectedRows])

  const columns = useMemo<ColumnsType<HistoryDatasetRow>>(() => [
    ...(selectedDatasetIds.length > 1 ? [{ title: '来源表', dataIndex: 'sourceDatasetName', key: 'sourceDatasetName', fixed: 'left' as const, width: 160, ellipsis: true }] : []),
    { title: '实验记录', dataIndex: 'label', key: 'label', fixed: 'left', width: 170, render: (value: string, row) => <Space direction="vertical" size={0}><Text strong>{value || row.title || '未命名'}</Text><Text type="secondary">{row.title}</Text></Space> },
    ...(dataset?.columns ?? []).map(column => ({
      title: <span>{column.name}{column.unit ? <Text type="secondary"> · {column.unit}</Text> : null}</span>,
      key: column.code, width: 150,
      render: (_: unknown, row: HistoryDatasetRow) => cellText(row.values[column.code]),
    })),
  ], [dataset?.columns, selectedDatasetIds.length])

  const dataChecks = useMemo(() => checkWorkbenchData(selectedDatasetView, xColumns, yColumn), [selectedDatasetView, xColumns, yColumn])
  const completeRows = dataChecks?.usableRowCount ?? 0
  const allXConstant = Boolean(dataChecks && dataChecks.fields.filter(field => field.role === 'X').every(field => field.distinctCount < 2))
  const currentMetrics = run?.result?.metrics ?? {}
  const selectedAlgorithm = typeof currentMetrics.selectedAlgorithm === 'string' ? currentMetrics.selectedAlgorithm : ''
  const trainingActive = submitting || Boolean(pendingTraining) || Boolean(runId) && (run ? run.status === 'QUEUED' || run.status === 'RUNNING' : !runLoadError)

  useEffect(() => {
    if (!pendingTraining) return
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined
    if (pendingTraining.runId) {
      setRunId(pendingTraining.runId); if (!userNavigated.current) setActiveStep(2); setSubmitting(false)
      return
    }
    setSubmitting(true)
    const send = async () => {
      try {
        const accepted = await modelingWorkbenchApi.train(pendingTraining.request, pendingTraining.key)
        if (cancelled) return
        setLoadError('')
        const confirmed = { ...pendingTraining, runId: accepted.runId }
        try { window.localStorage.setItem(operationKey, JSON.stringify(confirmed)) } catch { /* the saved request can still be retried with the same key */ }
        setPendingTraining(confirmed); setRunId(accepted.runId); setRun(undefined); if (!userNavigated.current) setActiveStep(2); setSubmitting(false)
      } catch (error) {
        if (cancelled) return
        const retryable = !(error instanceof HttpError) || error.status == null || error.status === 408 || error.status >= 500
        if (retryable) {
          setLoadError('训练请求尚未确认，正在使用同一请求重新连接。')
          timer = setTimeout(() => { void send() }, 3000)
          return
        }
        setLoadError(workbenchRequestMessage(error)); setSubmitting(false)
        setPendingTraining(undefined); window.localStorage.removeItem(operationKey)
      }
    }
    void send()
    return () => { cancelled = true; if (timer) clearTimeout(timer) }
  }, [operationKey, pendingTraining?.key, pendingTraining?.runId])

  const startTraining = async () => {
    if (submitting || trainingActive) return
    if (!selectedDatasetIds.length || !dataset) { message.warning('先选择实验数据表。'); return }
    if (!selectedRows.length) { message.warning('请至少勾选一条实验记录。'); return }
    if (!yColumn || !xColumns.length) { message.warning('请分别选择要预测的 Y 和作为输入的 X 列。'); return }
    if (allXConstant) { message.warning('所选 X 在完整记录中都没有变化，请选择有不同取值的输入列。'); return }
    if (completeRows < 6) { message.warning(`当前组合只有 ${completeRows} 条完整记录，至少需要 6 条。`); return }
    setLoadError('')
    // Navigation performed while configuring X/Y should not suppress the post-submit
    // transition. Only navigation after this submission starts should take priority.
    userNavigated.current = false
    try {
      const key = globalThis.crypto?.randomUUID?.() ?? `modeling-${Date.now()}-${Math.random().toString(16).slice(2)}`
      const request = {
        datasetIds: selectedDatasetIds,
        selectedRowIdsByDataset: selectedIdsByDataset,
        xColumns,
        yColumn,
      }
      const pending = { key, request }
      window.localStorage.setItem(operationKey, JSON.stringify(pending))
      setPendingTraining(pending); setSubmitting(true)
    } catch (error) {
      setLoadError(error instanceof DOMException && error.name === 'QuotaExceededError'
        ? '训练请求未提交：浏览器无法保存恢复信息，请清理本地空间后重试。'
        : workbenchRequestMessage(error))
    }
  }

  const clearRun = () => {
    setRunId(undefined)
    setRun(undefined)
    setFrozenDataset(undefined)
    setActiveStep(0)
    setXColumns([])
    setYColumn('')
    setLoadError('')
    setPendingTraining(undefined); window.localStorage.removeItem(operationKey)
    window.localStorage.removeItem(draftKey)
  }

  return <main className="modeling-workbench-page">
    <nav className="modeling-workbench-steps" aria-label="建模步骤">
      {stepTitles.map((title, index) => {
        const available = index === 0 || (index === 1 && Boolean(dataset)) || (index === 2 && Boolean(runId))
        return <button key={title} type="button" aria-current={activeStep === index ? 'step' : undefined} className={activeStep === index ? 'active' : ''} disabled={!available} onClick={() => navigateToStep(index)}>
          <span className="workbench-step-number">{index < activeStep ? <CheckCircleFilled /> : index + 1}</span>
          <span>{title}</span>
        </button>
      })}
    </nav>

    {loadError && <Alert className="modeling-workbench-error" type="error" showIcon message={loadError} closable onClose={() => setLoadError('')} />}

    {activeStep === 0 && <section className="modeling-workbench-panel">
      <div className="workbench-panel-heading">
      <div><Title level={3}>选择实验数据并预览原表</Title><Paragraph>这里只显示已完成的实验数据；按实验表单整理为数据表。可合并多张表，也可在预览中取消不需要的实验记录。</Paragraph></div>
        <Input.Search allowClear value={datasetKeyword} placeholder="搜索数据名称" onSearch={value => { setDatasetKeyword(value); void loadDatasets(value) }} onChange={event => { setDatasetKeyword(event.target.value); if (!event.target.value) void loadDatasets('') }} style={{ maxWidth: 280 }} />
      </div>
      <div className="workbench-data-layout">
        <Card className="workbench-dataset-card" title={<Space>实验数据表<Text type="secondary">共 {datasets.length} 张</Text></Space>} extra={<Button type="text" icon={<ReloadOutlined />} aria-label="刷新实验数据" onClick={() => void loadDatasets(datasetKeyword)}>刷新</Button>}>
          <Table<HistoryDatasetOption> size="small" rowKey="id" loading={loadingDatasets} dataSource={datasets} pagination={{ pageSize: 6, showSizeChanger: false }}
            rowSelection={{ selectedRowKeys: selectedDatasetIds, onChange: toggleDataset, getCheckboxProps: () => ({ disabled: trainingActive }) }}
            rowClassName={row => selectedDatasetIds.includes(row.id) ? 'selected-dataset-row' : ''}
            columns={[
              { title: '数据名称', dataIndex: 'name', ellipsis: true, render: (value: string, row) => <Button type="link" disabled={trainingActive} className="workbench-dataset-link" onClick={() => toggleDataset(selectedDatasetIds.includes(row.id) ? selectedDatasetIds.filter(id => id !== row.id) : [...selectedDatasetIds, row.id])}>{value}</Button> },
              { title: '记录数', dataIndex: 'rowCount', width: 72, align: 'right' },
            ]}
            locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={loadError ? '数据暂时无法读取' : '没有已完成的实验数据'} /> }} />
        </Card>
        <Card className="workbench-preview-card" title={dataset ? <Space wrap><span>原表预览</span><Tag color="blue">{selectedDatasetIds.length} 张表</Tag><Tag>{dataset.rows.length} 条记录</Tag><Tag color="green">已选 {selectedRows.length} 条</Tag></Space> : '原表预览'}>
          {!selectedDatasetIds.length ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="先从上方勾选一张或多张实验数据表" />
            : <>
              {dataset && selectedDatasetIds.length > 1 && !dataset.columns.length && <Alert type="warning" showIcon message="这些数据表没有可共同使用的字段" description="请取消不匹配的数据表，选择字段名称、单位和数据类型一致的表后再继续。" />}
              {dataset && <Table<HistoryDatasetRow> size="small" rowKey="id" loading={loadingDataset} columns={columns} dataSource={dataset.rows}
                rowSelection={{
                  selectedRowKeys: selectedRows.map(row => row.id), preserveSelectedRowKeys: true,
                  columnTitle: <Checkbox aria-label="全选实验记录" checked={allRowsSelected} indeterminate={someRowsSelected} disabled={trainingActive || loadingDataset || !dataset.rows.length} onChange={event => selectAllRows(event.target.checked)} />,
                  renderCell: (_checked, row) => <Checkbox aria-label={`选择实验记录 ${row.label || row.title || row.id}`} checked={selectedRows.some(item => item.id === row.id)} disabled={trainingActive || loadingDataset} onChange={event => selectRow(row, event.target.checked)} />,
                  hideSelectAll: true,
                }}
                pagination={{ pageSize: 8, showSizeChanger: false, showTotal: total => `共 ${total} 条` }} scroll={{ x: 'max-content' }} />}
            </>}
        </Card>
      </div>
      <footer className="workbench-step-footer"><Text type="secondary">{trainingActive ? '训练中，可查看原表；完成后可更换数据。' : selectedRows.length < 6 ? `至少勾选 6 条记录后继续；可合并多张表（还差 ${6 - selectedRows.length} 条）。` : '只有勾选且 X/Y 都有值的记录会用于训练。'}</Text><Button type="primary" disabled={!dataset || !dataset.columns.length || selectedRows.length < 6 || loadingDataset} onClick={() => navigateToStep(1)}>下一步：选择 X / Y <ArrowRightOutlined /></Button></footer>
    </section>}

    {activeStep === 1 && <section className="modeling-workbench-panel">
      <div className="workbench-panel-heading">
        <div><Title level={3}>选择输入 X 和预测结果 Y</Title><Paragraph>同一列不能同时作为 X 和 Y。</Paragraph></div>
        <Space wrap>{dataset && <Tag color="blue">{dataset.name} · 已选 {selectedRows.length}/{dataset.rows.length} 条</Tag>}<TestMetricCatalogManager canManage={canManageCatalog} /></Space>
      </div>
      {!dataset ? <Empty description="实验数据没有加载，请返回重新选择。"><Button onClick={() => navigateToStep(0)}>返回选择数据</Button></Empty> : <>
        {selectedDatasetView && <XYColumnPicker dataset={selectedDatasetView} mode="modeling" xColumns={xColumns} yColumn={yColumn} onXChange={setXColumns} onYChange={value => { setYColumn(value); setXColumns(current => current.filter(code => code !== value)) }} disabled={Boolean(pendingTraining)} />}
        <SelectedDataIssues dataset={selectedDatasetView} xColumns={xColumns} yColumn={yColumn} />
        <div className="workbench-readiness-row">
          <div><Text strong>可用于训练</Text><Text className={completeRows >= 6 ? 'workbench-ready-count' : 'workbench-warning-count'}>{completeRows} 条完整记录</Text><Text type="secondary">至少需要 6 条完整记录，且至少一列 X 有不同取值。</Text></div>
          <div><Text strong>训练方式</Text><Text>系统自动比较多种预测方法</Text><Text type="secondary">训练结果会用交叉验证指标进行对比。</Text></div>
        </div>
        <WorkbenchDataChecks checks={dataChecks} />
        <footer className="workbench-step-footer"><Button icon={<ArrowLeftOutlined />} onClick={() => navigateToStep(0)}>返回原表</Button><Button type="primary" icon={<ExperimentOutlined />} loading={submitting} disabled={!canTrain || trainingActive || !yColumn || !xColumns.length || completeRows < 6 || allXConstant} onClick={() => void startTraining()}>{!canTrain ? '没有训练权限' : trainingActive ? '训练中' : '开始训练'}</Button></footer>
      </>}
    </section>}

    {activeStep === 2 && <section className="modeling-workbench-panel">
      <div className="workbench-panel-heading">
        <div><Title level={3}>训练结果</Title></div>
        <Button icon={<ReloadOutlined />} onClick={() => { if (runId) void modelingWorkbenchApi.run(runId).then(current => { setRun(current); setRunLoadError('') }).catch(error => setRunLoadError(workbenchRequestMessage(error))) }}>刷新结果</Button>
      </div>
      {runLoadError && run && <Alert type="error" showIcon message={runLoadError} />}
      {!runId ? <Empty description="还没有训练任务"><Button type="primary" onClick={() => setActiveStep(dataset ? 1 : 0)}>返回选择</Button></Empty>
        : runLoadError && !run ? <div className="workbench-result-error"><Alert type="error" showIcon message="训练记录暂时无法读取" description={runLoadError} /><Button onClick={() => setActiveStep(0)}>返回选择数据</Button></div>
        : !run || run.status === 'QUEUED' || run.status === 'RUNNING' ? <div className="workbench-running-card">
          <Progress percent={run?.progress ?? 5} status="active" />
          <Title level={4}>{run?.status === 'RUNNING' ? '正在训练并检查模型表现' : '训练任务已提交'}</Title>
          <Text type="secondary">进度和结果会自动保存。</Text>
        </div> : run.status === 'FAILED' || run.status === 'CANCELLED' ? <div className="workbench-result-error">
          <Alert type="error" showIcon message={run.status === 'CANCELLED' ? '训练已取消' : '训练没有完成'} description={run.status === 'CANCELLED' ? undefined : workbenchFailureMessage(run.error)} />
          <Space><Button onClick={() => setActiveStep(1)}>调整 X/Y</Button><Button type="primary" onClick={() => void startTraining()}>重新训练</Button></Space>
        </div> : <>
          <div className="workbench-result-banner"><CheckCircleFilled /><div><strong>训练完成</strong><span>使用 {run.result?.rowCount ?? run.rowCount} 条完整实验记录 · {algorithmLabels[selectedAlgorithm] ?? (selectedAlgorithm || '模型方法')}</span></div><Tag color="success">已保存</Tag></div>
          <div className="workbench-result-summary">
            <div><span>实验数据</span><strong>{run.datasetName}</strong></div>
            <div><span>勾选记录</span><strong>{run.selectedRowCount ?? run.rowCount} 条</strong></div>
            <div><span>参与训练</span><strong>{run.result?.rowCount ?? run.rowCount} 条</strong></div>
            <div><span>未参与训练</span><strong>{Math.max(0, (run.selectedRowCount ?? run.rowCount) - (run.result?.rowCount ?? run.rowCount))} 条</strong></div>
            <div><span>输入 X</span><strong>{run.xColumns.map(column => column.name).join('、')}</strong></div>
            <div><span>预测结果 Y</span><strong>{run.yColumn.name}{run.yColumn.unit ? ` · ${run.yColumn.unit}` : ''}</strong></div>
          </div>
          <Card className="workbench-metric-card" title="交叉验证表现" extra={<Text type="secondary">用未参与每轮训练的数据检查预测效果</Text>}>
            <TrainingEvaluation metrics={currentMetrics} numeric={run.yColumn.kind === 'NUMERIC'} unit={run.yColumn.unit} />
          </Card>
          {registrationError && <Alert type="error" showIcon message={registrationError} />}
          <footer className="workbench-step-footer"><Button onClick={() => setActiveStep(1)}>返回调整 X/Y</Button><Space>
            <Button onClick={clearRun}>开始另一项建模</Button>
            {run.registration ? <><Tag color="success">已注册 · 第 {run.registration.version} 版</Tag>{onGoPredict && <Button type="primary" onClick={() => onGoPredict(run.registration!.targetId)}>去预测</Button>}{onOpenModel && <Button onClick={() => onOpenModel(run.registration!.targetId, run.registration!.modelVersionId)}>查看模型</Button>}</> : canRegister && <Button type="primary" loading={registering} onClick={() => void registerModel()}>注册模型</Button>}
          </Space></footer>
        </>}
    </section>}

  </main>
}
