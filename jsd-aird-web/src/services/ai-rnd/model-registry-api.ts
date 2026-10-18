import { httpClient } from '@/services/http/client'
import { generateUUID } from '@/utils/uuid'
import type { ModelActionCommand, ModelActionResult, ModelDetail, ModelVersion, Page } from './ai-rnd-types'

interface ApiResponse<T> { data: T }
const root = '/api/v1/ai/rnd'
const data = <T>(response: { data: ApiResponse<T> }) => response.data.data
const key = () => ({ headers: { 'Idempotency-Key': generateUUID() } })

export const modelRegistryApi = {
  async models(params: { status?: string; category?: string; keyword?: string; page?: number; size?: number } = {}) { return data(await httpClient.get<ApiResponse<Page<ModelVersion>>>(`${root}/models`, { params })) },
  async model(id: string) { return data(await httpClient.get<ApiResponse<ModelDetail>>(`${root}/models/${id}`)) },
  async pause(id: string, input: ModelActionCommand) { return data(await httpClient.post<ApiResponse<ModelActionResult>>(`${root}/models/${id}/pause`, input, key())) },
  async rollback(id: string, input: ModelActionCommand) { return data(await httpClient.post<ApiResponse<ModelActionResult>>(`${root}/models/${id}/rollback`, input, key())) },
}
