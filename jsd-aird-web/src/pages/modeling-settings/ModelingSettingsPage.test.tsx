import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { AppProviders } from '@/app/providers/AppProviders'
import { modelingApi } from '@/services/ai-rnd/modeling-api'
import { useAuthStore } from '@/stores/auth-store'
import { ModelingSettingsPage } from './ModelingSettingsPage'

vi.mock('@/services/ai-rnd/modeling-api', () => ({
  modelingApi: {
    targets: vi.fn(), inputFields: vi.fn(), standardFields: vi.fn(), materials: vi.fn(),
    materialAliases: vi.fn(), materialDictionaries: vi.fn(),
  },
}))

const emptyPage = { items: [], page: 1, size: 20, total: 0, totalPages: 0 }
const testRouterFuture = { v7_startTransition: true, v7_relativeSplatPath: true } as const
const mockedModelingApi = vi.mocked(modelingApi)

describe('ModelingSettingsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockedModelingApi.targets.mockResolvedValue(emptyPage)
    mockedModelingApi.inputFields.mockResolvedValue(emptyPage)
    mockedModelingApi.standardFields.mockResolvedValue([])
    mockedModelingApi.materials.mockResolvedValue([])
    mockedModelingApi.materialAliases.mockResolvedValue([])
    mockedModelingApi.materialDictionaries.mockResolvedValue([])
  })

  afterEach(() => useAuthStore.setState({ user: null, status: 'anonymous' }))

  it('renders the two top-level tabs and a true read-only empty state', async () => {
    setUserPermissions(['ai.modeling.read'])
    renderPage()

    expect(await screen.findByText('当前组织尚未建立预测目标目录')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /预测目标 Y/ })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /输入字段库 X/ })).toBeInTheDocument()
    expect(screen.getByText('当前为只读模式')).toBeInTheDocument()
    expect(screen.queryByText('预置目录初始化')).not.toBeInTheDocument()
    expect(screen.queryByText(/0条样本/)).not.toBeInTheDocument()
  })

})

function renderPage() {
  render(<AppProviders><MemoryRouter future={testRouterFuture}><ModelingSettingsPage /></MemoryRouter></AppProviders>)
}

function setUserPermissions(permissions: string[]) {
  useAuthStore.setState({
    status: 'authenticated',
    user: {
      userId: 'user-id', organizationId: 'organization-id', organizationName: '测试组织',
      username: 'tester', displayName: '测试用户', status: 'ACTIVE', authVersion: 1, permissions,
    },
  })
}
