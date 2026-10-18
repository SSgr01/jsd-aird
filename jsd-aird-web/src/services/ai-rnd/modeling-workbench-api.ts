import { httpClient } from '@/services/http/client'
import type { HistoryDataset, HistoryDatasetOption } from '@/services/ai-rnd/optimization-api'

interface ApiResponse<T> { data: T; message?: string }
const root = '/api/v1/ai/rnd/modeling-workbench'
const unwrap = <T>(response: { data: ApiResponse<T> }) => response.data.data

export type WorkbenchMetric = number | string | Record<string, unknown> | Array<unknown>
export type WorkbenchRegistration = { modelVersionId: string; targetId: string; version: number; status: string; registeredAt: string }
export type ModelingWorkbenchRun = {
  datasetSnapshot?: HistoryDataset
  selectedRowIdsByDataset?: Record<string, string[]>
  registration?: WorkbenchRegistration | null
  id: string
  datasetName: string
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  progress: number
  currentStage: string
  rowCount: number
  selectedRowCount?: number
  xColumns: Array<{ code: string; name: string; kind: 'NUMERIC' | 'CATEGORICAL'; unit?: string | null }>
  yColumn: { code: string; name: string; kind: 'NUMERIC' | 'CATEGORICAL'; unit?: string | null }
  result?: {
    rowCount: number
    excludedRowCount: number
    metrics: Record<string, WorkbenchMetric>
    applicabilityDomain: Record<string, unknown>
  }
  error?: { code?: string; message?: string } | null
  createdAt: string
  updatedAt: string
}

export const modelingWorkbenchApi = {
  async datasets(keyword?: string) {
    return unwrap(await httpClient.get<ApiResponse<{ items: HistoryDatasetOption[] }>>(`${root}/datasets`, { params: { keyword } }))
  },
  async dataset(id: string) {
    return unwrap(await httpClient.get<ApiResponse<HistoryDataset>>(`${root}/datasets/${encodeURIComponent(id)}`))
  },
  async train(command: { datasetIds: string[]; selectedRowIdsByDataset: Record<string, string[]>; xColumns: string[]; yColumn: string }, idempotencyKey: string) {
    return unwrap(await httpClient.post<ApiResponse<{ runId: string; status: string; pollAfterMs: number }>>(
      `${root}/runs`, command, { headers: { 'Idempotency-Key': idempotencyKey } },
    ))
  },
  async register(id: string, idempotencyKey: string) {
    return unwrap(await httpClient.post<ApiResponse<WorkbenchRegistration>>(`${root}/runs/${encodeURIComponent(id)}/register`, {}, { headers: { 'Idempotency-Key': idempotencyKey } }))
  },
  async run(id: string) {
    return unwrap(await httpClient.get<ApiResponse<ModelingWorkbenchRun>>(`${root}/runs/${encodeURIComponent(id)}`))
  },
}
