/* eslint-disable @typescript-eslint/unbound-method */
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { AppProviders } from '@/app/providers/AppProviders'
import { modelingWorkbenchApi } from '@/services/ai-rnd/modeling-workbench-api'
import { HttpError } from '@/services/http/errors'
import { ModelingWorkbenchPage } from './ModelingWorkbenchPage'

vi.mock('@/services/ai-rnd/modeling-workbench-api', () => ({
  modelingWorkbenchApi: {
    datasets: vi.fn(),
    dataset: vi.fn(),
    train: vi.fn(),
    run: vi.fn(),
    register: vi.fn(),
  },
}))

const dataset = {
  id: 'dataset-1', name: 'UV涂层实验', rowCount: 8,
  columns: [
    { code: 'temperature', name: '温度', kind: 'NUMERIC' as const, unit: '°C', filledCount: 8 },
    { code: 'substrate', name: '基材', kind: 'CATEGORICAL' as const, filledCount: 8 },
    { code: 'gloss', name: '60°光泽度', kind: 'NUMERIC' as const, unit: 'GU', filledCount: 8 },
  ],
  rows: Array.from({ length: 8 }, (_, index) => ({
    id: `row-${index + 1}`, versionId: `version-${index + 1}`, label: `EXP-${index + 1}`, title: `实验 ${index + 1}`,
    values: { temperature: 20 + index, substrate: index % 2 ? 'PET' : 'PC', gloss: 60 + index * 2 },
  })),
}

const trainedRun = {
  id: 'run-1', datasetName: dataset.name, status: 'SUCCEEDED' as const, progress: 100, currentStage: 'SUCCEEDED', rowCount: 8,
  xColumns: [dataset.columns[0]!, dataset.columns[1]!], yColumn: dataset.columns[2]!,
  result: { rowCount: 8, excludedRowCount: 0, metrics: { selectedAlgorithm: 'RANDOM_FOREST', mae: 1.2, rmse: 1.5, r2: 0.84, comparison: [
    { algorithm: 'RANDOM_FOREST', mae: 1.2, rmse: 1.5, r2: 0.84 },
    { algorithm: 'LIGHTGBM', mae: 1.8, rmse: 2.1, r2: 0.72 },
  ] }, applicabilityDomain: {} },
  createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
}

describe('ModelingWorkbenchPage', () => {
  beforeEach(() => {
    window.localStorage.clear()
    vi.mocked(modelingWorkbenchApi.datasets).mockResolvedValue({ items: [{ id: dataset.id, name: dataset.name, rowCount: 8, updatedAt: '2026-10-15T00:00:00Z' }] })
    vi.mocked(modelingWorkbenchApi.dataset).mockResolvedValue(dataset)
    vi.mocked(modelingWorkbenchApi.train).mockResolvedValue({ runId: 'run-1', status: 'QUEUED', pollAfterMs: 1000 })
    vi.mocked(modelingWorkbenchApi.run).mockResolvedValue(trainedRun)
  })

  afterEach(() => { vi.clearAllMocks(); window.localStorage.clear() })

  it('checks selected rows before training and names missing fields and conflicting experiment records', async () => {
    const rows = dataset.rows.map((row, index) => ({ ...row, values: {
      ...row.values, temperature: index === 7 ? '' : index === 1 ? dataset.rows[0]!.values.temperature : row.values.temperature,
    } }))
    vi.mocked(modelingWorkbenchApi.dataset).mockResolvedValue({ ...dataset, rows })
    window.localStorage.setItem('ai-modeling-workbench-draft:v1', JSON.stringify({ datasetId: dataset.id, xColumns: ['temperature'], yColumn: 'gloss', activeStep: 1 }))
    render(<AppProviders><ModelingWorkbenchPage /></AppProviders>)
    expect(await screen.findByText('数据检查')).toBeInTheDocument()
    expect(screen.getByText(/1 条记录因所选字段缺失或无效/)).toBeInTheDocument()
    expect(screen.getByText('X · 温度')).toBeInTheDocument()
    expect(screen.getByText(/1 组相同 X 对应不同 Y，涉及 2 条记录/)).toBeInTheDocument()
    fireEvent.click(screen.getByText('查看相关记录'))
    expect(await screen.findByText(/EXP-1.*EXP-2/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /开始训练/ })).toBeEnabled()
  })

  it('selects data, previews the table, freely assigns X/Y, trains, and shows all selected metrics', async () => {
    const goPredict = vi.fn()
    const openModel = vi.fn()
    vi.mocked(modelingWorkbenchApi.register).mockResolvedValue({ modelVersionId: 'model-1', targetId: 'target-1', version: 1, status: 'ACTIVE', registeredAt: new Date().toISOString() })
    const { container } = render(<AppProviders><ModelingWorkbenchPage canRegister onGoPredict={goPredict} onOpenModel={openModel} /></AppProviders>)

    fireEvent.click(await screen.findByRole('button', { name: /UV涂层实验/ }))
    expect(await screen.findByText('原表预览')).toBeInTheDocument()
    expect(await screen.findByText('EXP-1')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /下一步：选择 X \/ Y/ }))

    fireEvent.click(screen.getByRole('checkbox', { name: /温度/ }))
    fireEvent.click(screen.getByRole('checkbox', { name: /基材/ }))
    fireEvent.click(await screen.findByRole('radio', { name: /60°光泽度/ }))
    expect(container.querySelectorAll('.workbench-column-choice-list')).toHaveLength(2)
    expect(screen.getByRole('checkbox', { name: /温度/ }).closest('.ant-checkbox-wrapper')).toHaveClass('ant-checkbox-wrapper-checked')
    expect(screen.getByRole('radio', { name: /60°光泽度/ }).closest('.ant-radio-wrapper')).toHaveClass('ant-radio-wrapper-checked')
    expect(container.querySelector('.workbench-y-detail')).toHaveClass('workbench-selection-summary')
    expect(screen.queryByRole('button', { name: '清空 X' })).not.toBeInTheDocument()
    expect(screen.getByText('8 条完整记录')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /开始训练/ }))
    await waitFor(() => expect(modelingWorkbenchApi.train).toHaveBeenCalledWith({
      datasetIds: [dataset.id],
      selectedRowIdsByDataset: { [dataset.id]: dataset.rows.map(row => row.id) },
      xColumns: ['temperature', 'substrate'], yColumn: 'gloss',
    }, expect.any(String)))
    expect(await screen.findByText('训练完成')).toBeInTheDocument()
    expect(screen.getAllByText('平均绝对误差（MAE）').length).toBeGreaterThan(0)
    expect(screen.getByText(/预测方法对比/)).toBeInTheDocument()
    expect(window.localStorage.getItem('ai-modeling-workbench-draft:v1')).toContain('run-1')
    expect(screen.queryByText(/按正式配置重新训练/)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '注册模型' }))
    fireEvent.click(await screen.findByRole('button', { name: '去预测' }))
    expect(modelingWorkbenchApi.register).toHaveBeenCalledWith('run-1', 'register:run-1')
    expect(goPredict).toHaveBeenCalledWith('target-1')
    fireEvent.click(screen.getByRole('button', { name: '查看模型' }))
    expect(openModel).toHaveBeenCalledWith('target-1', 'model-1')
    expect(modelingWorkbenchApi.train).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('button', { name: '注册模型' })).not.toBeInTheDocument()
  })

  it('sends only the preview rows that remain checked', async () => {
    render(<AppProviders><ModelingWorkbenchPage /></AppProviders>)
    fireEvent.click(await screen.findByRole('button', { name: /UV涂层实验/ }))
    expect(await screen.findByText('EXP-1')).toBeInTheDocument()
    const previewCard = screen.getByText('原表预览').closest('.workbench-preview-card')
    expect(previewCard).not.toBeNull()
    const firstRowCheckbox = screen.getByRole('checkbox', { name: '选择实验记录 EXP-1' })
    fireEvent.click(firstRowCheckbox)
    expect(await screen.findByText('已选 7 条')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /下一步：选择 X \/ Y/ }))
    fireEvent.click(screen.getByRole('checkbox', { name: /温度/ }))
    fireEvent.click(await screen.findByRole('radio', { name: /60°光泽度/ }))
    fireEvent.click(screen.getByRole('button', { name: /开始训练/ }))

    await waitFor(() => expect(modelingWorkbenchApi.train).toHaveBeenCalledWith({
      datasetIds: [dataset.id],
      selectedRowIdsByDataset: { [dataset.id]: dataset.rows.slice(1).map(row => row.id) },
      xColumns: ['temperature'], yColumn: 'gloss',
    }, expect.any(String)))
  })

  it('selects or clears every preview row from one header checkbox across pages', async () => {
    const manyRows = Array.from({ length: 10 }, (_, index) => ({
      ...dataset.rows[index % dataset.rows.length]!,
      id: `row-many-${index + 1}`,
      versionId: `version-many-${index + 1}`,
      label: `EXP-MANY-${index + 1}`,
    }))
    const manyDataset = { ...dataset, rowCount: manyRows.length, rows: manyRows, columns: dataset.columns.map(column => ({ ...column, filledCount: manyRows.length })) }
    vi.mocked(modelingWorkbenchApi.dataset).mockResolvedValue(manyDataset)
    vi.mocked(modelingWorkbenchApi.datasets).mockResolvedValue({ items: [{ id: manyDataset.id, name: manyDataset.name, rowCount: manyRows.length, updatedAt: '' }] })
    render(<AppProviders><ModelingWorkbenchPage /></AppProviders>)
    fireEvent.click(await screen.findByRole('button', { name: /UV涂层实验/ }))

    const selectAll = await screen.findByRole('checkbox', { name: '全选实验记录' })
    expect(await screen.findByText('已选 10 条')).toBeInTheDocument()
    expect(selectAll).toBeChecked()
    expect(screen.queryByRole('button', { name: '全选记录' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '清空记录' })).not.toBeInTheDocument()

    fireEvent.click(selectAll)
    expect(await screen.findByText('已选 0 条')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('checkbox', { name: '全选实验记录' }))
    expect(await screen.findByText('已选 10 条')).toBeInTheDocument()
  })

  it('combines compatible experiment tables and assigns every row to its source table', async () => {
    const second = {
      ...dataset, id: 'dataset-2', name: 'UV涂层补充实验',
      rows: dataset.rows.slice(0, 3).map((row, index) => ({ ...row, id: `row-extra-${index + 1}`, versionId: `version-extra-${index + 1}`, label: `SUP-${index + 1}` })),
      rowCount: 3,
    }
    vi.mocked(modelingWorkbenchApi.datasets).mockResolvedValue({ items: [
      { id: dataset.id, name: dataset.name, rowCount: dataset.rowCount, updatedAt: '' },
      { id: second.id, name: second.name, rowCount: second.rowCount, updatedAt: '' },
    ] })
    vi.mocked(modelingWorkbenchApi.dataset).mockImplementation(id => Promise.resolve(id === dataset.id ? dataset : second))
    render(<AppProviders><ModelingWorkbenchPage /></AppProviders>)
    fireEvent.click(await screen.findByRole('button', { name: /UV涂层实验/ }))
    fireEvent.click(await screen.findByRole('button', { name: /UV涂层补充实验/ }))
    expect(await screen.findByText('2 张表')).toBeInTheDocument()
    expect(screen.getByText('已选 11 条')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /下一步：选择 X \/ Y/ }))
    fireEvent.click(screen.getByRole('checkbox', { name: /温度/ }))
    fireEvent.click(await screen.findByRole('radio', { name: /60°光泽度/ }))
    fireEvent.click(screen.getByRole('button', { name: /开始训练/ }))
    await waitFor(() => expect(modelingWorkbenchApi.train).toHaveBeenCalledWith({
      datasetIds: [dataset.id, second.id],
      selectedRowIdsByDataset: { [dataset.id]: dataset.rows.map(row => row.id), [second.id]: second.rows.map(row => row.id) },
      xColumns: ['temperature'], yColumn: 'gloss',
    }, expect.any(String)))
  })

  it('explains insufficient selected rows and prevents moving to X/Y selection', async () => {
    const shortDataset = { ...dataset, rowCount: 5, rows: dataset.rows.slice(0, 5), columns: dataset.columns.map(column => ({ ...column, filledCount: 5 })) }
    vi.mocked(modelingWorkbenchApi.datasets).mockResolvedValue({ items: [{ id: shortDataset.id, name: shortDataset.name, rowCount: 5, updatedAt: '2026-10-15T00:00:00Z' }] })
    vi.mocked(modelingWorkbenchApi.dataset).mockResolvedValue(shortDataset)
    render(<AppProviders><ModelingWorkbenchPage /></AppProviders>)

    fireEvent.click(await screen.findByRole('button', { name: /UV涂层实验/ }))
    expect(await screen.findByText(/还差 1 条/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /下一步：选择 X \/ Y/ })).toBeDisabled()
  })

  it('restores the last visited step and keeps saved result metrics when the draft Y changes', async () => {
    window.localStorage.setItem('ai-modeling-workbench-draft:v1', JSON.stringify({ datasetId: dataset.id, xColumns: ['temperature'], yColumn: 'substrate', runId: 'run-1', activeStep: 1 }))
    render(<AppProviders><ModelingWorkbenchPage /></AppProviders>)
    expect(await screen.findByRole('heading', { name: '选择输入 X 和预测结果 Y' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /温度/ })).toBeChecked()
    expect(screen.queryByText('训练完成')).not.toBeInTheDocument()
    await waitFor(() => expect(modelingWorkbenchApi.run).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: '3 训练结果' }))
    expect(await screen.findByText('训练完成')).toBeInTheDocument()
    expect(screen.getAllByText('平均绝对误差（MAE）').length).toBeGreaterThan(0)
    expect(screen.getAllByText('0.84').length).toBeGreaterThan(0)
    expect(screen.queryByText('概率损失')).not.toBeInTheDocument()
  })

  it('restores a registered model after refresh without registering or training again', async () => {
    window.localStorage.setItem('ai-modeling-workbench-draft:v1', JSON.stringify({ datasetId: dataset.id, xColumns: ['temperature'], yColumn: 'gloss', runId: 'run-1', activeStep: 2 }))
    vi.mocked(modelingWorkbenchApi.run).mockResolvedValue({ ...trainedRun, registration: { modelVersionId: 'model-1', targetId: 'target-1', version: 2, status: 'ACTIVE', registeredAt: '2026-10-15T00:00:00Z' } })
    const goPredict = vi.fn()
    render(<AppProviders><ModelingWorkbenchPage canRegister onGoPredict={goPredict} /></AppProviders>)
    fireEvent.click(await screen.findByRole('button', { name: '去预测' }))
    expect(goPredict).toHaveBeenCalledWith('target-1')
    expect(screen.queryByRole('button', { name: '注册模型' })).not.toBeInTheDocument()
    expect(modelingWorkbenchApi.train).not.toHaveBeenCalled()
  })

  it('shows a recoverable message for a failed computation without internal service terms', async () => {
    window.localStorage.setItem('ai-modeling-workbench-draft:v1', JSON.stringify({ datasetId: dataset.id, xColumns: ['temperature'], yColumn: 'gloss', runId: 'run-1', activeStep: 2 }))
    vi.mocked(modelingWorkbenchApi.run).mockResolvedValue({ ...trainedRun, status: 'FAILED', error: { code: 'AI_MODEL_ARTIFACT_INVALID', message: 'formula-model.v2 file URLs are disabled' } })
    render(<AppProviders><ModelingWorkbenchPage /></AppProviders>)
    expect(await screen.findByText('训练文件无法保存或读取，请联系管理员检查训练服务。')).toBeInTheDocument()
    expect(screen.queryByText(/file URLs|formula-model/)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '重新训练' }))
    await waitFor(() => expect(modelingWorkbenchApi.train).toHaveBeenCalled())
  })

  it('does not present a missing training record as a job still waiting to run', async () => {
    window.localStorage.setItem('ai-modeling-workbench-draft:v1', JSON.stringify({ datasetId: dataset.id, xColumns: ['temperature'], yColumn: 'gloss', runId: 'run-1', activeStep: 2 }))
    vi.mocked(modelingWorkbenchApi.run).mockRejectedValue(new HttpError('record not found', 'NOT_FOUND', 404))
    render(<AppProviders><ModelingWorkbenchPage /></AppProviders>)
    expect(await screen.findByText('训练记录暂时无法读取')).toBeInTheDocument()
    expect(screen.queryByText('训练任务已提交')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '返回选择数据' }))
    expect(screen.getByRole('heading', { name: '选择实验数据并预览原表' })).toBeInTheDocument()
  })

  it('explains a categorical Y with too few examples in one class', async () => {
    vi.mocked(modelingWorkbenchApi.dataset).mockResolvedValue({ ...dataset, rows: dataset.rows.map((row, index) => ({ ...row, values: { ...row.values, substrate: index === 0 ? 'PC' : 'PET' } })) })
    window.localStorage.setItem('ai-modeling-workbench-draft:v1', JSON.stringify({ datasetId: dataset.id, activeStep: 1 }))
    render(<AppProviders><ModelingWorkbenchPage /></AppProviders>)
    await screen.findByRole('heading', { name: '选择输入 X 和预测结果 Y' })
    expect(await screen.findByText(/部分类别少于 2 条记录/)).toBeInTheDocument()
  })

  it('keeps large X and Y option sets in the same adaptive card layout', async () => {
    const extraColumns = Array.from({ length: 27 }, (_, index) => ({
      code: `factor-${index + 1}`, name: `实验因素 ${index + 1}`, kind: 'NUMERIC' as const, unit: '份', filledCount: 8,
    }))
    const manyColumnDataset = {
      ...dataset,
      columns: [...dataset.columns, ...extraColumns],
      rows: dataset.rows.map((row, rowIndex) => ({
        ...row,
        values: { ...row.values, ...Object.fromEntries(extraColumns.map((column, index) => [column.code, rowIndex + index])) },
      })),
    }
    vi.mocked(modelingWorkbenchApi.dataset).mockResolvedValue(manyColumnDataset)
    window.localStorage.setItem('ai-modeling-workbench-draft:v1', JSON.stringify({ datasetId: dataset.id, activeStep: 1 }))
    render(<AppProviders><ModelingWorkbenchPage /></AppProviders>)

    expect(await screen.findByRole('heading', { name: '选择输入 X 和预测结果 Y' })).toBeInTheDocument()
    expect(document.querySelectorAll('.workbench-column-choice-list')).toHaveLength(2)
    expect(screen.getAllByRole('checkbox')).toHaveLength(manyColumnDataset.columns.length)
    expect(screen.getAllByRole('radio')).toHaveLength(manyColumnDataset.columns.length)
  })

  it('keeps the current task linked while allowing navigation during training', async () => {
    window.localStorage.setItem('ai-modeling-workbench-draft:v1', JSON.stringify({ datasetId: dataset.id, xColumns: ['temperature'], yColumn: 'gloss', runId: 'run-1', activeStep: 1 }))
    vi.mocked(modelingWorkbenchApi.run).mockResolvedValue({ ...trainedRun, status: 'RUNNING', progress: 35 })
    render(<AppProviders><ModelingWorkbenchPage /></AppProviders>)
    expect(await screen.findByRole('button', { name: /训练中/ })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: /返回原表/ }))
    expect(await screen.findByRole('button', { name: dataset.name })).toBeDisabled()
    expect(screen.getByText('训练中，可查看原表；完成后可更换数据。')).toBeInTheDocument()
    expect(modelingWorkbenchApi.train).not.toHaveBeenCalled()
    expect(window.localStorage.getItem('ai-modeling-workbench-draft:v1')).toContain('run-1')
  })

  it('respects navigation performed before the training submission returns', async () => {
    window.localStorage.setItem('ai-modeling-workbench-draft:v1', JSON.stringify({ datasetId: dataset.id, xColumns: ['temperature'], yColumn: 'gloss', activeStep: 1 }))
    let finish: (() => void) | undefined
    vi.mocked(modelingWorkbenchApi.train).mockImplementation(() => new Promise(resolve => { finish = () => resolve({ runId: 'run-2', status: 'QUEUED', pollAfterMs: 1000 }) }))
    render(<AppProviders><ModelingWorkbenchPage /></AppProviders>)
    const train = await screen.findByRole('button', { name: /开始训练/ })
    await waitFor(() => expect(train).toBeEnabled())
    fireEvent.click(train)
    fireEvent.click(screen.getByRole('button', { name: /选择数据$/ }))
    expect(await screen.findByRole('button', { name: dataset.name })).toBeDisabled()
    await act(async () => { finish?.(); await Promise.resolve() })
    expect(screen.getByRole('heading', { name: '选择实验数据并预览原表' })).toBeInTheDocument()
    await waitFor(() => expect(window.localStorage.getItem('ai-modeling-workbench-draft:v1')).toContain('run-2'))
  })

})
