import { beforeEach, describe, expect, it, vi } from 'vitest'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn() }))
vi.mock('@/services/http/client', () => ({ httpClient: httpMock }))

import { modelRegistryApi } from './model-registry-api'

describe('training and model API', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.spyOn(crypto, 'randomUUID').mockReturnValue('00000000-0000-4000-8000-000000000059')
    httpMock.get.mockResolvedValue({ data: { data: {} } })
    httpMock.post.mockResolvedValue({ data: { data: {} } })
    httpMock.put.mockResolvedValue({ data: { data: {} } })
  })

  it('sends idempotency keys and optimistic revisions for every mutation', async () => {
    const headers = { headers: { 'Idempotency-Key': '00000000-0000-4000-8000-000000000059' } }
    await modelRegistryApi.pause('model-1', { expectedRevision: 6, expectedActiveModelVersionId: 'model-1', reason: '暂停使用' })
    await modelRegistryApi.rollback('model-2', { expectedRevision: 3, expectedActiveModelVersionId: 'model-1', reason: '回退版本' })
    expect(httpMock.post).toHaveBeenCalledWith('/api/v1/ai/rnd/models/model-1/pause', { expectedRevision: 6, expectedActiveModelVersionId: 'model-1', reason: '暂停使用' }, headers)
    expect(httpMock.post).toHaveBeenCalledWith('/api/v1/ai/rnd/models/model-2/rollback', { expectedRevision: 3, expectedActiveModelVersionId: 'model-1', reason: '回退版本' }, headers)
  })
})
