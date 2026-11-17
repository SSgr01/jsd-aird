/* eslint-disable @typescript-eslint/unbound-method */
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { beforeEach, expect, it, vi } from 'vitest'
import { modelingWorkbenchApi } from '@/services/ai-rnd/modeling-workbench-api'
import { ModelingExperimentSelector } from './ModelingExperimentSelector'

vi.mock('@/services/ai-rnd/modeling-workbench-api', () => ({ modelingWorkbenchApi: { batches: vi.fn(), experiments: vi.fn() } }))
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(modelingWorkbenchApi.batches).mockResolvedValue({ items: [] })
  vi.mocked(modelingWorkbenchApi.experiments).mockImplementation((_id, params) => Promise.resolve({
    total: 21,
    items: [{ id: `exp-${params.page}`, versionId: 'v', label: `B-${params.page}`, title: 'PU773', owner: '林志华', date: '2025-02-28' }],
  }))
})
function Picker() {
  const [ids, setIds] = useState<string[]>([])
  return <ModelingExperimentSelector id="dataset" selected={ids} onChange={setIds} disabled={false} />
}
it('requests only one page, keeps selections across pages, and sends the search to the server', async () => {
  render(<Picker />)
  await screen.findByText('B-1')
  expect(modelingWorkbenchApi.experiments).toHaveBeenCalledWith('dataset', expect.objectContaining({ page: 1, size: 20 }), expect.any(AbortSignal))
  fireEvent.click(screen.getAllByRole('checkbox')[1]!)
  expect(await screen.findByText(/已选 1 份实验/)).toBeInTheDocument()
  fireEvent.click(screen.getByTitle('2'))
  await screen.findByText('B-2')
  fireEvent.click(screen.getAllByRole('checkbox')[1]!)
  expect(await screen.findByText(/已选 2 份实验/)).toBeInTheDocument()
  const search = screen.getByRole('searchbox', { name: '筛选实验' })
  fireEvent.change(search, { target: { value: 'B-36' } })
  fireEvent.keyDown(search, { key: 'Enter', code: 'Enter', keyCode: 13 })
  await waitFor(() => expect(modelingWorkbenchApi.experiments).toHaveBeenLastCalledWith('dataset', expect.objectContaining({ keyword: 'B-36', page: 1, size: 20 }), expect.any(AbortSignal)))
  expect(screen.getByText(/已选 2 份实验/)).toBeInTheDocument()
})
it('shows a failed page as retryable and does not silently load the complete dataset', async () => {
  vi.mocked(modelingWorkbenchApi.experiments).mockRejectedValueOnce(new Error('offline'))
  render(<Picker />)
  await screen.findByRole('alert')
  fireEvent.click(screen.getByRole('button', { name: '重试加载' }))
  expect(await screen.findByText('B-1')).toBeInTheDocument()
})
