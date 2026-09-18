/* eslint-disable @typescript-eslint/no-explicit-any */
import { httpClient } from '@/services/http/client'

interface ApiResponse<T> { data: T; message?: string }
const root = '/api/v1/ai/rnd'
const unwrap = <T>(response: { data: ApiResponse<T> }) => response.data.data

export type BaselineType = 'EXPERIMENT_VERSION'|'DATA_SAMPLE_REVISION'|'RESEARCH_CANDIDATE'
export type OptimizationBaseline = { type: BaselineType; id: string; versionId: string; title: string; sourceLabel: string; actualTotal: number; materials: any[]; inputs: Record<string,any>; results: any; contentHash: string; updatedAt: string }
export type OptimizationTarget = { targetId: string; code?: string; name: string; category?: string; valueType: 'CONTINUOUS'|'ORDINAL'|'BINARY'|'CATEGORICAL'; available: boolean; modelVersionId?: string; classes?: string[] }
export type ExperimentTemplateOption = { templateId: string; templateVersionId: string; templateCode: string; name: string; category?: string; versionNo: number; format: string }
export type OptimizationContext = { baseline: OptimizationBaseline; frozenBaseline: any; targets: OptimizationTarget[]; selected: OptimizationTarget[]; materialIntersection: Array<{ materialId: string; code?: string; name?: string }>; adjustableInputs: Array<{ code: string; name?: string; valueType?: string; unit?: string; availabilityTiming?: string }>; controlTotal: number; candidateCount: number; experimentTemplates?: ExperimentTemplateOption[] }
export type OptimizationCandidate = { id: string; candidateNo: number; strategy: 'CONTROL'|'CONSERVATIVE'|'BALANCED'|'EXPLORATORY'; title: string; formula: any; inputs: any; results: any; quality: any; applicability: any; ruleCheck: any; score: number; targetTotal: number; baselineDistance: number; changes: any; strategyEvidence: any; risks: string[] }
export type OptimizationRun = { id: string; executionStatus: 'QUEUED'|'RUNNING'|'SUCCEEDED'|'FAILED'|'CANCELLED'; outcomeStatus?: 'SUCCEEDED'|'PARTIAL'|'BLOCKED'; progress: number; currentStage?: string; requestedCandidateCount: number; actualCandidateCount: number; missingStrategies: string[]; missingStrategyReasons?: Record<string,string>; candidates: OptimizationCandidate[]; error?: { code?: string; message?: string }; frozenBaseline: any; frozenRequest: any; modelBindings: Record<string,string>; requestId: string; updatedAt: string; searchEngine?: 'BAYBE'|'DETERMINISTIC_CANDIDATE_POOL'; searchStrategy?: 'CONTINUOUS_BAYBE'|'DISCRETE_DETERMINISTIC_POOL'; searchEvidence?: Record<string, unknown> }
export type DraftLink = { candidateId: string; experimentId: string; experimentVersionId: string; experimentNo: string; status: string; href: string; createdAt: string }

export const optimizationApi = {
  async baselines(sourceType?: BaselineType, keyword?: string) { return unwrap(await httpClient.get<ApiResponse<{ items: OptimizationBaseline[] }>>(`${root}/optimization-baselines`, { params: { sourceType, keyword, page: 0, size: 100 } })) },
  async context(type: BaselineType, id: string, targetIds: string[] = []) { return unwrap(await httpClient.get<ApiResponse<OptimizationContext>>(`${root}/optimization-context`, { params: { baselineType: type, baselineId: id, targetId: targetIds.length ? targetIds : undefined }, paramsSerializer: { indexes: null } })) },
  async submit(request: unknown, key: string) { const response = await httpClient.post<any>(`${root}/experiment-optimizations`, request, { headers: { 'Idempotency-Key': key }, validateStatus: (s) => [200,202,409,422,503].includes(s) }); if (response.status >= 400) throw new Error(response.data?.message || '实验优化条件不满足'); const value = response.data?.data ?? response.data; return { runId: value.runId ?? value.id, executionStatus: value.executionStatus, pollAfterMs: value.pollAfterMs ?? 1000 } },
  async run(id: string) { return unwrap(await httpClient.get<ApiResponse<OptimizationRun>>(`${root}/experiment-optimizations/${id}`)) },
  async createDrafts(id: string, command: unknown, key: string) { return unwrap(await httpClient.post<ApiResponse<{ runId: string; experiments: DraftLink[] }>>(`${root}/research-runs/${id}/experiment-drafts`, command, { headers: { 'Idempotency-Key': key } })) },
  async links(id: string) { return unwrap(await httpClient.get<ApiResponse<DraftLink[]>>(`${root}/research-runs/${id}/experiment-links`)) },
  async feedback(id: string) { return unwrap(await httpClient.get<ApiResponse<any>>(`${root}/research-runs/${id}/feedback`)) },
}
