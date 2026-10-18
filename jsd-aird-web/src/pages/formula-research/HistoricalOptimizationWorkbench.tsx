/* eslint-disable @typescript-eslint/no-explicit-any, @typescript-eslint/no-unsafe-assignment, @typescript-eslint/no-unsafe-member-access, @typescript-eslint/no-unsafe-call, @typescript-eslint/no-unsafe-argument, @typescript-eslint/no-unsafe-return */
import { ReloadOutlined, RightOutlined } from '@ant-design/icons'
import { Alert, App, Button, Card, Checkbox, Empty, Input, InputNumber, Progress, Select, Space, Steps, Table, Tag, Typography } from 'antd'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { Key } from 'react'
import { aiActionFeedback } from '@/services/ai-rnd/action-feedback'
import { optimizationApi, type HistoryDataset, type HistoryDatasetColumn, type HistoryDatasetOption, type OptimizationObservation, type OptimizationRun } from '@/services/ai-rnd/optimization-api'
import { mergeHistoryDatasets, selectedRowIdsByDataset, fixedMaterial, isMappedFormulaColumn } from '@/services/ai-rnd/history-dataset-utils'
import { generateUUID } from '@/utils/uuid'
import { XYColumnPicker, SelectedDataIssues } from '@/components/ai-rnd/XYColumnPicker'
import { createExperiment } from '@/services/experiments/experiment-api'
import { useAuthStore } from '@/stores/auth-store'
import { historicalCandidateDraft } from './historical-optimization-draft'
import { useNavigate } from 'react-router-dom'
import { invalidContinuousPrediction } from '../performance-prediction/performance-prediction-utils'
import { HttpError } from '@/services/http/errors'
import './historical-optimization-workbench.css'

const { Title, Text, Paragraph } = Typography
const clearOptimizationRunUrl = (id?: string) => {
  const query = new URLSearchParams(window.location.search)
  if (id && query.get('optimizationRun') !== id) return
  query.delete('optimizationRun')
  window.history.replaceState({}, '', `${window.location.pathname}${query.size ? `?${query.toString()}` : ''}`)
}
const setOptimizationRunUrl = (id: string) => {
  const query = new URLSearchParams(window.location.search); query.set('optimizationRun', id)
  window.history.replaceState({}, '', `${window.location.pathname}?${query.toString()}`)
}
const stepTitles = ['选择数据', '选择 X 和 Y', '设置范围', '查看方案']
type PendingOptimizationOperation = { kind: 'SUBMIT'; key: string; request: Record<string, unknown>; acceptedRunId?: string } | { kind: 'NEXT_ROUND'; key: string; parentRunId: string; acceptedRunId?: string }

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
  const user = useAuthStore(state => state.user)
  const storageKey = user ? `historical-optimization:v2:${user.organizationId}:${user.userId}` : 'historical-optimization:v2:anonymous'
  return <HistoricalOptimizationWorkbenchInner key={storageKey} storageKey={storageKey} />
}

function HistoricalOptimizationWorkbenchInner({ storageKey }: { storageKey: string }) {
  const { message } = App.useApp()
  const user = useAuthStore(state => state.user)
  const navigate = useNavigate()
  const runStorageKey = `${storageKey}:run`
  const setupStorageKey = `${storageKey}:setup`
  const operationStorageKey = `${storageKey}:pending-operation`
  const draftStorageKey = `${storageKey}:drafts`
  const [pendingOperation, setPendingOperation] = useState<PendingOptimizationOperation | undefined>(() => {
    try { return JSON.parse(localStorage.getItem(operationStorageKey) ?? 'null') as PendingOptimizationOperation | null ?? undefined }
    catch { return undefined }
  })
  const [creatingDraft, setCreatingDraft] = useState<string>()
  const [drafts, setDrafts] = useState<Record<string, string>>(() => { try { return JSON.parse(localStorage.getItem(draftStorageKey) ?? '{}') as Record<string,string> } catch { return {} } })
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
  const [frozenDataset, setFrozenDataset] = useState<HistoryDataset>()
  const [runLoadError, setRunLoadError] = useState('')
  const [loadingDatasets, setLoadingDatasets] = useState(false)
  const [loadingDataset, setLoadingDataset] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [saving, setSaving] = useState(false)
  const [continuing, setContinuing] = useState(false)
  const [observations, setObservations] = useState<Record<string, number|undefined>>({})
  const [savedObservations, setSavedObservations] = useState<OptimizationObservation[]>([])
  const restoreStarted = useRef(false)
  const manualBounds = useRef(new Set<string>())
  const loadingDatasetIds = useRef(new Set<string>())
  const [setupReady, setSetupReady] = useState(false)

  const loadDatasets = async (search = keyword) => {
    setLoadingDatasets(true)
    try { setDatasets((await optimizationApi.historyDatasets(search)).items) }
    catch (error) { message.error(aiActionFeedback(error, '无法读取已完成的实验数据')) }
    finally { setLoadingDatasets(false) }
  }

  const sourceDatasets = useMemo(() => selectedDatasetIds.map(id => datasetCache[id]).filter((item): item is HistoryDataset => Boolean(item)), [datasetCache, selectedDatasetIds])
  const liveDataset = useMemo(() => sourceDatasets.length === selectedDatasetIds.length ? mergeHistoryDatasets(sourceDatasets) : undefined, [selectedDatasetIds, sourceDatasets])
  const dataset = runId && frozenDataset ? frozenDataset : liveDataset
  const rowIdsByDataset = useMemo(() => selectedRowIdsByDataset(selectedDatasetIds, dataset?.rows ?? [], selectedRowIds), [dataset?.rows, selectedDatasetIds, selectedRowIds])
  const selectedRows = useMemo(() => dataset?.rows.filter(row => rowIdsByDataset[row.sourceDatasetId ?? dataset.id]?.includes(row.id)) ?? [], [dataset, rowIdsByDataset])
  const selectedDatasetView = useMemo<HistoryDataset | undefined>(() => dataset ? ({
    ...dataset, rowCount: selectedRows.length, rows: selectedRows,
    columns: dataset.columns.map(column => ({ ...column, filledCount: selectedRows.filter(row => row.values[column.code] !== undefined && row.values[column.code] !== null && row.values[column.code] !== '').length })),
  }) : undefined, [dataset, selectedRows])

  const toggleDatasets = (ids: Key[]) => {
    if (pendingOperation) return
    const next = ids.map(String)
    if (next.join('|') === selectedDatasetIds.join('|')) return
    restoreStarted.current = false
    manualBounds.current.clear()
    setSelectedDatasetIds(next); setBounds({}); setRun(undefined); setRunId(undefined); setFrozenDataset(undefined)
    setSavedObservations([]); setObservations({}); localStorage.removeItem(runStorageKey); localStorage.removeItem(operationStorageKey); clearOptimizationRunUrl()
  }

  useEffect(() => {
    const missing = selectedDatasetIds.filter(id => !datasetCache[id] && !loadingDatasetIds.current.has(id))
    if (!missing.length) {
      if (!selectedDatasetIds.some(id => loadingDatasetIds.current.has(id))) setLoadingDataset(false)
      return
    }
    missing.forEach(id => loadingDatasetIds.current.add(id))
    setLoadingDataset(true)
    let cancelled = false
    void Promise.all(missing.map(id => optimizationApi.historyDataset(id))).then(values => {
      if (!cancelled) setDatasetCache(current => ({ ...current, ...Object.fromEntries(values.map(value => [value.id, value])) }))
    }).catch(error => { message.error(aiActionFeedback(error, '无法读取所选数据表')) }).finally(() => {
      if (!cancelled) {
        missing.forEach(id => loadingDatasetIds.current.delete(id))
        setLoadingDataset(selectedDatasetIds.some(id => loadingDatasetIds.current.has(id)))
      }
    })
    return () => { cancelled = true; missing.forEach(id => loadingDatasetIds.current.delete(id)) }
  }, [datasetCache, message, selectedDatasetIds])

  useEffect(() => {
    if (!dataset || runId) return
    const ids = new Set(dataset.rows.map(row => row.id))
    const availableCodes = new Set(dataset.columns.map(column => column.code))
    const staleRows = Object.entries(selectedRowIds).some(([id, rows]) => selectedDatasetIds.includes(id) && rows.some(row => !ids.has(row)))
    const staleFields = xColumns.some(code => !availableCodes.has(code)) || Boolean(yColumn && !availableCodes.has(yColumn))
    if (staleRows) setSelectedRowIds(current => Object.fromEntries(Object.entries(current).map(([id, rows]) => [id, selectedDatasetIds.includes(id) ? rows.filter(row => ids.has(row)) : rows])))
    if (staleFields) {
      setXColumns(current => current.filter(code => availableCodes.has(code)))
      setYColumn(current => current && availableCodes.has(current) ? current : undefined)
    }
    if (staleRows || staleFields) {
      setActiveStep(staleRows ? 0 : 1)
      message.warning({ key: 'optimization-sample-refresh', content: staleRows ? '实验数据已按样品重新整理，请重新勾选记录并确认 X/Y。' : '所选数据表中有字段已变化，请检查并重新确认 X/Y。' })
    }
  }, [dataset])

  useEffect(() => {
    if (!dataset || !selectedRows.length) return
    setBounds(current => Object.fromEntries(dataset.columns.filter(column => column.kind === 'NUMERIC').map(column => {
      const values = numericValues(selectedDatasetView, column.code)
      const previous = current[column.code]
      const valid = previous?.minimum != null && previous.maximum != null && Number.isFinite(previous.minimum) && Number.isFinite(previous.maximum) && previous.minimum <= previous.maximum
      return [column.code, manualBounds.current.has(column.code) && valid && new Set(values).size > 1 ? previous : values.length ? { minimum: Math.min(...values), maximum: Math.max(...values) } : {}]
    })))
  }, [dataset, selectedDatasetView, selectedRows.length])


  useEffect(() => {
    void loadDatasets('')
    try {
      const saved = JSON.parse(localStorage.getItem(setupStorageKey) ?? '{}') as { datasetIds?: string[]; selectedRowIdsByDataset?: Record<string, string[]>; xColumns?: string[]; yColumn?: string; activeStep?: number; direction?: 'MAXIMIZE'|'MINIMIZE'; bounds?: typeof bounds; manualBounds?: string[] }
      if (saved.datasetIds?.length) { setSelectedDatasetIds(saved.datasetIds); setSelectedRowIds(saved.selectedRowIdsByDataset ?? {}); setXColumns(saved.xColumns ?? []); setYColumn(saved.yColumn); setActiveStep(saved.activeStep ?? 0); setDirection(saved.direction ?? 'MAXIMIZE'); setBounds(saved.bounds ?? {}); manualBounds.current = new Set(saved.manualBounds ?? []) }
    } catch { /* ignore an incomplete local setup draft */ }
    setSetupReady(true)
  }, [])
  useEffect(() => {
    if (!setupReady) return
    localStorage.setItem(setupStorageKey, JSON.stringify({ datasetIds: selectedDatasetIds, selectedRowIdsByDataset: selectedRowIds, xColumns, yColumn, activeStep, direction, bounds, manualBounds: [...manualBounds.current] }))
  }, [activeStep, selectedDatasetIds, selectedRowIds, setupReady, xColumns, yColumn, direction, bounds])
  useEffect(() => {
    const remembered = new URLSearchParams(location.search).get('optimizationRun') ?? localStorage.getItem(runStorageKey) ?? undefined
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
        setRun(current); setRunLoadError(''); localStorage.setItem(runStorageKey, current.id)
        const request: any = current.frozenRequest ?? {}
        if (request.mode === 'HISTORICAL_GP_BO' && !restoreStarted.current) {
          restoreStarted.current = true
          const sourceIds: string[] = Array.isArray(request.datasetIds) ? request.datasetIds.map(String) : [String(request.datasetId)]
          setSelectedDatasetIds(sourceIds)
          setSelectedRowIds(request.selectedRowIdsByDataset ?? {})
          const snapshot = request.datasetSnapshot as HistoryDataset | undefined
          if (snapshot?.rows) {
            setFrozenDataset(snapshot)
          } else { message.warning('本任务未保存样品明细，请重新选择数据开始优化') }
          const features = Array.isArray(request.features) ? request.features : []
          manualBounds.current = new Set(features.map((feature: any) => String(feature.code)))
          setXColumns(features.map((feature: any) => String(feature.code)))
          setYColumn(String(request.target ?? ''))
          setDirection(request.direction === 'MINIMIZE' ? 'MINIMIZE' : 'MAXIMIZE')
          setBounds(Object.fromEntries(features.filter((feature: any) => feature.kind === 'NUMERIC').map((feature: any) => [feature.code, { minimum: feature.minimum, maximum: feature.maximum }])))
        }
        if (['QUEUED', 'RUNNING'].includes(current.executionStatus)) timer = window.setTimeout(() => { void poll() }, 1200)
        else {
          if (pendingOperation?.acceptedRunId === current.id) {
            localStorage.removeItem(operationStorageKey); setPendingOperation(undefined); setSubmitting(false); setContinuing(false)
          }
          try {
            const saved = (await optimizationApi.observations(runId)).observations
            if (active) {
              setSavedObservations(saved)
              setObservations(Object.fromEntries(saved.map(item => [`${item.candidateId}:${item.fieldCode ?? item.targetId}`, Number(item.value)])))
            }
          } catch { /* observation form remains available */ }
        }
      } catch (error) {
        if (!active) return
        if (error instanceof HttpError && [404, 410].includes(error.status ?? 0)) {
          setRun(undefined); setRunId(undefined); setFrozenDataset(undefined); setRunLoadError('这条优化任务已不存在，已保留设置')
          localStorage.removeItem(runStorageKey); localStorage.removeItem(operationStorageKey); setPendingOperation(undefined); clearOptimizationRunUrl(runId)
          return
        }
        setRunLoadError('连接暂时中断，正在重新读取优化进度。')
        if (!(error instanceof HttpError && [401, 403].includes(error.status ?? 0))) timer = window.setTimeout(() => { void poll() }, 3000)
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
  const formulaCodes = (selectedDatasetView?.columns ?? []).filter(isMappedFormulaColumn).map(column => column.code)
  const missingFormulaColumns = (selectedDatasetView?.columns ?? []).filter(column => formulaCodes.includes(column.code) && !xColumns.includes(column.code) && !fixedMaterial(selectedDatasetView, column))
  const partialFormulaSelection = selectedFeatureColumns.some(isMappedFormulaColumn) && missingFormulaColumns.length > 0
  const fixedCodes = selectedDatasetView?.columns.filter(column => column.code !== yColumn && fixedMaterial(selectedDatasetView, column) && column.filledCount >= 4).map(column => column.code) ?? []
  const formulaSelected = xColumns.some(code => formulaCodes.includes(code))
  useEffect(() => {
    if (!formulaSelected) return
    setXColumns(current => fixedCodes.some(code => !current.includes(code)) ? [...new Set([...current, ...fixedCodes])] : current)
  }, [formulaSelected, fixedCodes.join('|')])
  const adjustable = (column: HistoryDatasetColumn) => column.kind !== 'NUMERIC' ? uniqueValues(selectedDatasetView,column.code) > 1 : (bounds[column.code]?.minimum ?? 0) < (bounds[column.code]?.maximum ?? 0)
  const recipeFeatures = selectedFeatureColumns.filter(column => formulaCodes.includes(column.code))
  const recipeMin = recipeFeatures.reduce((sum, column) => sum + (bounds[column.code]?.minimum ?? 0), 0)
  const recipeMax = recipeFeatures.reduce((sum, column) => sum + (bounds[column.code]?.maximum ?? 0), 0)
  const recipeVariable = recipeFeatures.some(adjustable)
  const unsupportedMaterial = recipeFeatures.find(column => column.compositionBasis !== 'MASS_PERCENT' || !['', '%', 'PERCENT'].includes(column.unit ?? ''))
  const compositionProblem = unsupportedMaterial ? `材料“${unsupportedMaterial.name}”的计量基准不是质量百分比，请先确认原始记录的单位和计量基准。`
    : formulaSelected && !partialFormulaSelection && (recipeMin > 100 + (recipeVariable ? 1e-9 : 0.001) || recipeMax < 100 - (recipeVariable ? 1e-9 : 0.001))
    ? '当前材料比例范围无法合计为 100%，请调整对应材料的上下限或固定值。' : ''
  const hasAdjustment = selectedFeatureColumns.some(column => !formulaCodes.includes(column.code) && adjustable(column))
    || recipeFeatures.some(column => {
      const range = bounds[column.code]
      return range?.minimum != null && range.maximum != null && Math.min(range.maximum, 100 - recipeMin + range.minimum) - Math.max(range.minimum, 100 - recipeMax + range.maximum) > 1e-9
    })
  const rangeError = (column: HistoryDatasetColumn) => {
    if (column.kind !== 'NUMERIC') return ''
    const range = bounds[column.code]
    if (range?.minimum == null || range.maximum == null || !Number.isFinite(range.minimum) || !Number.isFinite(range.maximum)) return '请填写有限的数值范围'
    if (range.minimum > range.maximum) return '最小值不能大于最大值'
    if (formulaCodes.includes(column.code) && range.minimum < 0) return '材料比例不能为负数'
    return ''
  }
  const hasSavedMeasurement = savedObservations.length > 0
  const configurationLocked = Boolean(pendingOperation || runId && frozenDataset)
  const canSubmit = Boolean(selectedDatasetView && selectedRows.length && yColumn && xColumns.length && completeRowCount >= 4 && hasAdjustment && !partialFormulaSelection && !compositionProblem && !selectedFeatureColumns.some(column => rangeError(column)))

  const detachRunForEditing = () => {
    setRunId(undefined); setRun(undefined); setFrozenDataset(undefined); setPendingOperation(undefined)
    localStorage.removeItem(operationStorageKey); localStorage.removeItem(runStorageKey); clearOptimizationRunUrl()
  }

  useEffect(() => {
    if (!pendingOperation) return
    let cancelled = false
    let timer: number | undefined
    if (pendingOperation.acceptedRunId) {
      setRunId(pendingOperation.acceptedRunId); setRun(undefined); setActiveStep(3); setRunLoadError('')
      localStorage.setItem(runStorageKey, pendingOperation.acceptedRunId)
      setOptimizationRunUrl(pendingOperation.acceptedRunId)
      setSubmitting(false); setContinuing(false)
      return
    }
    if (pendingOperation.kind === 'SUBMIT') setSubmitting(true); else setContinuing(true)
    const send = async () => {
      try {
        const accepted = pendingOperation.kind === 'SUBMIT'
          ? await optimizationApi.submit(pendingOperation.request, pendingOperation.key)
          : await optimizationApi.nextRound(pendingOperation.parentRunId, pendingOperation.key)
        if (cancelled) return
        const confirmed = { ...pendingOperation, acceptedRunId: accepted.runId } as PendingOptimizationOperation
        try { localStorage.setItem(operationStorageKey, JSON.stringify(confirmed)) } catch { /* the original command remains retryable with the same key */ }
        setPendingOperation(confirmed); setRunId(accepted.runId); setRun(undefined); setActiveStep(3); setRunLoadError('')
        localStorage.setItem(runStorageKey, accepted.runId); setOptimizationRunUrl(accepted.runId)
        setSavedObservations([]); setObservations({}); restoreStarted.current = false
        setSubmitting(false); setContinuing(false)
      } catch (error) {
        if (cancelled) return
        const retryable = !(error instanceof HttpError) || error.status == null || error.status === 408 || error.status >= 500
        if (retryable) {
          setRunLoadError('请求尚未确认，正在使用同一请求重新连接。')
          timer = window.setTimeout(() => { void send() }, 3000)
          return
        }
        setRunLoadError(aiActionFeedback(error, '优化请求未能提交，请检查数据和选择范围。'))
        setPendingOperation(undefined); localStorage.removeItem(operationStorageKey)
        setSubmitting(false); setContinuing(false)
      }
    }
    void send()
    return () => { cancelled = true; if (timer) window.clearTimeout(timer) }
  }, [pendingOperation?.key, pendingOperation?.acceptedRunId])

  const submit = async () => {
    if (!dataset || !selectedRows.length || !yColumn || !xColumns.length) { message.warning('先选择数据表、记录和 X/Y 列'); return }
    if (completeRowCount < 4) { message.warning(`当前 X/Y 组合只有 ${completeRowCount} 条完整记录，至少需要 4 条`); return }
    if (!canSubmit) { message.warning('请检查每个数值 X 因素的最小值和最大值'); return }
    const request = {
        mode: 'HISTORICAL_GP_BO', datasetId: selectedDatasetIds[0], datasetIds: selectedDatasetIds,
        selectedRowIdsByDataset: rowIdsByDataset, xColumns, yColumn, direction, bounds,
        candidateCount: 4,
      }
    const operation: PendingOptimizationOperation = { kind: 'SUBMIT', key: generateUUID(), request }
    try { localStorage.setItem(operationStorageKey, JSON.stringify(operation)) }
    catch { setRunLoadError('优化未提交：浏览器无法保存恢复信息，请清理本地空间后重试。'); return }
    setFrozenDataset(dataset); setRun(undefined); setRunId(undefined); localStorage.removeItem(runStorageKey); clearOptimizationRunUrl()
    setPendingOperation(operation); setSubmitting(true); setRunLoadError('')
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
    const operation: PendingOptimizationOperation = { kind: 'NEXT_ROUND', key: generateUUID(), parentRunId: runId }
    try { localStorage.setItem(operationStorageKey, JSON.stringify(operation)) }
    catch { setRunLoadError('下一轮未提交：浏览器无法保存恢复信息，请清理本地空间后重试。'); return }
    setPendingOperation(operation); setContinuing(true); setRunLoadError('')
  }

  const previewColumns = useMemo(() => {
    const visible = (dataset?.columns ?? []).map(column => column.code)
    return [
      ...(selectedDatasetIds.length > 1 ? [{ title: '来源表', dataIndex: 'sourceDatasetName', fixed: 'left' as const, width: 150, ellipsis: true }] : []),
      { title: '实验记录', dataIndex: 'label', fixed: 'left' as const, width: 130, ellipsis: true },
      ...visible.map(code => {
        const column = dataset?.columns.find(item => item.code === code)
        return { title: <span>{column?.name ?? code}{code === yColumn ? <Tag color="blue" className="history-role-tag">Y</Tag> : xColumns.includes(code) ? <Tag color="green" className="history-role-tag">X</Tag> : null}</span>, width: 150, render: (_: unknown, row: any) => <span>{row.values?.[code] ?? '—'}{row.values?.[code] !== undefined && column?.unit ? ` ${column.unit}` : ''}</span> }
      }),
    ]
  }, [dataset, selectedDatasetIds.length, xColumns, yColumn])

  const createDraft = async (candidate: any) => {
    if (!run || !user || creatingDraft) return
    if (drafts[candidate.id]) { navigate(`/experiments/${drafts[candidate.id]}`); return }
    setCreatingDraft(candidate.id)
    try {
      const measured = savedObservations.find(item => item.candidateId === candidate.id)
      const input = historicalCandidateDraft(run, candidate, measured ? Number(measured.value) : undefined)
      const created = await createExperiment({ ...input, ownerName: user.displayName || user.username })
      const next = { ...drafts, [candidate.id]: created.id }; setDrafts(next); localStorage.setItem(draftStorageKey,JSON.stringify(next))
      message.success('实验草稿已生成'); navigate(`/experiments/${created.id}`)
    } catch (error) { message.error(aiActionFeedback(error, '实验草稿生成失败')) }
    finally { setCreatingDraft(undefined) }
  }

  const candidateColumns = [
    { title: '方案', dataIndex: 'title', fixed: 'left' as const, width: 110, render: (_: string, candidate: any) => <Text strong>{candidate.title}</Text> },
    ...selectedFeatureColumns.map(column => ({ title: <span>{column.name}{column.unit ? <Text type="secondary">（{column.unit}）</Text> : null}</span>, width: 140, render: (_: unknown, candidate: any) => { const value = candidate.inputs?.[column.code]; return <Text>{column.kind === 'NUMERIC' ? valueText(value, undefined, 8) : value ?? '—'}</Text> } })),
    { title: <span>预测 {yMeta?.name ?? 'Y'}{yMeta?.unit ? `（${yMeta.unit}）` : ''}</span>, width: 210, render: (_: unknown, candidate: any) => { const prediction = candidatePrediction(candidate); const hasPrediction = Number.isFinite(Number(prediction.value)); const invalid = hasPrediction && invalidContinuousPrediction(Number(prediction.value), yMeta?.unit); return <Space direction="vertical" size={0}><Text strong>{invalid ? '未展示：结果不合理' : valueText(prediction.value)}</Text>{invalid ? <Text type="danger">核对单位并补充相近实验后重新优化</Text> : <Text type="secondary">范围 {valueText(prediction.lower)}～{valueText(prediction.upper)}</Text>}</Space> } },
    { title: <span>实测 {yMeta?.name ?? 'Y'}{yMeta?.unit ? `（${yMeta.unit}）` : ''}</span>, width: 180, render: (_: unknown, candidate: any) => <InputNumber aria-label={`${candidate.title}实测${yMeta?.name ?? 'Y'}`} className="history-observation-input" disabled={Boolean(pendingOperation)} value={observations[`${candidate.id}:${yColumn}`]} onChange={value => setObservations(current => ({ ...current, [`${candidate.id}:${yColumn}`]: value ?? undefined }))} placeholder="完成实验后填写" /> },
    ...(user?.permissions.includes('experiment.create') ? [{ title: '实验记录', width: 140, render: (_: unknown, candidate: any) => <Button loading={creatingDraft === candidate.id} disabled={Boolean(creatingDraft)} onClick={() => void createDraft(candidate)}>{drafts[candidate.id] ? '查看实验草稿' : '生成实验草稿'}</Button> }] : []),
  ]

  const steps = <div className="history-steps-card"><Steps current={activeStep} onChange={setActiveStep} responsive={false} items={stepTitles.map(title => ({ title }))} /></div>

  return <main className="historical-optimization-page">
    <header className="historical-page-header">
      <div><Text className="historical-eyebrow">AI 研发助手 · 实验优化</Text><Title level={2}>实验优化</Title><Paragraph>选择一张或多张实验数据表，勾选要使用的记录，再选择 X 因素和 Y 指标。</Paragraph></div>
      <Button icon={<ReloadOutlined />} onClick={() => void loadDatasets()}>刷新数据</Button>
    </header>
    {steps}
    {runLoadError && <Alert className="history-range-note" type={runLoadError.includes('不存在') ? 'warning' : 'error'} showIcon message={runLoadError} />}

    {activeStep === 0 && <div className="history-layout history-data-layout">
      <div><Card title="选择数据表" className="history-card" extra={<Text type="secondary">已选 {selectedDatasetIds.length} 张</Text>}>
        <Space.Compact className="history-search"><Input value={keyword} onChange={event => setKeyword(event.target.value)} onPressEnter={() => void loadDatasets()} placeholder="搜索实验模板"/><Button onClick={() => void loadDatasets()}>搜索</Button></Space.Compact>
        <div className="history-dataset-list">{datasets.length ? datasets.map(item => <button key={item.id} type="button" role="checkbox" aria-checked={selectedDatasetIds.includes(item.id)} disabled={Boolean(pendingOperation)} className={`history-dataset-option ${selectedDatasetIds.includes(item.id) ? 'selected' : ''}`} onClick={() => toggleDatasets(selectedDatasetIds.includes(item.id) ? selectedDatasetIds.filter(id => id !== item.id) : [...selectedDatasetIds, item.id])}>
          <span className="history-selection-mark" aria-hidden="true">{selectedDatasetIds.includes(item.id) ? '✓' : ''}</span>
          <span><Text strong>{item.name}</Text><Text type="secondary">{item.rowCount} 条已完成实验</Text></span><RightOutlined />
        </button>) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={loadingDatasets ? '正在读取数据' : '没有可用的已完成实验数据'} />}</div>
      </Card></div>
      <div><Card title="原表预览" className="history-card" extra={dataset ? <Space><Tag color="blue">{dataset.rowCount} 条记录</Tag><Tag color="green">已选 {selectedRows.length} 条</Tag></Space> : null}>
        {dataset ? <><Paragraph type="secondary" className="history-preview-hint">多张表按相同字段、数据类型和单位合并。默认全选记录，可逐条取消；未勾选的记录不会参与优化。</Paragraph>
          {selectedDatasetIds.length > 1 && !dataset.columns.length && <Alert type="warning" showIcon message="所选数据表没有共同字段" description="请取消不匹配的数据表，选择字段和单位一致的数据表后再继续。" />}
          <Table size="small" rowKey="id" loading={loadingDataset} rowSelection={{ selectedRowKeys: selectedRows.map(row => row.id), preserveSelectedRowKeys: true,
            columnTitle: <Checkbox aria-label="全选实验记录" checked={selectedRows.length === dataset.rows.length && dataset.rows.length > 0} indeterminate={selectedRows.length > 0 && selectedRows.length < dataset.rows.length} disabled={loadingDataset || Boolean(pendingOperation) || Boolean(runId && frozenDataset)} onChange={event => { setSelectedRowIds(current => ({ ...current, ...Object.fromEntries((dataset.sourceDatasetIds ?? [dataset.id]).map(id => [id, event.target.checked ? dataset.rows.filter(row => (row.sourceDatasetId ?? dataset.id) === id).map(row => row.id) : []])) })); }} />, hideSelectAll: true,
            renderCell: (_checked, row) => <Checkbox aria-label={`选择实验记录 ${row.label || row.title || row.id}`} checked={selectedRows.some(item => item.id === row.id)} disabled={loadingDataset || Boolean(pendingOperation) || Boolean(runId && frozenDataset)} onChange={event => {
              const sourceId = row.sourceDatasetId ?? dataset.id
              setSelectedRowIds(current => { const ids = current[sourceId] ?? dataset.rows.filter(item => (item.sourceDatasetId ?? dataset.id) === sourceId).map(item => item.id); return { ...current, [sourceId]: event.target.checked ? [...new Set([...ids,row.id])] : ids.filter(id => id !== row.id) } })
            }} />,
 }} pagination={{ pageSize: 8, showSizeChanger: false }} scroll={{ x: 'max-content' }} columns={previewColumns} dataSource={dataset.rows} />
          <div className="history-step-footer"><Text type="secondary">{selectedRows.length < 4 ? `至少勾选 4 条记录后继续；可合并多张表（还差 ${4 - selectedRows.length} 条）。` : '只有勾选且 Y 和所有 X 都有值的记录才会参与计算。'}</Text><Button type="primary" disabled={loadingDataset || selectedRows.length < 4 || !dataset.columns.length} onClick={() => setActiveStep(1)}>选择 X 和 Y<RightOutlined /></Button></div></>
          : <Empty description={loadingDatasets ? '正在读取数据' : '勾选上方一张或多张数据表后查看记录'} />}
      </Card></div>
    </div>}

    {activeStep === 1 && <div className="history-step-panel">
      {!selectedDatasetView ? <Card className="history-card"><Empty description="先选择实验数据并勾选要使用的记录"><Button onClick={() => setActiveStep(0)}>选择数据</Button></Empty></Card> : <>
        <XYColumnPicker dataset={selectedDatasetView} mode="optimization" xColumns={xColumns} yColumn={yColumn} onXChange={setXColumns} disabled={Boolean(pendingOperation) || Boolean(runId && frozenDataset)}
          lockedXCodes={formulaSelected ? fixedCodes : []}
          onYChange={value => { setYColumn(value); setXColumns(current => current.filter(code => code !== value)) }}
          yExtra={yColumn ? <div className="history-direction-control"><Text>优化方向</Text><Select aria-label="优化方向" disabled={configurationLocked} value={direction} onChange={setDirection} options={[{ value: 'MAXIMIZE', label: '越高越好' }, { value: 'MINIMIZE', label: '越低越好' }]} /></div> : null} />
        {partialFormulaSelection && <Alert className="history-range-note" type="warning" showIcon message={`配方还缺少材料：${missingFormulaColumns.map(column => column.name).join('、')}`} />}
        <SelectedDataIssues dataset={selectedDatasetView} xColumns={xColumns} yColumn={yColumn} />
        <div className="history-step-footer"><Button onClick={() => setActiveStep(0)}>上一步</Button><Space><Text type="secondary">当前选择可用 {completeRowCount} 条完整记录</Text><Button type="primary" disabled={!xColumns.length || !yColumn || completeRowCount < 4 || partialFormulaSelection} onClick={() => setActiveStep(2)}>设置范围<RightOutlined /></Button></Space></div>
      </>}
    </div>}

    {activeStep === 2 && <div className="history-step-panel">
      {!selectedDatasetView || !yColumn || !xColumns.length ? <Card className="history-card"><Empty description="先选择数据记录、X 因素和 Y 指标"><Button onClick={() => setActiveStep(1)}>返回选择</Button></Empty></Card> : <>
        <Card className="history-card" title="X 因素可搜索范围" extra={<Text type="secondary">默认取历史数据中的最小值和最大值</Text>}>
          <Table size="small" pagination={false} rowKey="code" dataSource={selectedFeatureColumns} columns={[
            { title: 'X 因素', dataIndex: 'name', render: (name: string, column: HistoryDatasetColumn) => <Space direction="vertical" size={0}><Text strong>{name}</Text><Text type="secondary">{column.kind === 'NUMERIC' ? '数值因素' : '分类因素'}{column.unit ? ` · ${column.unit}` : ''}</Text>{rangeError(column) && <Text type="danger">{rangeError(column)}</Text>}</Space> },
            { title: '历史取值', render: (_: unknown, column: HistoryDatasetColumn) => column.kind === 'NUMERIC' ? fixedMaterial(selectedDatasetView, column) ? <Text>固定 {numericValues(selectedDatasetView,column.code)[0]}{column.unit ?? ''}</Text> : <Space><InputNumber aria-label={`${column.name}最小值`} disabled={configurationLocked} value={bounds[column.code]?.minimum} onChange={value => { manualBounds.current.add(column.code); setBounds(current => ({ ...current, [column.code]: { ...current[column.code], minimum: value ?? undefined } })) }} /><Text type="secondary">至</Text><InputNumber aria-label={`${column.name}最大值`} disabled={configurationLocked} value={bounds[column.code]?.maximum} onChange={value => { manualBounds.current.add(column.code); setBounds(current => ({ ...current, [column.code]: { ...current[column.code], maximum: value ?? undefined } })) }} /></Space> : <Text>{[...new Set(selectedDatasetView.rows.map(row => row.values[column.code]).filter(value => value !== undefined && value !== null && value !== ''))].join('、')}</Text> },
          ]} />
          <Alert className="history-range-note" type="info" showIcon message={`将用 ${completeRowCount} 条完整实验记录学习 ${yMeta?.name ?? 'Y'}，再生成满足范围和配方总量的新实验组合。`} />
        </Card>
        {compositionProblem ? <Text type="warning">{compositionProblem}</Text> : !hasAdjustment && <Text type="warning">当前范围与配方总量约束没有留下可调整空间，请调整因素范围。</Text>}
        <div className="history-step-footer"><Button onClick={() => setActiveStep(1)}>上一步</Button><Button type="primary" loading={submitting} disabled={!canSubmit || Boolean(runId) || Boolean(pendingOperation)} onClick={() => void submit()}>开始优化</Button></div>
      </>}
    </div>}

    {activeStep === 3 && <div className="history-step-panel">
      {!runId ? runLoadError ? <Card className="history-card"><Alert type="warning" showIcon message={runLoadError} /><div className="history-step-footer"><Button onClick={() => setActiveStep(2)}>返回保留的设置</Button><Button type="primary" onClick={() => { setRunLoadError(''); setActiveStep(2) }}>重新开始优化</Button></div></Card> : <Card className="history-card"><Empty description="还没有优化结果"><Button type="primary" onClick={() => setActiveStep(dataset ? 1 : 0)}>选择数据并开始</Button></Empty></Card> : !run ? <Card className="history-card">{runLoadError ? <Alert type="warning" showIcon message={runLoadError} description="任务设置已保留。可稍后刷新进度，或返回设置重新优化。" /> : <Text type="secondary">已提交，正在读取优化任务状态…</Text>}<div className="history-step-footer"><Button icon={<ReloadOutlined />} onClick={() => { setRunLoadError(''); void optimizationApi.run(runId).then(current => { setRun(current); setRunLoadError('') }).catch(error => setRunLoadError(aiActionFeedback(error, '读取优化进度失败，请重试。'))) }}>重试读取</Button><Button onClick={() => setActiveStep(2)}>返回设置</Button></div></Card> : run.executionStatus === 'FAILED' ? <Card className="history-card"><Alert type="error" showIcon message="优化没有完成" description={aiActionFeedback(run.error, '检查 X/Y 是否有足够的完整且不同的历史记录，然后重新提交。')} /><div className="history-step-footer"><Button onClick={() => { detachRunForEditing(); setActiveStep(2) }}>调整条件后重试</Button></div></Card> : ['QUEUED','RUNNING'].includes(run.executionStatus) ? <Card className="history-card"><Progress percent={Math.max(run.progress, 10)} status="active" /><Text type="secondary">高斯过程正在学习 {run.frozenRequest?.rows?.length ?? 0} 条历史实验…</Text></Card> : <>
        <Card className="history-card history-result-card" title="下一轮实验建议" extra={<Space><Tag color="green">已生成 {run.candidates.length} 个方案</Tag><Button icon={<ReloadOutlined />} onClick={() => void optimizationApi.run(runId).then(setRun)}>刷新</Button></Space>}>
          <Paragraph className="history-result-explainer">高斯过程使用 {run.searchEvidence?.trainingRowCount ?? run.frozenRequest?.rows?.length ?? '历史'} 条实验记录学习 X 与 Y 的关系。完成实验后填写实测 Y，系统会把这些新记录纳入下一轮计算。</Paragraph>
          {run.candidates.length ? <Table size="middle" rowKey="id" pagination={false} scroll={{ x: 'max-content' }} columns={candidateColumns} dataSource={run.candidates} /> : <Empty description="当前数据无法生成新的实验组合" />}
          {run.candidates.length > 0 && <div className="history-measurement-footer"><Text type="secondary">预测用于安排实验；实测值保存后才能用于下一轮优化。</Text><Space><Button loading={saving} disabled={Boolean(pendingOperation)} onClick={() => void saveMeasurements()}>保存实测值</Button><Button type="primary" disabled={!hasSavedMeasurement || Boolean(pendingOperation)} loading={continuing} onClick={() => void startNextRound()}>用实测值优化下一轮</Button></Space></div>}
        </Card>
        {savedObservations.length > 0 && <Card className="history-card" title="已保存的实测值"><Text type="secondary">{savedObservations.length} 条实测结果已保存，可继续优化下一轮。</Text></Card>}
      </>}
      <div className="history-step-footer"><Button onClick={() => setActiveStep(0)}>查看数据</Button>{dataset && <Button onClick={() => { detachRunForEditing(); setActiveStep(1) }}>修改 X/Y 选择</Button>}</div>
    </div>}
  </main>
}
