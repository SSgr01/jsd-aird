/* eslint-disable @typescript-eslint/unbound-method -- Vitest spies intentionally inspect service methods. */
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { AppProviders } from '@/app/providers/AppProviders'
import { modelRegistryApi } from '@/services/ai-rnd/model-registry-api'
import { useAuthStore } from '@/stores/auth-store'
import { ModelManagementPage } from './ModelManagementPage'
import type { ModelVersion } from '@/services/ai-rnd/ai-rnd-types'

vi.mock('@/services/ai-rnd/model-registry-api', () => ({ modelRegistryApi: {
  models: vi.fn(), model: vi.fn(),
  pause: vi.fn(), rollback: vi.fn(),
} }))

const page = <T,>(items: T[]) => ({ items, page: 1, size: 100, total: items.length, totalPages: items.length ? 1 : 0 })
const routerFuture = { v7_startTransition: true, v7_relativeSplatPath: true } as const
const model: ModelVersion = {
  id: 'model-1', targetId: 'target-1', targetName: '60°光泽', category: 'UV固化性能', valueType: 'CONTINUOUS',
  version: 1, status: 'ACTIVE', modelType: 'RANDOM_FOREST', dataNature: 'SYNTHETIC',
  productionEligible: false, comparisonStatus: 'NOT_COMPARABLE', metrics: { r2: 0.8, mae: 1.25 }, trainableSamples: 24,
  workbenchRunId: 'run-1', createdAt: '2026-09-15T00:00:00Z', updatedAt: '2026-09-15T00:00:00Z', revision: 0,
}
describe('ModelManagementPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(modelRegistryApi.models).mockResolvedValue(page([model]))

  })

  afterEach(() => useAuthStore.setState({ user: null, status: 'anonymous' }))

  it('shows local models as ordinary model versions and hides worker identifiers', async () => {
    setPermissions(['ai.model.read'])
    render(<AppProviders><MemoryRouter future={routerFuture}><ModelManagementPage /></MemoryRouter></AppProviders>)
    expect(await screen.findByText('60°光泽')).toBeInTheDocument()
    expect(screen.getByText((_, element) => element?.textContent?.startsWith('实验数据 ·') ?? false)).toBeInTheDocument()
    expect(screen.getByText('当前用于预测')).toBeInTheDocument()
    expect(screen.getByText('MAE 1.250')).toBeInTheDocument()
    expect(screen.getByText('平均绝对误差 · 越低越好')).toBeInTheDocument()
    expect(screen.queryByText(/模拟|仅供预览|正式预测不可用/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '查看模型' })).toBeInTheDocument()
    expect(screen.queryByText('暂无同口径正式模型比较')).not.toBeInTheDocument()
    expect(screen.queryByText('model-1')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /检查训练|目标设置/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '训练任务' })).not.toBeInTheDocument()
    await waitFor(() => expect(modelRegistryApi.models).toHaveBeenCalled())
  })

})

function setPermissions(permissions: string[]) {
  useAuthStore.setState({ status: 'authenticated', user: {
    userId: 'user-1', organizationId: 'org-1', organizationName: '测试组织', username: 'tester',
    displayName: '测试用户', status: 'ACTIVE', authVersion: 1, permissions,
  } })
}
