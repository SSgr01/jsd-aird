/* eslint-disable @typescript-eslint/unbound-method -- Vitest spies intentionally inspect service methods. */
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { AppProviders } from '@/app/providers/AppProviders'
import { trainingApi } from '@/services/ai-rnd/training-api'
import { useAuthStore } from '@/stores/auth-store'
import { ModelManagementPage } from './ModelManagementPage'

vi.mock('@/services/ai-rnd/training-api', () => ({ trainingApi: {
  models: vi.fn(), jobs: vi.fn(), job: vi.fn(), model: vi.fn(),
  retry: vi.fn(), cancel: vi.fn(),
  activate: vi.fn(), pause: vi.fn(), rollback: vi.fn(), comparison: vi.fn(),
} }))

const page = <T,>(items: T[]) => ({ items, page: 1, size: 100, total: items.length, totalPages: items.length ? 1 : 0 })
const routerFuture = { v7_startTransition: true, v7_relativeSplatPath: true } as const

describe('ModelManagementPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(trainingApi.models).mockResolvedValue(page([{
      id: 'model-1', targetId: 'target-1', targetName: '60°光泽', category: 'UV固化性能', valueType: 'CONTINUOUS',
      version: 1, status: 'CANDIDATE', modelType: 'RANDOM_FOREST', dataNature: 'SYNTHETIC',
      productionEligible: false, comparisonStatus: 'NOT_COMPARABLE', metrics: { r2: 0.8, mae: 1.25 }, trainableSamples: 24,
      trainingJobId: 'job-1', createdAt: '2026-09-15T00:00:00Z', updatedAt: '2026-09-15T00:00:00Z', revision: 0,
    }]))
    vi.mocked(trainingApi.jobs).mockResolvedValue(page([]))

  })

  afterEach(() => useAuthStore.setState({ user: null, status: 'anonymous' }))

  it('shows local models as ordinary model versions and hides worker identifiers', async () => {
    setPermissions(['ai.model.read'])
    render(<AppProviders><MemoryRouter future={routerFuture}><ModelManagementPage /></MemoryRouter></AppProviders>)
    expect(await screen.findByText('60°光泽')).toBeInTheDocument()
    expect(screen.getByText((_, element) => element?.textContent?.startsWith('实验数据 ·') ?? false)).toBeInTheDocument()
    expect(screen.getByText('待检查')).toBeInTheDocument()
    expect(screen.getByText('MAE 1.250')).toBeInTheDocument()
    expect(screen.getByText('平均绝对误差 · 越低越好')).toBeInTheDocument()
    expect(screen.queryByText(/模拟|仅供预览|正式预测不可用/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '检查结果' })).toBeInTheDocument()
    expect(screen.queryByText('暂无同口径正式模型比较')).not.toBeInTheDocument()
    expect(screen.queryByText('model-1')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /检查训练|目标设置/ })).not.toBeInTheDocument()
    expect(screen.queryByText('尚无可用模型')).not.toBeInTheDocument()
    await waitFor(() => expect(trainingApi.models).toHaveBeenCalled())
  })
})

function setPermissions(permissions: string[]) {
  useAuthStore.setState({ status: 'authenticated', user: {
    userId: 'user-1', organizationId: 'org-1', organizationName: '测试组织', username: 'tester',
    displayName: '测试用户', status: 'ACTIVE', authVersion: 1, permissions,
  } })
}
