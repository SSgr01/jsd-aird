import { httpClient } from '@/services/http/client'
import { generateUUID } from '@/utils/uuid'
import type { PredictionContext, PredictionRequest, PredictionResponse, PredictionRunning, QualityPolicy, QualityRule } from './ai-rnd-types'

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
    return response.data
  },
  async record(id: string) {
    const response = await httpClient.get<PredictionResponse | PredictionRunning>(`${root}/prediction-records/${id}`)
    return response.data
  },
  async qualityPolicies(targetId: string) { return data(await httpClient.get<ApiResponse<QualityPolicy[]>>(`${root}/targets/${targetId}/quality-policies`)) },
  async createQualityPolicy(targetId: string, input: { defaultTrustLevel: string; defaultExplanation: string; rules: QualityRule[]; expectedRevision: number }) {
    return data(await httpClient.post<ApiResponse<QualityPolicy>>(`${root}/targets/${targetId}/quality-policies`, input, { headers: { 'Idempotency-Key': generateUUID() } }))
  },
  async publishQualityPolicy(id: string, expectedRevision: number) {
    return data(await httpClient.post<ApiResponse<QualityPolicy>>(`${root}/quality-policies/${id}/publish`, { expectedRevision }, { headers: { 'Idempotency-Key': generateUUID() } }))
  },
}
