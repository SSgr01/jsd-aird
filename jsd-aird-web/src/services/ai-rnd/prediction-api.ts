import { httpClient } from '@/services/http/client'
import { HttpError } from '@/services/http/errors'
import type { PredictionContext, PredictionRequest, PredictionResponse, PredictionRunning } from './ai-rnd-types'

interface ApiResponse<T> { data: T }
const root = '/api/v1/ai/rnd'
const data = <T>(response: { data: ApiResponse<T> }) => response.data.data

export const predictionApi = {
  async context(targetIds: string[] = []) {
    return data(await httpClient.get<ApiResponse<PredictionContext>>(`${root}/prediction-context`, {
      params: targetIds.length ? { targetId: targetIds } : undefined,
      paramsSerializer: { indexes: null },
    }))
  },
  async predict(input: PredictionRequest, idempotencyKey: string) {
    const response = await httpClient.post<PredictionResponse | PredictionRunning>(`${root}/predictions`, input, {
      headers: { 'Idempotency-Key': idempotencyKey },
      validateStatus: (status) => [200, 202, 422, 503].includes(status),
    })
    const payload = response.data
    if (payload?.executionStatus !== 'RUNNING' && !Array.isArray(payload?.results)) {
      const failure = payload as unknown as { message?: string; code?: string; traceId?: string }
      throw new HttpError(failure?.message || '预测服务返回异常，请稍后重试', failure?.code || 'PREDICTION_RESPONSE_INVALID', response.status, failure?.traceId)
    }
    return payload
  },
  async record(id: string) {
    const response = await httpClient.get<PredictionResponse | PredictionRunning>(`${root}/prediction-records/${id}`)
    return response.data
  },
}
