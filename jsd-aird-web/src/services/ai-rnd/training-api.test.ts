import { beforeEach, describe, expect, it, vi } from 'vitest'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn() }))
vi.mock('@/services/http/client', () => ({ httpClient: httpMock }))

import { trainingApi } from './training-api'

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
    await trainingApi.updateSettings({ autoLearningEnabled: true, expectedRevision: 3 })
    await trainingApi.evaluate()
    await trainingApi.retry('job-1', 4)
    await trainingApi.cancel('job-2', 5, '数据配置有误')
    await trainingApi.activate('model-1', { expectedRevision: 6, expectedActiveModelVersionId: 'active-1', reason: '验证通过' })
    expect(httpMock.put).toHaveBeenCalledWith('/api/v1/ai/rnd/training-settings', { autoLearningEnabled: true, expectedRevision: 3 }, headers)
    expect(httpMock.post).toHaveBeenCalledWith('/api/v1/ai/rnd/training-jobs/evaluate', undefined, headers)
    expect(httpMock.post).toHaveBeenCalledWith('/api/v1/ai/rnd/training-jobs/job-1/retry', { expectedRevision: 4 }, headers)
    expect(httpMock.post).toHaveBeenCalledWith('/api/v1/ai/rnd/training-jobs/job-2/cancel', { expectedRevision: 5, reason: '数据配置有误' }, headers)
    expect(httpMock.post).toHaveBeenCalledWith('/api/v1/ai/rnd/models/model-1/activate', { expectedRevision: 6, expectedActiveModelVersionId: 'active-1', reason: '验证通过' }, headers)
  })
})
