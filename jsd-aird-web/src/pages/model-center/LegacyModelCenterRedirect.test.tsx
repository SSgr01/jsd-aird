import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it } from 'vitest'

import { useAuthStore } from '@/stores/auth-store'
import { LegacyModelCenterRedirect } from './LegacyModelCenterRedirect'

function LocationProbe() {
  const location = useLocation()
  return <output data-testid="location">{location.pathname}{location.search}</output>
}

describe('legacy model center links', () => {
  afterEach(() => useAuthStore.setState({ user: null, status: 'anonymous' }))

  it.each([
    ['/assistant/modeling-settings', false, true, '?tab=workbench'],
    ['/assistant/modeling-settings?targetId=target-1', false, true, '?tab=models&targetId=target-1'],
    ['/assistant/modeling-settings?action=create-target&targetId=target-1', false, true, '?tab=workbench'],
    ['/assistant/model-center/targets/target-1', false, true, '?tab=models&targetId=target-1'],
    ['/assistant/model-center/targets/target-1', false, false, '?tab=workbench'],
    ['/assistant/model-management?view=jobs&modelId=model-1', true, true, '?tab=models&modelId=model-1&view=jobs'],
  ])('redirects %s to a permitted current page', async (url, models, canReadModels, query) => {
    useAuthStore.setState({ status: 'authenticated', user: {
      userId: 'user-1', organizationId: 'org-1', organizationName: '组织', username: 'user',
      displayName: '用户', status: 'ACTIVE', authVersion: 1,
      permissions: canReadModels ? ['ai.model.read', 'ai.modeling.read'] : ['ai.modeling.read'],
    } })
    render(<MemoryRouter initialEntries={[url]}><Routes>
      <Route path="/assistant/model-center" element={<LocationProbe />} />
      <Route path="/assistant/model-center/targets/:targetId" element={<LegacyModelCenterRedirect />} />
      <Route path="*" element={<LegacyModelCenterRedirect models={models} />} />
    </Routes></MemoryRouter>)
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe(`/assistant/model-center${query}`))
  })
})
