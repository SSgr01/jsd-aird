import { beforeEach, describe, expect, it, vi } from 'vitest'
import { predictionApi } from './prediction-api'
const { post } = vi.hoisted(() => ({ post: vi.fn() }))

vi.mock('@/services/http/client', () => ({ httpClient: { post } }))

describe('prediction response handling', () => {
  beforeEach(() => vi.clearAllMocks())
  it('reports an error envelope instead of displaying it as a prediction', async () => {
    post.mockResolvedValue({ status: 503, data: { code: 'COMPUTE_UNAVAILABLE', message: '计算服务未连接' } })
    await expect(predictionApi.predict({ inputs: {}, targets: [] }, 'request')).rejects.toMatchObject({ code: 'COMPUTE_UNAVAILABLE', message: '计算服务未连接' })
  })
  it('preserves per-target blocked results and their actionable details', async () => {
    const result = { executionStatus: 'SUCCEEDED', results: [{ status: 'BLOCKED', errorCode: 'OUT_OF_DOMAIN', detail: { violations: [{ fieldCode: 'TEMPERATURE', value: 90, minimum: 20, maximum: 60 }] } }] }
    post.mockResolvedValue({ status: 422, data: result })
    await expect(predictionApi.predict({ inputs: {}, targets: [] }, 'request')).resolves.toEqual(result)
  })
})
