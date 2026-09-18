import { httpClient } from '@/services/http/client'
import { generateUUID } from '@/utils/uuid'
import type { ModelActionCommand, ModelActionResult, ModelComparison, ModelDetail, ModelVersion, Page, TrainingEvaluationResult, TrainingJobDetail, TrainingJobSummary, TrainingSettings } from './ai-rnd-types'

interface ApiResponse<T> { data: T }
const root = '/api/v1/ai/rnd'
const data = <T>(response: { data: ApiResponse<T> }) => response.data.data
const key = () => ({ headers: { 'Idempotency-Key': generateUUID() } })

export const trainingApi = {
  async settings() { return data(await httpClient.get<ApiResponse<TrainingSettings>>(`${root}/training-settings`)) },
  async updateSettings(input: { autoLearningEnabled: boolean; expectedRevision: number }) { return data(await httpClient.put<ApiResponse<TrainingSettings>>(`${root}/training-settings`, input, key())) },
  async evaluate() { return data(await httpClient.post<ApiResponse<TrainingEvaluationResult>>(`${root}/training-jobs/evaluate`, undefined, key())) },
  async jobs(params: { status?: string; keyword?: string; page?: number; size?: number } = {}) { return data(await httpClient.get<ApiResponse<Page<TrainingJobSummary>>>(`${root}/training-jobs`, { params })) },
  async job(id: string) { return data(await httpClient.get<ApiResponse<TrainingJobDetail>>(`${root}/training-jobs/${id}`)) },
  async retry(id: string, expectedRevision: number) { return data(await httpClient.post<ApiResponse<unknown>>(`${root}/training-jobs/${id}/retry`, { expectedRevision }, key())) },
  async cancel(id: string, expectedRevision: number, reason: string) { return data(await httpClient.post<ApiResponse<unknown>>(`${root}/training-jobs/${id}/cancel`, { expectedRevision, reason }, key())) },
  async models(params: { status?: string; category?: string; keyword?: string; page?: number; size?: number } = {}) { return data(await httpClient.get<ApiResponse<Page<ModelVersion>>>(`${root}/models`, { params })) },
  async model(id: string) { return data(await httpClient.get<ApiResponse<ModelDetail>>(`${root}/models/${id}`)) },
  async comparison(id: string) { return data(await httpClient.get<ApiResponse<ModelComparison>>(`${root}/models/${id}/comparison`)) },
  async activate(id: string, input: ModelActionCommand) { return data(await httpClient.post<ApiResponse<ModelActionResult>>(`${root}/models/${id}/activate`, input, key())) },
  async pause(id: string, input: ModelActionCommand) { return data(await httpClient.post<ApiResponse<ModelActionResult>>(`${root}/models/${id}/pause`, input, key())) },
  async rollback(id: string, input: ModelActionCommand) { return data(await httpClient.post<ApiResponse<ModelActionResult>>(`${root}/models/${id}/rollback`, input, key())) },
}
