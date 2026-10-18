import { httpClient } from '@/services/http/client'
import { HttpError } from '@/services/http/errors'
import type { PredictionInputField, TypedPrediction } from './ai-rnd-types'

interface ApiResponse<T> { data: T }
const root = '/api/v1/ai/rnd'
const unwrap = <T>(response: { data: ApiResponse<T> }) => response.data.data

export type FormulaTarget = {
  missingFormulaMaterials?: string[]; formulaIssues?: string[]
  targetId: string; code: string; name: string; category?: string; modelSource?: { datasetName: string; xColumns: Array<{ name: string }> }; valueType: 'CONTINUOUS'|'ORDINAL'|'BINARY'|'CATEGORICAL'
  available: boolean; simulation?: boolean; testOnlySimulation?: boolean; modelVersionId?: string; operators?: string[]; unavailableReasons: string[]; unit?: string; classes?: string[]; positiveClass?: string; registered?: boolean; statusLabel?: string; decisionThreshold?: number; testMethod?: string; testStage?: string; targetStatus?: string; formalPredictionAvailable?: boolean
}
export type FormulaMaterial = { materialId: string; code?: string; name?: string; sourceColumnName?: string; fixedValue?: number; category?: string; role?: string; encoderIndex?: number; supportedRanges?: Array<{ targetId: string; targetName: string; minimum: number; maximum: number }> }
export type FormulaContext = {
  targets: FormulaTarget[]
  selected: Array<FormulaTarget & { modelVersionId?: string }>
  requiredInputs: PredictionInputField[]
  materialIntersection: FormulaMaterial[]
  candidateCountDefault: number; simulationAuthorized?: boolean; configurationConflicts?: Array<{ fieldCode: string; definitions?: string[] }>
}
export type GoalValue = number | [number | undefined, number | undefined] | { target?: number; tolerance?: number; label?: string }
export type FormulaGoal = { targetId: string; valueType: FormulaTarget['valueType']; expectedModelVersionId?: string; operator: string; value?: GoalValue; mandatory: boolean; weight: number; minimumProbability?: number }
export type FormulaRequest = {
  typedGoals: FormulaGoal[]; targetTotal: number; formulaBasis: 'MASS_PERCENT'
  baselineFormula: { basis: 'MASS_PERCENT'; compositionComplete: boolean; components: Array<{ materialId: string; ratio: number; unit: string; amountKnown: boolean }>; recordedTotal: number }
  fixedInputs: Record<string, string | number | boolean>; searchSpace: { allowedMaterialIds: string[]; variableMaterials: Array<{ materialId: string; minimum: number; maximum: number }>; variableInputs: Record<string, { minimum: number; maximum: number; step?: number }> }; constraints: Record<string, unknown>; candidateCount: number
}
export type FormulaTargetResult = { targetId: string; targetName?: string; status: string; result?: TypedPrediction; prediction?: TypedPrediction; code?: string; errorCode?: string; applicability?: { status?: string } }
export type FormulaCandidate = { id: string; candidateNo: number; title: string; formula: { components?: Array<{ materialId: string; ratio?: number; name?: string; code?: string; role?: string }> }; inputs?: Record<string, string | number | boolean>; targetResults: { targets?: FormulaTargetResult[] }; quality?: unknown; applicability?: { status?: string }; ruleCheck?: unknown; score: number }
export type ResearchRun = {
  id: string; runType: string; executionStatus: 'QUEUED'|'RUNNING'|'SUCCEEDED'|'FAILED'|'CANCELLED'; outcomeStatus?: 'SUCCEEDED'|'PARTIAL'|'BLOCKED'
  progress: number; currentStage?: string; requestedCandidateCount: number; actualCandidateCount: number; candidates: FormulaCandidate[]; shortfallReason?: string | { code?: string; message?: string }; modelBindings: Record<string,string>; frozenEvidence?: Partial<FormulaRequest> & { displayContext?: FormulaContext; simulation?: boolean }; requestId: string; updatedAt?: string
  rejectionSummary?: Record<string,number>; actionHints?: string[]; simulation?: boolean; predictionMode?: 'FORMAL'|'SIMULATION'
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
      const error = response.data as { code?: string; message?: string; data?: { message?: string } }
      throw new HttpError(error?.message || error?.data?.message || '配方搜索条件存在冲突', error?.code || 'FORMULA_SEARCH_REJECTED', response.status)
    }
    const payload = response.data
    const value = 'data' in payload ? payload.data : payload
    if ('runId' in value) return value
    return { runId: value.id, executionStatus: value.executionStatus, modelBindings: value.modelBindings, pollAfterMs: 1000 }
  },
  async run(id: string) { return unwrap(await httpClient.get<ApiResponse<ResearchRun>>(`${root}/research-runs/${id}`)) },
}
