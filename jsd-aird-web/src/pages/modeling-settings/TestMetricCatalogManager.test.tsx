/* eslint-disable @typescript-eslint/unbound-method -- Vitest spies intentionally inspect service methods. */
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { AppProviders } from '@/app/providers/AppProviders'
import { testMetricCatalogApi, type TestMetricCatalogIndicator } from '@/services/ai-rnd/test-metric-catalog-api'
import { stageFile } from '@/services/files/file-api'
import { TestMetricCatalogManager } from './TestMetricCatalogManager'

vi.mock('@/services/ai-rnd/test-metric-catalog-api', () => ({
  testMetricCatalogApi: { latest: vi.fn(), importFile: vi.fn(), indicators: vi.fn() },
}))
vi.mock('@/services/files/file-api', () => ({ stageFile: vi.fn() }))

const imported = { id: 'catalog-1', sourceName: '指标清单.xls', catalogVersion: 1, sourceRowCount: 2, proposalCount: 2, createdAt: '2026-10-15T00:00:00Z' }
const indicators: TestMetricCatalogIndicator[] = [
  { id: 'metric-gloss', sourceRow: 2, rawItemName: '光泽', rawTestMethod: '光泽度计，60°', rawExampleResult: '80-90 GU', rawInfluenceFactors: '膜厚、基材', atomicName: '60°光泽', suggestedUnit: 'GU', aliases: ['光泽'], factorSuggestions: ['膜厚', '基材'] },
  { id: 'metric-hardness', sourceRow: 3, rawItemName: '硬度', rawTestMethod: '铅笔硬度计', rawExampleResult: '2H', rawInfluenceFactors: '基材', atomicName: '铅笔硬度', suggestedUnit: undefined, aliases: ['硬度'], factorSuggestions: ['基材'] },
]

describe('TestMetricCatalogManager', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(testMetricCatalogApi.latest).mockResolvedValue(undefined)
    vi.mocked(testMetricCatalogApi.importFile).mockResolvedValue(imported)
    vi.mocked(testMetricCatalogApi.indicators).mockResolvedValue({ content: indicators, page: 1, size: 200, totalPages: 1, totalElements: 2 })
    vi.mocked(stageFile).mockResolvedValue({ fileId: 'staged-1', originalName: '指标清单.xls', contentType: 'application/vnd.ms-excel', size: 100, sha256: 'hash', status: 'STAGED' })
  })

  it('imports and searches the indicator reference without creating target drafts', async () => {
    render(<AppProviders><TestMetricCatalogManager canManage /></AppProviders>)
    fireEvent.click(screen.getByRole('button', { name: '指标清单' }))
    expect(await screen.findByText('还没有导入清单')).toBeInTheDocument()
    const file = new File(['spreadsheet'], '指标清单.xls', { type: 'application/vnd.ms-excel' })
    fireEvent.change(document.querySelector('input[type="file"]')!, { target: { files: [file] } })
    await waitFor(() => expect(testMetricCatalogApi.importFile).toHaveBeenCalledWith('staged-1'))
    expect(stageFile).toHaveBeenCalledWith(file, 'AI_MODELING_CATALOG')
    expect(await screen.findByText('光泽度计，60°')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /登记|继续设置/ })).not.toBeInTheDocument()
    expect(screen.queryByText('80-90 GU')).not.toBeInTheDocument()
    fireEvent.change(screen.getByPlaceholderText('搜索名称、项目或测试方法'), { target: { value: '铅笔' } })
    await waitFor(() => expect(screen.queryByText('光泽度计，60°')).not.toBeInTheDocument())
    expect(screen.getByText('铅笔硬度计')).toBeInTheDocument()
  })

  it('lets readers consult the existing catalog without an import action', async () => {
    vi.mocked(testMetricCatalogApi.latest).mockResolvedValue(imported)
    render(<AppProviders><TestMetricCatalogManager canManage={false} /></AppProviders>)
    fireEvent.click(screen.getByRole('button', { name: '指标清单' }))
    expect(await screen.findByText('光泽度计，60°')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '导入清单' })).not.toBeInTheDocument()
  })
})
