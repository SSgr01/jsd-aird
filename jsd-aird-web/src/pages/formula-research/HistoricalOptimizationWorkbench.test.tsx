import { App } from 'antd'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { HttpError } from '@/services/http/errors'
import { HistoricalOptimizationWorkbench } from './HistoricalOptimizationWorkbench'

const api = vi.hoisted(() => ({
  historyDatasets: vi.fn(), historyDataset: vi.fn(), submit: vi.fn(), run: vi.fn(),
  observations: vi.fn(), saveObservations: vi.fn(), nextRound: vi.fn(),
}))

vi.mock('@/services/ai-rnd/optimization-api', () => ({ optimizationApi: api }))

const dataset = {
  id: 'template-version-1', name: '涂层历史数据', rowCount: 8,
  columns: [
    { code: 'input:temperature', name: '烘烤温度', kind: 'NUMERIC', unit: '℃', filledCount: 8 },
    { code: 'input:time', name: '烘烤时间', kind: 'NUMERIC', unit: 'min', filledCount: 8 },
    { code: 'result:gloss', name: '60°光泽', kind: 'NUMERIC', unit: 'GU', filledCount: 8 },
  ],
  rows: Array.from({ length: 8 }, (_, index) => ({
    id: `experiment-${index}`, versionId: `version-${index}`, label: `EXP-${index + 1}`, title: `涂层实验 ${index + 1}`,
    values: { 'input:temperature': 20 + index * 2, 'input:time': 5 + index, 'result:gloss': 35 + index * 1.4 },
  })),
}

const compositionDataset = {
  ...dataset,
  id: 'formula-template-version',
  name: '树脂配方历史数据',
  columns: [
    { code: 'formula:00000000-0000-0000-0000-000000000001', name: '助剂比例', kind: 'NUMERIC', unit: '%', compositionBasis: 'MASS_PERCENT', filledCount: 8, semanticType: 'MATERIAL_RATIO', materialId: '00000000-0000-0000-0000-000000000001', mappingStatus: 'MAPPED' },
    { code: 'formula:00000000-0000-0000-0000-000000000002', name: '树脂 A 比例', kind: 'NUMERIC', unit: '%', compositionBasis: 'MASS_PERCENT', filledCount: 8, semanticType: 'MATERIAL_RATIO', materialId: '00000000-0000-0000-0000-000000000002', mappingStatus: 'MAPPED' },
    { code: 'formula:00000000-0000-0000-0000-000000000003', name: '树脂 B 比例', kind: 'NUMERIC', unit: '%', compositionBasis: 'MASS_PERCENT', filledCount: 8, semanticType: 'MATERIAL_RATIO', materialId: '00000000-0000-0000-0000-000000000003', mappingStatus: 'MAPPED' },
    { code: 'result:wear', name: '耐磨次数', kind: 'NUMERIC', unit: '次', filledCount: 8 },
  ],
  rows: Array.from({ length: 8 }, (_, index) => ({
    id: `formula-experiment-${index}`, versionId: `formula-version-${index}`, label: `配方实验 ${index + 1}`, title: `配方实验 ${index + 1}`,
    values: {
      'formula:00000000-0000-0000-0000-000000000001': 52 + index * 0.2,
      'formula:00000000-0000-0000-0000-000000000002': 3 + index * 0.1,
      'formula:00000000-0000-0000-0000-000000000003': 45 - index * 0.3,
      'result:wear': 850 + index * 15,
    },
  })),
}

const sparseDataset = {
  ...dataset,
  id: 'sparse-template',
  name: '稀疏配方记录',
  rowCount: 1,
  rows: dataset.rows.slice(0, 1),
}

const completedRun = {
  id: 'run-1', executionStatus: 'SUCCEEDED', outcomeStatus: 'SUCCEEDED', progress: 100,
  requestedCandidateCount: 1, actualCandidateCount: 1, missingStrategies: [], missingStrategyReasons: {},
  candidates: [{ id: 'candidate-1', candidateNo: 1, strategy: 'HISTORICAL_BO', title: '候选方案 1',
    formula: {}, inputs: { 'input:temperature': 29.123456, 'input:time': 8.54321 },
    results: { targets: [{ targetId: 'result:gloss', targetName: '60°光泽', status: 'SUCCEEDED', result: { resultType: 'CONTINUOUS', value: 49.1, lower: 45.2, upper: 53, unit: 'GU' } }] },
    quality: {}, applicability: {}, ruleCheck: {}, score: 0, targetTotal: 0, baselineDistance: 0, changes: {}, strategyEvidence: {}, risks: [],
  }],
  frozenBaseline: {}, frozenRequest: { datasetSnapshot: dataset, mode: 'HISTORICAL_GP_BO', datasetId: dataset.id, datasetName: dataset.name,
    target: 'result:gloss', targetName: '60°光泽', direction: 'MAXIMIZE', candidateCount: 1,
    features: [{ code: 'input:temperature', kind: 'NUMERIC', minimum: 20, maximum: 34 }, { code: 'input:time', kind: 'NUMERIC', minimum: 5, maximum: 12 }],
    rows: dataset.rows.map(row => ({ ...row.values })), columns: dataset.columns },
  modelBindings: {}, requestId: 'request-1', updatedAt: new Date().toISOString(), searchEngine: 'GAUSSIAN_PROCESS_EI',
  searchStrategy: 'EXPECTED_IMPROVEMENT', searchEvidence: { trainingRowCount: 8 },
}

const chooseXYButton = () => screen.getAllByRole('button', { name: /选择 X 和 Y/ }).find(element => element.tagName === 'BUTTON')!

describe('HistoricalOptimizationWorkbench', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    sessionStorage.clear()
    window.history.replaceState({}, '', '/')
    api.historyDatasets.mockResolvedValue({ items: [
      { id: dataset.id, name: dataset.name, rowCount: dataset.rowCount, updatedAt: '' },
      { id: 'sparse-template', name: '稀疏配方记录', rowCount: 1, updatedAt: '' },
    ] })
    api.historyDataset.mockImplementation((id: string) => id === sparseDataset.id ? sparseDataset : dataset)
    api.submit.mockResolvedValue({ runId: completedRun.id, executionStatus: 'QUEUED', pollAfterMs: 1000 })
    api.run.mockResolvedValue(completedRun)
    api.observations.mockResolvedValue({ observations: [] })
  })

  it('selects arbitrary measured X/Y columns and submits a direct historical GP optimization', async () => {
    render(<App><MemoryRouter><HistoricalOptimizationWorkbench /></MemoryRouter></App>)
    await screen.findByText('涂层历史数据')
    fireEvent.click(screen.getByText('涂层历史数据'))
    await waitFor(() => expect(chooseXYButton()).toBeEnabled())
    fireEvent.click(chooseXYButton())

    fireEvent.click(screen.getByRole('checkbox', { name: /烘烤温度/ }))
    fireEvent.click(screen.getByRole('checkbox', { name: /烘烤时间/ }))
    fireEvent.click(screen.getByRole('radio', { name: /60°光泽/ }))
    const setRangeButton = screen.getAllByRole('button', { name: /设置范围/ }).find(element => element.tagName === 'BUTTON')
    expect(setRangeButton).toBeDefined()
    fireEvent.click(setRangeButton!)
    fireEvent.click(screen.getByRole('button', { name: '开始优化' }))

    await waitFor(() => expect(api.submit).toHaveBeenCalledOnce())
    expect(api.submit.mock.calls[0]?.[0]).toMatchObject({
      mode: 'HISTORICAL_GP_BO', datasetId: dataset.id, datasetIds: [dataset.id],
      selectedRowIdsByDataset: { [dataset.id]: dataset.rows.map(row => row.id) },
      yColumn: 'result:gloss', direction: 'MAXIMIZE',
      xColumns: ['input:temperature', 'input:time'],
    })
    expect(await screen.findByText('下一轮实验建议')).toBeInTheDocument()
    expect(screen.getByText('49.1')).toBeInTheDocument()
    expect(screen.getByText('29.123456')).toBeInTheDocument()
    expect(screen.getByText('8.54321')).toBeInTheDocument()
    expect(screen.queryByText('49.1 GU')).not.toBeInTheDocument()
  })

  it('allows a small table to be combined and explains the minimum selected row count', async () => {
    render(<App><MemoryRouter><HistoricalOptimizationWorkbench /></MemoryRouter></App>)
    const sparseName = await screen.findByText('稀疏配方记录')
    const sparseDataset = sparseName.closest('button')
    expect(sparseDataset).not.toBeNull()
    expect(sparseDataset).toBeEnabled()
    fireEvent.click(sparseDataset!)
    expect(await screen.findByText(/至少勾选 4 条记录后继续/)).toBeInTheDocument()
    expect(chooseXYButton()).toBeDisabled()
  })

  it('merges multiple tables and submits only the rows that remain checked', async () => {
    const second = {
      ...dataset, id: 'template-version-2', name: '涂层补充数据', rowCount: 8,
      rows: dataset.rows.map((row, index) => ({ ...row, id: `experiment-extra-${index}`, versionId: `version-extra-${index}`, label: `SUP-${index + 1}` })),
    }
    api.historyDatasets.mockResolvedValue({ items: [
      { id: dataset.id, name: dataset.name, rowCount: dataset.rowCount, updatedAt: '' },
      { id: second.id, name: second.name, rowCount: second.rowCount, updatedAt: '' },
    ] })
    api.historyDataset.mockImplementation((id: string) => id === dataset.id ? dataset : second)
    render(<App><MemoryRouter><HistoricalOptimizationWorkbench /></MemoryRouter></App>)
    fireEvent.click(await screen.findByText('涂层历史数据'))
    fireEvent.click(await screen.findByText('涂层补充数据'))
    expect(await screen.findByText('已选 2 张')).toBeInTheDocument()
    expect(await screen.findByText('已选 16 条')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('checkbox', { name: /^选择实验记录 EXP-1$/ }))
    expect(await screen.findByText('已选 15 条')).toBeInTheDocument()
    fireEvent.click(chooseXYButton())
    fireEvent.click(screen.getByRole('checkbox', { name: /烘烤温度/ }))
    fireEvent.click(screen.getByRole('checkbox', { name: /烘烤时间/ }))
    fireEvent.click(screen.getByRole('radio', { name: /60°光泽/ }))
    fireEvent.click(screen.getAllByRole('button', { name: /设置范围/ }).find(element => element.tagName === 'BUTTON')!)
    fireEvent.click(screen.getByRole('button', { name: '开始优化' }))

    await waitFor(() => expect(api.submit).toHaveBeenCalledOnce())
    expect(api.submit.mock.calls[0]?.[0]).toMatchObject({
      datasetIds: [dataset.id, second.id],
      selectedRowIdsByDataset: { [dataset.id]: dataset.rows.slice(1).map(row => row.id), [second.id]: second.rows.map(row => row.id) },
    })
  })

  it('requires all material proportions when optimizing a formula', async () => {
    api.historyDatasets.mockResolvedValue({ items: [
      { id: compositionDataset.id, name: compositionDataset.name, rowCount: compositionDataset.rowCount, updatedAt: '' },
    ] })
    api.historyDataset.mockResolvedValue(compositionDataset)
    render(<App><MemoryRouter><HistoricalOptimizationWorkbench /></MemoryRouter></App>)
    await screen.findByText('树脂配方历史数据')
    fireEvent.click(screen.getByText('树脂配方历史数据'))
    await waitFor(() => expect(chooseXYButton()).toBeEnabled())
    fireEvent.click(chooseXYButton())

    fireEvent.click(screen.getByRole('checkbox', { name: /助剂比例/ }))
    fireEvent.click(screen.getByRole('radio', { name: /耐磨次数/ }))
    expect(await screen.findByText(/配方还缺少材料/)).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /设置范围/ }).find(element => element.tagName === 'BUTTON')).toBeDisabled()

    fireEvent.click(screen.getByRole('checkbox', { name: /树脂 A 比例/ }))
    fireEvent.click(screen.getByRole('checkbox', { name: /树脂 B 比例/ }))
    await waitFor(() => expect(screen.getAllByRole('button', { name: /设置范围/ }).find(element => element.tagName === 'BUTTON')).toBeEnabled())
    expect(screen.queryByText('请同时选择所有材料比例列')).not.toBeInTheDocument()
  })

  it('retains fixed zero materials and prevents infeasible recipe bounds before submission', async () => {
    const stabilizerId = '00000000-0000-0000-0000-000000000004'
    const source = { ...compositionDataset, columns: [...compositionDataset.columns,
      { code: `formula:${stabilizerId}`, name: '稳定剂比例', kind: 'NUMERIC', unit: '%', compositionBasis: 'MASS_PERCENT', semanticType: 'MATERIAL_RATIO', mappingStatus: 'MAPPED', materialId: stabilizerId, filledCount: 8 }],
      rows: compositionDataset.rows.map(row => ({ ...row, values: { ...row.values, [`formula:${stabilizerId}`]: 0 } })) }
    api.historyDataset.mockResolvedValue(source)
    api.historyDatasets.mockResolvedValue({ items: [{ id: source.id, name: source.name, rowCount: 8, updatedAt: '' }] })
    render(<App><MemoryRouter><HistoricalOptimizationWorkbench /></MemoryRouter></App>)
    fireEvent.click(await screen.findByText(source.name))
    await waitFor(() => expect(chooseXYButton()).toBeEnabled())
    fireEvent.click(chooseXYButton())
    fireEvent.click(screen.getByRole('checkbox', { name: /助剂比例/ }))
    expect(screen.getByRole('checkbox', { name: /稳定剂比例/ })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: /稳定剂比例/ })).toBeDisabled()
    expect(screen.getByText('固定 0% · 已纳入配方')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('checkbox', { name: /树脂 A 比例/ }))
    fireEvent.click(screen.getByRole('checkbox', { name: /树脂 B 比例/ }))
    fireEvent.click(screen.getByRole('radio', { name: /耐磨次数/ }))
    fireEvent.click(screen.getAllByRole('button', { name: /设置范围/ }).find(element => element.tagName === 'BUTTON')!)
    fireEvent.change(screen.getByRole('spinbutton', { name: '助剂比例最小值' }), { target: { value: '90' } })
    fireEvent.change(screen.getByRole('spinbutton', { name: '助剂比例最大值' }), { target: { value: '95' } })
    expect(screen.getByText(/当前材料比例范围无法合计为 100%/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '开始优化' })).toBeDisabled()
    expect(api.submit).not.toHaveBeenCalled()
  })

  it('keeps mass parts visible but blocks material optimization until the mass percentage basis is confirmed', async () => {
    const source = { ...compositionDataset, columns: compositionDataset.columns.map(column => column.materialId
      ? { ...column, unit: '份', compositionBasis: 'UNSPECIFIED' } : column) }
    api.historyDataset.mockResolvedValue(source)
    api.historyDatasets.mockResolvedValue({ items: [{ id: source.id, name: source.name, rowCount: 8, updatedAt: '' }] })
    render(<App><MemoryRouter><HistoricalOptimizationWorkbench /></MemoryRouter></App>)
    fireEvent.click(await screen.findByText(source.name))
    await waitFor(() => expect(chooseXYButton()).toBeEnabled())
    fireEvent.click(chooseXYButton())
    for (const material of ['助剂比例', '树脂 A 比例', '树脂 B 比例']) fireEvent.click(screen.getByRole('checkbox', { name: new RegExp(material) }))
    fireEvent.click(screen.getByRole('radio', { name: /耐磨次数/ }))
    fireEvent.click(screen.getAllByRole('button', { name: /设置范围/ }).find(element => element.tagName === 'BUTTON')!)
    expect(screen.getByText(/计量基准不是质量百分比/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '开始优化' })).toBeDisabled()
    expect(api.submit).not.toHaveBeenCalled()
  })

  it('saves measured results, starts the next round, and restores it after a refresh', async () => {
    const secondRun = {
      ...completedRun,
      id: 'run-2',
      frozenRequest: {
        ...completedRun.frozenRequest,
        rows: [...dataset.rows.map(row => row.values), {
          'input:temperature': 29, 'input:time': 8, 'result:gloss': 51.2,
        }],
      },
      searchEvidence: { trainingRowCount: 9 },
    }
    api.run.mockImplementation((id: string) => id === 'run-2' ? secondRun : completedRun)
    api.saveObservations.mockResolvedValue({ observations: [
      { candidateId: 'candidate-1', fieldCode: 'result:gloss', value: 51.2, updatedAt: new Date().toISOString() },
    ], savedCount: 1 })
    api.nextRound.mockResolvedValue({ runId: 'run-2', executionStatus: 'QUEUED', pollAfterMs: 1000 })

    const view = render(<App><MemoryRouter><HistoricalOptimizationWorkbench /></MemoryRouter></App>)
    await screen.findByText('涂层历史数据')
    fireEvent.click(screen.getByText('涂层历史数据'))
    await waitFor(() => expect(chooseXYButton()).toBeEnabled())
    fireEvent.click(chooseXYButton())
    fireEvent.click(screen.getByRole('checkbox', { name: /烘烤温度/ }))
    fireEvent.click(screen.getByRole('checkbox', { name: /烘烤时间/ }))
    fireEvent.click(screen.getByRole('radio', { name: /60°光泽/ }))
    fireEvent.click(screen.getAllByRole('button', { name: /设置范围/ }).find(element => element.tagName === 'BUTTON')!)
    fireEvent.click(screen.getByRole('button', { name: '开始优化' }))

    await screen.findByText('下一轮实验建议')
    const nextRoundButton = screen.getByRole('button', { name: '用实测值优化下一轮' })
    expect(nextRoundButton).toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: '保存实测值' }))
    expect(await screen.findByText('至少填写一个方案的 Y 实测值')).toBeInTheDocument()
    expect(api.saveObservations).not.toHaveBeenCalled()

    const observedValue = screen.getByRole('spinbutton', { name: /候选方案 1实测60°光泽/ })
    fireEvent.change(observedValue, { target: { value: '51.2' } })
    fireEvent.blur(observedValue)
    fireEvent.click(screen.getByRole('button', { name: '保存实测值' }))
    await waitFor(() => expect(api.saveObservations).toHaveBeenCalledWith('run-1', [
      { candidateId: 'candidate-1', fieldCode: 'result:gloss', value: 51.2 },
    ]))
    await waitFor(() => expect(screen.getByRole('button', { name: '用实测值优化下一轮' })).toBeEnabled())

    fireEvent.click(screen.getByRole('button', { name: '用实测值优化下一轮' }))
    await waitFor(() => expect(api.nextRound).toHaveBeenCalledOnce())
    await screen.findByText(/使用 9 条实验记录学习/)
    expect(api.nextRound).toHaveBeenCalledWith('run-1', expect.any(String))

    view.unmount()
    render(<App><MemoryRouter><HistoricalOptimizationWorkbench /></MemoryRouter></App>)
    await screen.findByText(/使用 9 条实验记录学习/)
    expect(api.run).toHaveBeenCalledWith('run-2')
    expect(screen.getByRole('spinbutton', { name: /候选方案 1实测60°光泽/ })).toBeInTheDocument()
  })

  it('keeps the setup editable and explains structured data shortage when optimization is rejected', async () => {
    api.submit.mockRejectedValueOnce(new HttpError('internal details', 'INSUFFICIENT_DATA', 400))
    render(<App><MemoryRouter><HistoricalOptimizationWorkbench /></MemoryRouter></App>)
    await screen.findByText('涂层历史数据')
    fireEvent.click(screen.getByText('涂层历史数据'))
    await waitFor(() => expect(chooseXYButton()).toBeEnabled())
    fireEvent.click(chooseXYButton())
    fireEvent.click(screen.getByRole('checkbox', { name: /烘烤温度/ }))
    fireEvent.click(screen.getByRole('checkbox', { name: /烘烤时间/ }))
    fireEvent.click(screen.getByRole('radio', { name: /60°光泽/ }))
    fireEvent.click(screen.getAllByRole('button', { name: /设置范围/ }).find(element => element.tagName === 'BUTTON')!)
    fireEvent.click(screen.getByRole('button', { name: '开始优化' }))

    expect(await screen.findByText('符合条件的完整实验记录不足，请补齐记录或调整 X/Y。')).toBeInTheDocument()
    expect(api.submit).toHaveBeenCalledOnce()
    expect(screen.getByRole('button', { name: '开始优化' })).toBeEnabled()
  })
})
