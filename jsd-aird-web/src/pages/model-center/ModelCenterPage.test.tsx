import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { AppProviders } from '@/app/providers/AppProviders'
import { useAuthStore } from '@/stores/auth-store'
import { ModelCenterPage } from './ModelCenterPage'

vi.mock('@/pages/model-management', () => ({
  ModelManagementPage: ({ view }: { view?: string }) => <div data-testid="model-list-panel">模型列表面板 · {view}</div>,
}))
vi.mock('@/pages/modeling-settings', () => ({
  ModelingSettingsPage: ({ openCreateTarget }: { openCreateTarget?: boolean }) => <div data-testid="modeling-settings-panel">建模设置面板{openCreateTarget ? ' · 新增目标' : ''}</div>,
}))

function LocationProbe() {
  const location = useLocation()
  return <output data-testid="location">{location.pathname}{location.search}</output>
}

function setPermissions(permissions: string[]) {
  useAuthStore.setState({ status: 'authenticated', user: {
    userId: 'user-1', organizationId: 'org-1', organizationName: '测试组织', username: 'tester',
    displayName: '测试用户', status: 'ACTIVE', authVersion: 1, permissions,
  } })
}

describe('ModelCenterPage', () => {
  afterEach(() => useAuthStore.setState({ user: null, status: 'anonymous' }))

  it('defaults to model list and keeps settings in the same route', async () => {
    setPermissions(['ai.model.read', 'ai.modeling.read', 'ai.modeling.manage'])
    render(<AppProviders><MemoryRouter initialEntries={['/assistant/model-center']}><ModelCenterPage /><LocationProbe /></MemoryRouter></AppProviders>)
    expect(screen.getByRole('heading', { name: '模型中心' })).toBeInTheDocument()
    expect(screen.getByTestId('model-list-panel')).toHaveTextContent('模型列表面板')
    fireEvent.click(screen.getByRole('tab', { name: '建模设置' }))
    expect(screen.getByTestId('modeling-settings-panel')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('?tab=settings'))
    fireEvent.click(screen.getByRole('button', { name: /新增预测目标/ }))
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('?tab=settings&action=create-target'))
  })

  it('shows only the permitted panel for a modeling-only user', () => {
    setPermissions(['ai.modeling.read'])
    render(<AppProviders><MemoryRouter initialEntries={['/assistant/model-center?tab=models']}><ModelCenterPage /></MemoryRouter></AppProviders>)
    expect(screen.queryByRole('tab', { name: '模型列表' })).not.toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '建模设置' })).toBeInTheDocument()
    expect(screen.getByTestId('modeling-settings-panel')).toBeInTheDocument()
  })
})
