/* eslint-disable @typescript-eslint/unbound-method -- Vitest spies intentionally inspect service methods. */
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { AppProviders } from '@/app/providers/AppProviders'
import { modelingApi } from '@/services/ai-rnd/modeling-api'
import { trainingApi } from '@/services/ai-rnd/training-api'
import { useAuthStore } from '@/stores/auth-store'
import { ModelManagementPage } from './ModelManagementPage'

vi.mock('@/services/ai-rnd/modeling-api', () => ({ modelingApi: { targets: vi.fn() } }))
vi.mock('@/services/ai-rnd/training-api', () => ({ trainingApi: {
  settings: vi.fn(), models: vi.fn(), jobs: vi.fn(), job: vi.fn(), model: vi.fn(),
  updateSettings: vi.fn(), evaluate: vi.fn(), retry: vi.fn(), cancel: vi.fn(),
  activate: vi.fn(), pause: vi.fn(), rollback: vi.fn(), comparison: vi.fn(),
} }))

const page = <T,>(items: T[]) => ({ items, page: 1, size: 100, total: items.length, totalPages: items.length ? 1 : 0 })
const routerFuture = { v7_startTransition: true, v7_relativeSplatPath: true } as const

describe('ModelManagementPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(trainingApi.settings).mockResolvedValue({ autoLearningEnabled: false, revision: 0 })
    vi.mocked(trainingApi.models).mockResolvedValue(page([{
      id: 'model-1', targetId: 'target-1', targetName: '60°光泽', category: 'UV固化性能', valueType: 'CONTINUOUS',
      version: 1, status: 'CANDIDATE', modelType: 'RANDOM_FOREST', dataNature: 'SYNTHETIC',
      productionEligible: false, comparisonStatus: 'NOT_COMPARABLE', metrics: { mae: 1.25 }, trainableSamples: 24,
      trainingJobId: 'job-1', createdAt: '2026-09-15T00:00:00Z', updatedAt: '2026-09-15T00:00:00Z', revision: 0,
    }]))
    vi.mocked(trainingApi.jobs).mockResolvedValue(page([]))
    vi.mocked(modelingApi.targets).mockResolvedValue(page([{
      id: 'target-1', code: 'GLOSS_60', name: '60°光泽', category: 'UV固化性能', valueType: 'CONTINUOUS',
      status: 'ACTIVE', definitionStatus: 'COMPLETE', sourceMappingStatus: 'COMPLETE', trainingStatus: 'NOT_TRAINED', revision: 1,
      evaluationStatus: 'COMPLETED', updatedAt: '2026-09-15T00:00:00Z',
    }]))
  })

  afterEach(() => useAuthStore.setState({ user: null, status: 'anonymous' }))

  it('shows business status and hides worker identifiers from the main table', async () => {
    setPermissions(['ai.model.read'])
    render(<AppProviders><MemoryRouter future={routerFuture}><ModelManagementPage /></MemoryRouter></AppProviders>)
    expect(await screen.findByText('60°光泽')).toBeInTheDocument()
    expect(screen.getByText('待评估')).toBeInTheDocument()
    expect(screen.getByText('平均绝对误差 1.250')).toBeInTheDocument()
    expect(screen.queryByText('model-1')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /立即检查/ })).toBeDisabled()
    expect(modelingApi.targets).not.toHaveBeenCalled()
    await waitFor(() => expect(trainingApi.models).toHaveBeenCalled())
  })
})

function setPermissions(permissions: string[]) {
  useAuthStore.setState({ status: 'authenticated', user: {
    userId: 'user-1', organizationId: 'org-1', organizationName: '测试组织', username: 'tester',
    displayName: '测试用户', status: 'ACTIVE', authVersion: 1, permissions,
  } })
}
