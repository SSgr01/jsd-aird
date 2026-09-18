import { beforeEach, describe, expect, it, vi } from 'vitest'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), patch: vi.fn() }))
vi.mock('@/services/http/client', () => ({ httpClient: httpMock }))

import { modelingApi } from './modeling-api'

const response = <T,>(value: T) => Promise.resolve({ data: { data: value } })

describe('modeling settings API', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.spyOn(crypto, 'randomUUID').mockReturnValue('00000000-0000-4000-8000-000000000002')
    httpMock.get.mockResolvedValue(response([]))
    httpMock.post.mockResolvedValue(response({}))
    httpMock.patch.mockResolvedValue(response({}))
  })

  it('carries the current revision when freezing and retiring a material dictionary', async () => {
    await modelingApi.freezeMaterialDictionary('dictionary-1', 4)
    await modelingApi.retireMaterialDictionary('dictionary-1', 5, 'obsolete')
    expect(httpMock.post).toHaveBeenNthCalledWith(
      1,
      '/api/v1/ai/rnd/material-dictionaries/dictionary-1/freeze',
      { expectedRevision: 4 },
      { headers: { 'Idempotency-Key': '00000000-0000-4000-8000-000000000002' } },
    )
    expect(httpMock.post).toHaveBeenNthCalledWith(
      2,
      '/api/v1/ai/rnd/material-dictionaries/dictionary-1/retire',
      { expectedRevision: 5, reason: 'obsolete' },
      { headers: { 'Idempotency-Key': '00000000-0000-4000-8000-000000000002' } },
    )
  })
})
