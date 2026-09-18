/* API payloads are validated by formula-model.v2; this client only unwraps
 * the versioned envelope and intentionally leaves the typed JSON payloads
 * extensible for the four result families. */
/* eslint-disable @typescript-eslint/no-explicit-any, @typescript-eslint/no-unnecessary-type-assertion */
import { httpClient } from '@/services/http/client'

interface ApiResponse<T> { data: T }
const root = '/api/v1/ai/rnd'
const unwrap = <T>(response: { data: ApiResponse<T> }) => response.data.data

export type FormulaTarget = {
  targetId: string; code: string; name: string; category?: string; valueType: 'CONTINUOUS'|'ORDINAL'|'BINARY'|'CATEGORICAL'
  available: boolean; modelVersionId?: string; operators?: string[]; unavailableReasons: string[]; unit?: string; classes?: string[]; positiveClass?: string
}
export type FormulaContext = {
  targets: FormulaTarget[]
  selected: Array<FormulaTarget & { modelVersionId?: string }>
  requiredInputs: Array<{ code: string; name?: string; valueType?: string; unit?: string; required?: boolean }>
  materialIntersection: Array<{ materialId: string; code?: string; name?: string; category?: string; role?: string; encoderIndex?: number }>
  candidateCountDefault: number
}
export type FormulaCandidate = { id: string; candidateNo: number; title: string; formula: any; targetResults: any; quality: any; applicability: any; ruleCheck: any; score: number }
export type ResearchRun = {
  id: string; runType: string; executionStatus: 'QUEUED'|'RUNNING'|'SUCCEEDED'|'FAILED'|'CANCELLED'; outcomeStatus?: 'SUCCEEDED'|'PARTIAL'|'BLOCKED'
  progress: number; currentStage?: string; requestedCandidateCount: number; actualCandidateCount: number; candidates: FormulaCandidate[]; shortfallReason?: any; modelBindings: Record<string,string>; frozenEvidence?: any; requestId: string; updatedAt?: string
  rejectionSummary?: Record<string,number>; actionHints?: string[]
}
export type RunAccepted = { runId: string; asyncJobId?: string; executionStatus: string; modelBindings: Record<string,string>; pollAfterMs: number }

export const formulaDesignApi = {
  async context(targetIds: string[] = []) {
    return unwrap(await httpClient.get<ApiResponse<FormulaContext>>(`${root}/formula-design-context`, {
      params: targetIds.length ? { targetId: targetIds } : undefined, paramsSerializer: { indexes: null },
    }))
  },
  async submit(request: unknown, idempotencyKey: string) {
    const response = await httpClient.post<ApiResponse<RunAccepted> | RunAccepted | ResearchRun>(`${root}/formula-designs`, request, { headers: { 'Idempotency-Key': idempotencyKey }, validateStatus: (status) => [202, 200, 409, 422].includes(status) })
    if (response.status >= 400) {
      const error = response.data as { message?: string; data?: { message?: string } }
      throw new Error(error?.message || error?.data?.message || '配方搜索条件存在冲突')
    }
    const payload = response.data as ApiResponse<RunAccepted> | RunAccepted | ResearchRun
    const value = 'data' in payload ? payload.data : payload
    if ('runId' in value) return value
    return { runId: value.id, executionStatus: value.executionStatus, modelBindings: value.modelBindings, pollAfterMs: 1000 }
  },
  async run(id: string) { return unwrap(await httpClient.get<ApiResponse<ResearchRun>>(`${root}/research-runs/${id}`)) },
}
