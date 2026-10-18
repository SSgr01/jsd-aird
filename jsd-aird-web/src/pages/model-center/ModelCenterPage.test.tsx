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
  ModelingWorkbenchPage: () => <div data-testid="modeling-workbench-panel">自由选择 X / Y 建模工具</div>,
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

  it('keeps only model list and workbench without manual configuration entries', () => {
    setPermissions(['ai.model.read', 'ai.modeling.read', 'ai.modeling.manage'])
    render(<AppProviders><MemoryRouter initialEntries={['/assistant/model-center']}><ModelCenterPage /></MemoryRouter></AppProviders>)
    expect(screen.getByTestId('model-list-panel')).toBeInTheDocument()
    expect(screen.getAllByRole('tab').map(tab => tab.textContent)).toEqual(['模型列表', '建模工具'])
    expect(screen.queryByRole('button', { name: '新增预测目标' })).not.toBeInTheDocument()
  })

  it('opens the workbench for a modeling-only user without training permissions', () => {
    setPermissions(['ai.modeling.read'])
    render(<AppProviders><MemoryRouter initialEntries={['/assistant/model-center']}><ModelCenterPage /><LocationProbe /></MemoryRouter></AppProviders>)
    expect(screen.queryByRole('tab', { name: '模型列表' })).not.toBeInTheDocument()
    expect(screen.getByTestId('modeling-workbench-panel')).toBeInTheDocument()
    expect(screen.getByTestId('location').textContent).toBe('/assistant/model-center')
  })

  it.each([
    '?tab=settings',
    '?tab=settings&action=create-target&targetName=旧目标&targetUnit=GU',
    '?tab=settings&targetId=target-1',
    '?action=create-target',
  ])('rejects a retired configuration query without rewriting it: %s', (query) => {
    setPermissions(['ai.model.read', 'ai.modeling.read'])
    render(<AppProviders><MemoryRouter initialEntries={[`/assistant/model-center${query}`]}><ModelCenterPage /><LocationProbe /></MemoryRouter></AppProviders>)
    expect(screen.getByText('访问的页面不存在。')).toBeInTheDocument()
    expect(screen.getByTestId('location').textContent).toBe(`/assistant/model-center${query}`)
    expect(screen.queryByTestId('model-list-panel')).not.toBeInTheDocument()
    expect(screen.queryByTestId('modeling-workbench-panel')).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: '目标设置' })).not.toBeInTheDocument()
  })

  it('switches from a focused registered model to the workbench without carrying old model links', async () => {
    setPermissions(['ai.model.read', 'ai.modeling.read'])
    render(<AppProviders><MemoryRouter initialEntries={['/assistant/model-center?tab=models&targetId=target-1&modelId=model-1']}><ModelCenterPage /><LocationProbe /></MemoryRouter></AppProviders>)
    fireEvent.click(screen.getByRole('tab', { name: '建模工具' }))
    expect(await screen.findByTestId('modeling-workbench-panel')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/assistant/model-center?tab=workbench'))
    expect(screen.getByTestId('location').textContent).not.toContain('modelId')
    expect(screen.getByTestId('location').textContent).not.toContain('targetId')
  })

  it('retains the original link while authentication is loading', () => {
    useAuthStore.setState({ status: 'loading', user: null })
    render(<AppProviders><MemoryRouter initialEntries={['/assistant/model-center?tab=models&targetId=target-1']}><ModelCenterPage /><LocationProbe /></MemoryRouter></AppProviders>)
    expect(screen.getByTestId('location')).toHaveTextContent('?tab=models&targetId=target-1')
  })
})
