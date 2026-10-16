/* eslint-disable @typescript-eslint/no-explicit-any, @typescript-eslint/no-unsafe-assignment, @typescript-eslint/no-unsafe-member-access, @typescript-eslint/no-unsafe-call, @typescript-eslint/no-unsafe-argument, @typescript-eslint/no-unsafe-return */
import { ReloadOutlined, RightOutlined } from '@ant-design/icons'
import { Alert, App, Button, Card, Checkbox, Col, Empty, Input, InputNumber, Progress, Row, Select, Space, Steps, Table, Tag, Typography } from 'antd'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { Key } from 'react'
import { aiActionFeedback } from '@/services/ai-rnd/action-feedback'
import { optimizationApi, type HistoryDataset, type HistoryDatasetColumn, type HistoryDatasetOption, type OptimizationObservation, type OptimizationRun } from '@/services/ai-rnd/optimization-api'
import { mergeHistoryDatasets, selectedRowIdsByDataset } from '@/services/ai-rnd/history-dataset-utils'
import { generateUUID } from '@/utils/uuid'
import './historical-optimization-workbench.css'

const { Title, Text, Paragraph } = Typography
const runStorageKey = 'r09.gpOptimizationRunId'
const setupStorageKey = 'r09.gpOptimizationSetup'
const stepTitles = ['选择数据', '选择 X 和 Y', '设置范围', '查看方案']

const numericValues = (dataset: HistoryDataset | undefined, code: string) => (dataset?.rows ?? [])
  .map(row => row.values[code]).filter((value): value is number => typeof value === 'number' && Number.isFinite(value))
const uniqueValues = (dataset: HistoryDataset | undefined, code: string) => new Set((dataset?.rows ?? [])
  .map(row => row.values[code]).filter(value => value !== undefined && value !== null && value !== '')).size
const candidatePrediction = (candidate: any) => candidate?.results?.targets?.[0]?.result ?? {}
const valueText = (value: unknown, unit?: string, significantDigits = 6) => {
  const number = Number(value)
  return Number.isFinite(number) ? `${Number(number.toPrecision(significantDigits))}${unit ? ` ${unit}` : ''}` : '—'
}

export function HistoricalOptimizationWorkbench() {
  const { message } = App.useApp()
  const [keyword, setKeyword] = useState('')
  const [datasets, setDatasets] = useState<HistoryDatasetOption[]>([])
  const [selectedDatasetIds, setSelectedDatasetIds] = useState<string[]>([])
  const [datasetCache, setDatasetCache] = useState<Record<string, HistoryDataset>>({})
  const [selectedRowIds, setSelectedRowIds] = useState<Record<string, string[]>>({})
  const [xColumns, setXColumns] = useState<string[]>([])
  const [yColumn, setYColumn] = useState<string>()
  const [direction, setDirection] = useState<'MAXIMIZE'|'MINIMIZE'>('MAXIMIZE')
  const [bounds, setBounds] = useState<Record<string, {minimum?: number; maximum?: number}>>({})
  const [activeStep, setActiveStep] = useState(0)
  const [runId, setRunId] = useState<string>()
  const [run, setRun] = useState<OptimizationRun>()
  const [loadingDatasets, setLoadingDatasets] = useState(false)
  const [loadingDataset, setLoadingDataset] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [saving, setSaving] = useState(false)
  const [continuing, setContinuing] = useState(false)
  const [observations, setObservations] = useState<Record<string, number|undefined>>({})
  const [savedObservations, setSavedObservations] = useState<OptimizationObservation[]>([])
  const restoreStarted = useRef(false)
  const loadingDatasetIds = useRef(new Set<string>())
  const [setupReady, setSetupReady] = useState(false)

  const loadDatasets = async (search = keyword) => {
    setLoadingDatasets(true)
    try { setDatasets((await optimizationApi.historyDatasets(search)).items) }
    catch (error) { message.error(aiActionFeedback(error, '无法读取已完成的实验数据')) }
    finally { setLoadingDatasets(false) }
  }

  const sourceDatasets = useMemo(() => selectedDatasetIds.map(id => datasetCache[id]).filter((item): item is HistoryDataset => Boolean(item)), [datasetCache, selectedDatasetIds])
  const dataset = useMemo(() => sourceDatasets.length === selectedDatasetIds.length ? mergeHistoryDatasets(sourceDatasets) : undefined, [selectedDatasetIds, sourceDatasets])
  const rowIdsByDataset = useMemo(() => selectedRowIdsByDataset(selectedDatasetIds, dataset?.rows ?? [], selectedRowIds), [dataset?.rows, selectedDatasetIds, selectedRowIds])
  const selectedRows = useMemo(() => dataset?.rows.filter(row => rowIdsByDataset[row.sourceDatasetId ?? dataset.id]?.includes(row.id)) ?? [], [dataset, rowIdsByDataset])
  const selectedDatasetView = useMemo<HistoryDataset | undefined>(() => dataset ? ({
    ...dataset, rowCount: selectedRows.length, rows: selectedRows,
    columns: dataset.columns.map(column => ({ ...column, filledCount: selectedRows.filter(row => row.values[column.code] !== undefined && row.values[column.code] !== null && row.values[column.code] !== '').length })),
  }) : undefined, [dataset, selectedRows])

  const toggleDatasets = (ids: Key[]) => {
    const next = ids.map(String)
    if (next.join('|') === selectedDatasetIds.join('|')) return
    setSelectedDatasetIds(next); setXColumns([]); setYColumn(undefined); setBounds({}); setRun(undefined); setRunId(undefined)
    setSavedObservations([]); setObservations({}); sessionStorage.removeItem(runStorageKey)
  }

  useEffect(() => {
    const missing = selectedDatasetIds.filter(id => !datasetCache[id] && !loadingDatasetIds.current.has(id))
    if (!missing.length) {
      if (!selectedDatasetIds.some(id => loadingDatasetIds.current.has(id))) setLoadingDataset(false)
      return
    }
    missing.forEach(id => loadingDatasetIds.current.add(id))
    setLoadingDataset(true)
    void Promise.all(missing.map(id => optimizationApi.historyDataset(id))).then(values => {
      setDatasetCache(current => ({ ...current, ...Object.fromEntries(values.map(value => [value.id, value])) }))
    }).catch(error => { message.error(aiActionFeedback(error, '无法读取所选数据表')) }).finally(() => {
      missing.forEach(id => loadingDatasetIds.current.delete(id))
      setLoadingDataset(selectedDatasetIds.some(id => loadingDatasetIds.current.has(id)))
    })
  }, [datasetCache, message, selectedDatasetIds])

  useEffect(() => {
    if (!dataset || !selectedRows.length) return
    setBounds(current => {
      if (Object.keys(current).length) return current
      return Object.fromEntries(dataset.columns.filter(column => column.kind === 'NUMERIC').map(column => {
        const values = numericValues(selectedDatasetView, column.code)
        return values.length ? [column.code, { minimum: Math.min(...values), maximum: Math.max(...values) }] : [column.code, {}]
      }))
    })
  }, [dataset, selectedDatasetView, selectedRows.length])

  useEffect(() => {
    void loadDatasets('')
    try {
      const saved = JSON.parse(localStorage.getItem(setupStorageKey) ?? '{}') as { datasetIds?: string[]; selectedRowIdsByDataset?: Record<string, string[]>; xColumns?: string[]; yColumn?: string; activeStep?: number }
      if (saved.datasetIds?.length) { setSelectedDatasetIds(saved.datasetIds); setSelectedRowIds(saved.selectedRowIdsByDataset ?? {}); setXColumns(saved.xColumns ?? []); setYColumn(saved.yColumn); setActiveStep(saved.activeStep ?? 0) }
    } catch { /* ignore an incomplete local setup draft */ }
    setSetupReady(true)
  }, [])
  useEffect(() => {
    if (!setupReady) return
    localStorage.setItem(setupStorageKey, JSON.stringify({ datasetIds: selectedDatasetIds, selectedRowIdsByDataset: selectedRowIds, xColumns, yColumn, activeStep }))
  }, [activeStep, selectedDatasetIds, selectedRowIds, setupReady, xColumns, yColumn])
  useEffect(() => {
    const remembered = new URLSearchParams(location.search).get('optimizationRun') ?? sessionStorage.getItem(runStorageKey) ?? undefined
    if (remembered) { setRunId(remembered); setActiveStep(3) }
  }, [])

  useEffect(() => {
    if (!runId) return
    let active = true
    let timer: number | undefined
    const poll = async () => {
      try {
        const current = await optimizationApi.run(runId)
        if (!active) return
        setRun(current)
        const request: any = current.frozenRequest ?? {}
        if (request.mode === 'HISTORICAL_GP_BO' && !restoreStarted.current) {
          restoreStarted.current = true
          const sourceIds: string[] = Array.isArray(request.datasetIds) ? request.datasetIds.map(String) : [String(request.datasetId)]
          setSelectedDatasetIds(sourceIds)
          setSelectedRowIds(request.selectedRowIdsByDataset ?? {})
          try {
            const details = await Promise.all(sourceIds.map(id => optimizationApi.historyDataset(id)))
            if (!active) return
            setDatasetCache(cache => ({ ...cache, ...Object.fromEntries(details.map(detail => [detail.id, detail])) }))
          } catch {
            const rows = Array.isArray(request.rows) ? request.rows : []
            if (rows.length) {
              setSelectedDatasetIds([sourceIds[0]!])
              setDatasetCache(cache => ({ ...cache, [sourceIds[0]!]: {
              id: sourceIds[0]!, name: request.datasetName ?? current.frozenBaseline?.title ?? '历史实验数据', rowCount: rows.length,
              sourceDatasetIds: [sourceIds[0]!], columns: request.columns ?? [],
              rows: rows.map((row: any, index: number) => ({ id: `frozen-${index}`, versionId: '', label: `历史记录 ${index + 1}`, title: '', sourceDatasetId: sourceIds[0], values: row })),
            } }))
            }
          }
          const features = Array.isArray(request.features) ? request.features : []
          setXColumns(features.map((feature: any) => String(feature.code)))
          setYColumn(String(request.target ?? ''))
          setDirection(request.direction === 'MINIMIZE' ? 'MINIMIZE' : 'MAXIMIZE')
          setBounds(Object.fromEntries(features.filter((feature: any) => feature.kind === 'NUMERIC').map((feature: any) => [feature.code, { minimum: feature.minimum, maximum: feature.maximum }])))
        }
        if (['QUEUED', 'RUNNING'].includes(current.executionStatus)) timer = window.setTimeout(() => { void poll() }, 1200)
        else {
          try {
            const saved = (await optimizationApi.observations(runId)).observations
            if (active) {
              setSavedObservations(saved)
              setObservations(Object.fromEntries(saved.map(item => [`${item.candidateId}:${item.fieldCode ?? item.targetId}`, Number(item.value)])))
            }
          } catch { /* observation form remains available */ }
        }
      } catch (error) {
        if (active) message.error(aiActionFeedback(error, '读取优化结果失败，请刷新重试'))
      }
    }
    void poll()
    return () => { active = false; if (timer) window.clearTimeout(timer) }
  }, [runId])

  const numericColumns = useMemo(() => (selectedDatasetView?.columns ?? []).filter(column => column.kind === 'NUMERIC'), [selectedDatasetView])
  const completeRowCount = useMemo(() => {
    if (!selectedDatasetView || !yColumn || !xColumns.length) return 0
    return selectedDatasetView.rows.filter(row => {
      const y = row.values[yColumn]
      return typeof y === 'number' && Number.isFinite(y) && xColumns.every(code => {
        const value = row.values[code]
        return value !== undefined && value !== null && value !== '' && (typeof value !== 'number' || Number.isFinite(value))
      })
    }).length
  }, [selectedDatasetView, xColumns, yColumn])
  const yMeta = numericColumns.find(column => column.code === yColumn)
  const selectedFeatureColumns = xColumns.map(code => selectedDatasetView?.columns.find(column => column.code === code)).filter((column): column is HistoryDatasetColumn => Boolean(column))
  const formulaCodes = (selectedDatasetView?.columns ?? []).filter(column => column.code.startsWith('formula:')).map(column => column.code)
  const partialFormulaSelection = xColumns.some(code => code.startsWith('formula:')) && formulaCodes.some(code => !xColumns.includes(code))
  const hasSavedMeasurement = savedObservations.length > 0
  const canSubmit = Boolean(selectedDatasetView && selectedRows.length && yColumn && xColumns.length && completeRowCount >= 4 && !partialFormulaSelection && !selectedFeatureColumns.some(column => {
    if (column.kind !== 'NUMERIC') return false
    const range = bounds[column.code]
    return range?.minimum == null || range.maximum == null || range.minimum >= range.maximum
  }))

  const submit = async () => {
    if (!dataset || !selectedRows.length || !yColumn || !xColumns.length) { message.warning('先选择数据表、记录和 X/Y 列'); return }
    if (completeRowCount < 4) { message.warning(`当前 X/Y 组合只有 ${completeRowCount} 条完整记录，至少需要 4 条`); return }
    if (!canSubmit) { message.warning('请检查每个数值 X 因素的最小值和最大值'); return }
    setSubmitting(true)
    try {
      const accepted = await optimizationApi.submit({
        mode: 'HISTORICAL_GP_BO', datasetId: selectedDatasetIds[0], datasetIds: selectedDatasetIds,
        selectedRowIdsByDataset: rowIdsByDataset, xColumns, yColumn, direction, bounds,
        candidateCount: 4,
      }, generateUUID())
      setRun(undefined); setRunId(accepted.runId); setActiveStep(3); setSavedObservations([]); setObservations({})
      restoreStarted.current = false; sessionStorage.setItem(runStorageKey, accepted.runId)
      history.replaceState({}, '', `${location.pathname}?optimizationRun=${accepted.runId}`)
    } catch (error) { message.error(aiActionFeedback(error, '启动优化失败，请检查数据和选择范围')) }
    finally { setSubmitting(false) }
  }

  const saveMeasurements = async () => {
    if (!runId || !yColumn) return
    const values = (run?.candidates ?? []).map(candidate => ({ candidateId: candidate.id, fieldCode: yColumn, value: observations[`${candidate.id}:${yColumn}`] }))
      .filter((item): item is {candidateId: string; fieldCode: string; value: number} => Number.isFinite(item.value))
    if (!values.length) { message.warning('至少填写一个方案的 Y 实测值'); return }
    setSaving(true)
    try {
      const result = await optimizationApi.saveObservations(runId, values)
      setSavedObservations(result.observations)
      message.success(`已保存 ${result.savedCount} 条实测值`)
    } catch (error) { message.error(aiActionFeedback(error, '保存实测值失败')) }
    finally { setSaving(false) }
  }

  const startNextRound = async () => {
    if (!runId || !hasSavedMeasurement) return
    setContinuing(true)
    try {
      const accepted = await optimizationApi.nextRound(runId, generateUUID())
      setRun(undefined); setRunId(accepted.runId); setActiveStep(3); setSavedObservations([]); setObservations({})
      restoreStarted.current = false; sessionStorage.setItem(runStorageKey, accepted.runId)
      history.replaceState({}, '', `${location.pathname}?optimizationRun=${accepted.runId}`)
    } catch (error) { message.error(aiActionFeedback(error, '启动下一轮优化失败')) }
    finally { setContinuing(false) }
  }

  const previewColumns = useMemo(() => {
    const codes = [...new Set([...xColumns, ...(yColumn ? [yColumn] : [])])]
    const visible = codes.length ? codes : (dataset?.columns ?? []).slice(0, 5).map(column => column.code)
    return [
      ...(selectedDatasetIds.length > 1 ? [{ title: '来源表', dataIndex: 'sourceDatasetName', fixed: 'left' as const, width: 150, ellipsis: true }] : []),
      { title: '实验记录', dataIndex: 'label', fixed: 'left' as const, width: 130, ellipsis: true },
      ...visible.map(code => {
        const column = dataset?.columns.find(item => item.code === code)
        return { title: <span>{column?.name ?? code}{code === yColumn ? <Tag color="blue" className="history-role-tag">Y</Tag> : xColumns.includes(code) ? <Tag color="green" className="history-role-tag">X</Tag> : null}</span>, width: 150, render: (_: unknown, row: any) => <span>{row.values?.[code] ?? '—'}{row.values?.[code] !== undefined && column?.unit ? ` ${column.unit}` : ''}</span> }
      }),
    ]
  }, [dataset, selectedDatasetIds.length, xColumns, yColumn])

  const candidateColumns = [
    { title: '方案', dataIndex: 'title', fixed: 'left' as const, width: 110, render: (_: string, candidate: any) => <Text strong>{candidate.title}</Text> },
    ...selectedFeatureColumns.map(column => ({ title: <span>{column.name}{column.unit ? <Text type="secondary">（{column.unit}）</Text> : null}</span>, width: 140, render: (_: unknown, candidate: any) => { const value = candidate.inputs?.[column.code]; return <Text>{column.kind === 'NUMERIC' ? valueText(value, undefined, 8) : value ?? '—'}</Text> } })),
    { title: <span>预测 {yMeta?.name ?? 'Y'}{yMeta?.unit ? `（${yMeta.unit}）` : ''}</span>, width: 170, render: (_: unknown, candidate: any) => { const prediction = candidatePrediction(candidate); return <Space direction="vertical" size={0}><Text strong>{valueText(prediction.value)}</Text><Text type="secondary">范围 {valueText(prediction.lower)}～{valueText(prediction.upper)}</Text></Space> } },
    { title: <span>实测 {yMeta?.name ?? 'Y'}{yMeta?.unit ? `（${yMeta.unit}）` : ''}</span>, width: 180, render: (_: unknown, candidate: any) => <InputNumber aria-label={`${candidate.title}实测${yMeta?.name ?? 'Y'}`} className="history-observation-input" value={observations[`${candidate.id}:${yColumn}`]} onChange={value => setObservations(current => ({ ...current, [`${candidate.id}:${yColumn}`]: value ?? undefined }))} placeholder="完成实验后填写" /> },
  ]

  const steps = <div className="history-steps-card"><Steps current={activeStep} onChange={step => setActiveStep(step)} responsive={false} items={stepTitles.map(title => ({ title }))} /></div>

  return <main className="historical-optimization-page">
    <header className="historical-page-header">
      <div><Text className="historical-eyebrow">AI 研发助手 · 实验优化</Text><Title level={2}>实验优化</Title><Paragraph>选择一张或多张实验数据表，勾选要使用的记录，再选择 X 因素和 Y 指标。</Paragraph></div>
      <Button icon={<ReloadOutlined />} onClick={() => void loadDatasets()}>刷新数据</Button>
    </header>
    {steps}

    {activeStep === 0 && <Row gutter={[16,16]} className="history-layout">
      <Col xs={24} xl={8}><Card title="选择数据表" className="history-card" extra={<Text type="secondary">已选 {selectedDatasetIds.length} 张</Text>}>
        <Space.Compact className="history-search"><Input value={keyword} onChange={event => setKeyword(event.target.value)} onPressEnter={() => void loadDatasets()} placeholder="搜索实验模板"/><Button onClick={() => void loadDatasets()}>搜索</Button></Space.Compact>
        <div className="history-dataset-list">{datasets.length ? datasets.map(item => <button key={item.id} type="button" role="checkbox" aria-checked={selectedDatasetIds.includes(item.id)} disabled={submitting} className={`history-dataset-option ${selectedDatasetIds.includes(item.id) ? 'selected' : ''}`} onClick={() => toggleDatasets(selectedDatasetIds.includes(item.id) ? selectedDatasetIds.filter(id => id !== item.id) : [...selectedDatasetIds, item.id])}>
          <span className="history-selection-mark" aria-hidden="true">{selectedDatasetIds.includes(item.id) ? '✓' : ''}</span>
          <span><Text strong>{item.name}</Text><Text type="secondary">{item.rowCount} 条已完成实验</Text></span><RightOutlined />
        </button>) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={loadingDatasets ? '正在读取数据' : '没有可用的已完成实验数据'} />}</div>
      </Card></Col>
      <Col xs={24} xl={16}><Card title={dataset?.name ?? '数据预览'} className="history-card" extra={dataset ? <Space><Tag color="blue">{dataset.rowCount} 条记录</Tag><Tag color="green">已选 {selectedRows.length} 条</Tag></Space> : null}>
        {dataset ? <><Paragraph type="secondary" className="history-preview-hint">多张表按相同字段、数据类型和单位合并。默认全选记录，可逐条取消；未勾选的记录不会参与优化。</Paragraph>
          {selectedDatasetIds.length > 1 && !dataset.columns.length && <Alert type="warning" showIcon message="所选数据表没有共同字段" description="请取消不匹配的数据表，选择字段和单位一致的数据表后再继续。" />}
          <div className="history-table-actions"><Text type="secondary">已选 {selectedRows.length}/{dataset.rowCount} 条记录</Text><Space><Button size="small" disabled={loadingDataset || submitting} onClick={() => setSelectedRowIds(current => ({ ...current, ...Object.fromEntries((dataset.sourceDatasetIds ?? []).map(id => [id, dataset.rows.filter(row => row.sourceDatasetId === id).map(row => row.id)])) }))}>全选</Button><Button size="small" disabled={loadingDataset || submitting} onClick={() => setSelectedRowIds(current => ({ ...current, ...Object.fromEntries((dataset.sourceDatasetIds ?? []).map(id => [id, []])) }))}>清空</Button></Space></div>
          <Table size="small" rowKey="id" loading={loadingDataset} rowSelection={{ selectedRowKeys: selectedRows.map(row => row.id), hideSelectAll: true, onChange: keys => {
            const included = new Set(keys.map(String)); const sourceIds = dataset.sourceDatasetIds ?? [dataset.id]
            const grouped: Record<string, string[]> = Object.fromEntries(sourceIds.map(id => [id, []]))
            for (const row of dataset.rows) if (included.has(row.id)) (grouped[row.sourceDatasetId ?? dataset.id] ??= []).push(row.id)
            setSelectedRowIds(current => ({ ...current, ...grouped })); setBounds({})
          } }} pagination={{ pageSize: 8, showSizeChanger: false }} scroll={{ x: 'max-content' }} columns={previewColumns} dataSource={dataset.rows} />
          <div className="history-step-footer"><Text type="secondary">{selectedRows.length < 4 ? `至少勾选 4 条记录后继续；可合并多张表（还差 ${4 - selectedRows.length} 条）。` : '只有勾选且 Y 和所有 X 都有值的记录才会参与计算。'}</Text><Button type="primary" disabled={loadingDataset || selectedRows.length < 4 || !dataset.columns.length} onClick={() => setActiveStep(1)}>选择 X 和 Y<RightOutlined /></Button></div></>
          : <Empty description={loadingDatasets ? '正在读取数据' : '勾选左侧一张或多张数据表后查看记录'} />}
      </Card></Col>
    </Row>}

    {activeStep === 1 && <div className="history-step-panel">
      {!selectedDatasetView ? <Card className="history-card"><Empty description="先选择实验数据并勾选要使用的记录"><Button onClick={() => setActiveStep(0)}>选择数据</Button></Empty></Card> : <>
        <Card className="history-card" title="选择 X 因素" extra={<Text type="secondary">可选多个数值或分类列</Text>}>
          <Checkbox.Group className="history-factor-grid" value={xColumns} onChange={values => setXColumns(values.map(String))} options={selectedDatasetView.columns.filter(column => column.code !== yColumn && column.filledCount >= 4).map(column => ({
            value: column.code,
            disabled: uniqueValues(selectedDatasetView, column.code) < 2,
            label: <span className="history-factor-option"><Text strong>{column.name}</Text><Text type="secondary">{column.kind === 'NUMERIC' ? '数值' : '分类'} · {column.filledCount}/{selectedDatasetView.rowCount} 条有值{uniqueValues(selectedDatasetView, column.code) < 2 ? ' · 记录值未变化' : ''}</Text></span>,
          }))} />
          {!selectedDatasetView.columns.some(column => column.code !== yColumn && column.filledCount >= 4 && uniqueValues(selectedDatasetView, column.code) > 1) && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={selectedDatasetView.rowCount < 4 ? `当前勾选 ${selectedDatasetView.rowCount} 条记录；X 至少需要 4 条有值记录，而且取值要有变化。` : '没有符合条件的 X 列；每个 X 至少需要 4 条有值记录和 2 种不同取值。'} />}
          {partialFormulaSelection && <Alert className="history-range-note" type="warning" showIcon message="请同时选择所有材料比例列" description="这样生成的候选方案才能保持配方总量与历史一致。" />}
        </Card>
        <Card className="history-card" title="选择 Y 指标" extra={<Text type="secondary">只能选择有数值实测结果的列</Text>}>
          <Select className="history-y-select" value={yColumn} onChange={value => { setYColumn(value); setXColumns(current => current.filter(code => code !== value)) }} placeholder="选择要优化的实验结果" options={numericColumns.filter(column => column.filledCount >= 4).map(column => ({ value: column.code, label: `${column.name}${column.unit ? `（${column.unit}）` : ''} · ${column.filledCount}/${selectedDatasetView.rowCount} 条有值` }))} />
          {yColumn && <div className="history-direction-control"><Text>希望 Y 指标</Text><Select value={direction} onChange={setDirection} options={[{ value: 'MAXIMIZE', label: '越高越好' }, { value: 'MINIMIZE', label: '越低越好' }]} /></div>}
        </Card>
        <div className="history-step-footer"><Button onClick={() => setActiveStep(0)}>上一步</Button><Space><Text type="secondary">当前选择可用 {completeRowCount} 条完整记录</Text><Button type="primary" disabled={!xColumns.length || !yColumn || completeRowCount < 4 || partialFormulaSelection} onClick={() => setActiveStep(2)}>设置范围<RightOutlined /></Button></Space></div>
      </>}
    </div>}

    {activeStep === 2 && <div className="history-step-panel">
      {!selectedDatasetView || !yColumn || !xColumns.length ? <Card className="history-card"><Empty description="先选择数据记录、X 因素和 Y 指标"><Button onClick={() => setActiveStep(1)}>返回选择</Button></Empty></Card> : <>
        <Card className="history-card" title="X 因素可搜索范围" extra={<Text type="secondary">默认取历史数据中的最小值和最大值</Text>}>
          <Table size="small" pagination={false} rowKey="code" dataSource={selectedFeatureColumns} columns={[
            { title: 'X 因素', dataIndex: 'name', render: (name: string, column: HistoryDatasetColumn) => <Space direction="vertical" size={0}><Text strong>{name}</Text><Text type="secondary">{column.kind === 'NUMERIC' ? '数值因素' : '分类因素'}{column.unit ? ` · ${column.unit}` : ''}</Text></Space> },
            { title: '历史取值', render: (_: unknown, column: HistoryDatasetColumn) => column.kind === 'NUMERIC' ? <Space><InputNumber aria-label={`${column.name}最小值`} value={bounds[column.code]?.minimum} onChange={value => setBounds(current => ({ ...current, [column.code]: { ...current[column.code], minimum: value ?? undefined } }))} /><Text type="secondary">至</Text><InputNumber aria-label={`${column.name}最大值`} value={bounds[column.code]?.maximum} onChange={value => setBounds(current => ({ ...current, [column.code]: { ...current[column.code], maximum: value ?? undefined } }))} /></Space> : <Text>{[...new Set(selectedDatasetView.rows.map(row => row.values[column.code]).filter(value => value !== undefined && value !== null && value !== ''))].join('、')}</Text> },
          ]} />
          <Alert className="history-range-note" type="info" showIcon message={`将用 ${completeRowCount} 条完整实验记录学习 ${yMeta?.name ?? 'Y'}，然后给出 4 个新的实验组合。`} />
        </Card>
        <div className="history-step-footer"><Button onClick={() => setActiveStep(1)}>上一步</Button><Button type="primary" loading={submitting} disabled={!canSubmit} onClick={() => void submit()}>开始优化</Button></div>
      </>}
    </div>}

    {activeStep === 3 && <div className="history-step-panel">
      {!runId ? <Card className="history-card"><Empty description="还没有优化结果"><Button type="primary" onClick={() => setActiveStep(dataset ? 1 : 0)}>选择数据并开始</Button></Empty></Card> : !run ? <Card className="history-card"><Progress percent={15} status="active" /><Text type="secondary">正在拟合历史实验数据并搜索下一组方案…</Text></Card> : run.frozenRequest?.mode !== 'HISTORICAL_GP_BO' ? <Card className="history-card"><Empty description="此链接对应旧版模型预测任务，请从历史实验数据重新开始优化"><Button type="primary" onClick={() => { setRunId(undefined); setRun(undefined); setActiveStep(0); restoreStarted.current = false; history.replaceState({}, '', location.pathname) }}>选择数据</Button></Empty></Card> : run.executionStatus === 'FAILED' ? <Card className="history-card"><Alert type="error" showIcon message="优化没有完成" description={aiActionFeedback(run.error?.message, '检查 X/Y 是否有足够的完整且不同的历史记录，然后重新提交。')} /><div className="history-step-footer"><Button onClick={() => { setRunId(undefined); setRun(undefined); setActiveStep(2); sessionStorage.removeItem(runStorageKey) }}>调整条件后重试</Button></div></Card> : ['QUEUED','RUNNING'].includes(run.executionStatus) ? <Card className="history-card"><Progress percent={Math.max(run.progress, 10)} status="active" /><Text type="secondary">高斯过程正在学习 {run.frozenRequest?.rows?.length ?? 0} 条历史实验…</Text></Card> : <>
        <Card className="history-card history-result-card" title="下一轮实验建议" extra={<Space><Tag color="green">已生成 {run.candidates.length} 个方案</Tag><Button icon={<ReloadOutlined />} onClick={() => void optimizationApi.run(runId).then(setRun)}>刷新</Button></Space>}>
          <Paragraph className="history-result-explainer">高斯过程使用 {run.searchEvidence?.trainingRowCount ?? run.frozenRequest?.rows?.length ?? '历史'} 条实验记录学习 X 与 Y 的关系。完成实验后填写实测 Y，系统会把这些新记录纳入下一轮计算。</Paragraph>
          {run.candidates.length ? <Table size="middle" rowKey="id" pagination={false} scroll={{ x: 'max-content' }} columns={candidateColumns} dataSource={run.candidates} /> : <Empty description="当前数据无法生成新的实验组合" />}
          {run.candidates.length > 0 && <div className="history-measurement-footer"><Text type="secondary">预测用于安排实验；实测值保存后才能用于下一轮优化。</Text><Space><Button loading={saving} onClick={() => void saveMeasurements()}>保存实测值</Button><Button type="primary" disabled={!hasSavedMeasurement} loading={continuing} onClick={() => void startNextRound()}>用实测值优化下一轮</Button></Space></div>}
        </Card>
        {savedObservations.length > 0 && <Card className="history-card" title="已保存的实测值"><Text type="secondary">{savedObservations.length} 条实测结果已保存，可继续优化下一轮。</Text></Card>}
      </>}
      <div className="history-step-footer"><Button onClick={() => setActiveStep(0)}>查看数据</Button>{dataset && <Button onClick={() => setActiveStep(1)}>修改 X/Y 选择</Button>}</div>
    </div>}
  </main>
}
