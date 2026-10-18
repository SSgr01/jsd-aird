/* eslint-disable @typescript-eslint/no-explicit-any */
import { HttpError } from '@/services/http/errors'
import { httpClient } from '@/services/http/client'

interface ApiResponse<T> { data: T; message?: string }
const root = '/api/v1/ai/rnd'
const unwrap = <T>(response: { data: ApiResponse<T> }) => response.data.data

export type HistoryDatasetOption = { id: string; name: string; rowCount: number; updatedAt: string }
export type HistoryDatasetColumn = {
  code: string
  name: string
  kind: 'NUMERIC'|'CATEGORICAL'
  unit?: string
  testMethods?: string[]
  testStage?: string
  semanticKey?: string
  semanticType?: 'MATERIAL_RATIO'
  materialId?: string
  materialCode?: string
  mappingStatus?: 'MAPPED'|'UNMAPPED'
  compositionBasis?: string
  measurementComponent?: string
  filledCount: number
}
export type HistoryDatasetRow = { id: string; versionId: string; label: string; title: string; sourceDatasetId?: string; sourceDatasetName?: string; sourceExperimentId?: string; sampleKey?: string; sampleNo?: string; sourceContextKey?: string; sourceRefs?: Record<string, unknown>; values: Record<string, number|string> }
export type HistoryDataset = { id: string; name: string; rowCount: number; projectionVersion?: number; issues?: Array<{ rowId: string; sampleNo: string; columnCode: string; rawValue: string; message: string; sourceRefs?: unknown }>; sourceDatasetIds?: string[]; columns: HistoryDatasetColumn[]; rows: HistoryDatasetRow[] }
export type OptimizationCandidate = { id: string; candidateNo: number; strategy: string; title: string; formula: any; inputs: any; results: any; quality: any; applicability: any; ruleCheck: any; score: number; targetTotal: number; baselineDistance: number; changes: any; strategyEvidence: any; risks: string[] }
export type OptimizationRun = { id: string; executionStatus: 'QUEUED'|'RUNNING'|'SUCCEEDED'|'FAILED'|'CANCELLED'; outcomeStatus?: 'SUCCEEDED'|'PARTIAL'|'BLOCKED'; progress: number; currentStage?: string; requestedCandidateCount: number; actualCandidateCount: number; missingStrategies: string[]; missingStrategyReasons?: Record<string,string>; candidates: OptimizationCandidate[]; error?: { code?: string; message?: string }; frozenBaseline: any; frozenRequest: any; modelBindings: Record<string,string>; requestId: string; updatedAt: string; simulation?: boolean; predictionMode?: 'FORMAL'|'SIMULATION'; searchEngine?: 'BAYBE'|'DETERMINISTIC_CANDIDATE_POOL'|'GAUSSIAN_PROCESS_EI'; searchStrategy?: 'CONTINUOUS_BAYBE'|'DISCRETE_DETERMINISTIC_POOL'|'EXPECTED_IMPROVEMENT'; searchEvidence?: Record<string, unknown> }
export type OptimizationObservation = { candidateId: string; targetId?: string; fieldCode?: string; value: number|string; updatedAt?: string }

type OptimizationSubmission = {
  executionStatus: OptimizationRun['executionStatus']; pollAfterMs?: number
} & ({ runId: string; id?: never } | { id: string; runId?: never })
type OptimizationSubmissionResponse = (OptimizationSubmission | ApiResponse<OptimizationSubmission>) & {
  code?: string; message?: string
}

async function submitOptimization(path: string, request: unknown, key: string, failureMessage: string) {
  const response = await httpClient.post<OptimizationSubmissionResponse>(`${root}${path}`, request, {
    headers: { 'Idempotency-Key': key }, validateStatus: (status) => [200, 202, 409, 422, 503].includes(status),
  })
  if (response.status >= 400) {
    throw new HttpError(response.data.message || failureMessage, response.data.code || 'OPTIMIZATION_REJECTED', response.status)
  }
  const value = 'data' in response.data ? response.data.data : response.data
  return { runId: value.runId ?? value.id, executionStatus: value.executionStatus, pollAfterMs: value.pollAfterMs ?? 1000 }
}

export const optimizationApi = {
  async historyDatasets(keyword?: string) { return unwrap(await httpClient.get<ApiResponse<{ items: HistoryDatasetOption[] }>>(`${root}/optimization-datasets`, { params: { keyword } })) },
  async historyDataset(id: string) { return unwrap(await httpClient.get<ApiResponse<HistoryDataset>>(`${root}/optimization-datasets/${id}`)) },
  submit(request: unknown, key: string) { return submitOptimization('/experiment-optimizations', request, key, '实验优化条件不满足') },
  nextRound(id: string, key: string) { return submitOptimization(`/research-runs/${id}/next-round`, {}, key, '下一轮优化启动失败') },
  async run(id: string) { return unwrap(await httpClient.get<ApiResponse<OptimizationRun>>(`${root}/experiment-optimizations/${id}`)) },
  async observations(id: string) { return unwrap(await httpClient.get<ApiResponse<{ observations: OptimizationObservation[] }>>(`${root}/research-runs/${id}/observations`)) },
  async saveObservations(id: string, observations: Array<{ candidateId: string; targetId?: string; fieldCode?: string; value: number|string }>) {
    return unwrap(await httpClient.put<ApiResponse<{ observations: OptimizationObservation[]; savedCount: number }>>(`${root}/research-runs/${id}/observations`, { observations }))
  },
}
