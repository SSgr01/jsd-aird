/** Frozen ai-rnd.v1 transport types. R01 intentionally exports no HTTP client. */
export const AI_RND_CONTRACT_VERSION = 'ai-rnd.v1' as const

export type ValueType = 'CONTINUOUS' | 'ORDINAL' | 'BINARY' | 'CATEGORICAL'
export type DomainStatus = 'IN_DOMAIN' | 'NEAR_BOUNDARY' | 'OUT_OF_DOMAIN'
export type ModelStatus = 'CANDIDATE' | 'ACTIVE' | 'PAUSED' | 'RETIRED' | 'REJECTED'

export interface Page<T> { items: T[]; total: number; page: number; size: number; totalPages?: number }
export interface ErrorDetail { targetId?: string; fieldCode?: string; missingFields?: string[]; evidence?: unknown }
export interface ErrorResponse { code: string; message: string; requestId: string; detail?: ErrorDetail }
export interface VersionCommand { expectedRevision: number }
export interface ReasonedVersionCommand extends VersionCommand { reason: string }

export type TrainingJobStatus = 'QUEUED' | 'MATERIALIZING' | 'SNAPSHOT_VALIDATING' | 'FOLDING' | 'TRAINING' | 'VALIDATING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
export interface TrainingJobSummary {
  id: string; targetId: string; targetName: string; category: string; valueType: ValueType
  status: TrainingJobStatus; stage: string; progress: number; attemptCount: number; maxAttempts: number
  snapshotId: string; candidateModelId?: string; errorCode?: string; errorMessage?: string
  createdAt: string; startedAt?: string; finishedAt?: string; revision: number
}
export interface TrainingAttempt { id: string; attemptNo: number; status: string; stage: string; progress: number; workerId?: string; startedAt: string; heartbeatAt?: string; finishedAt?: string; errorCode?: string; errorMessage?: string; artifacts: Record<string, unknown> }
export interface TrainingSnapshotSummary { id: string; targetId: string; targetVersionId: string; inputSchemeId: string; eligibilityRunId: string; sampleCount: number; dataNature: 'REAL' | 'SYNTHETIC'; snapshotHash: string; businessFingerprint: string; frozenAt: string }
export interface TrainingJobDetail { job: TrainingJobSummary; snapshot: TrainingSnapshotSummary; attempts: TrainingAttempt[]; policy: Record<string, unknown>; frozenConfiguration: Record<string, unknown> }
export interface ModelVersion {
  id: string; targetId: string; targetName: string; category: string; valueType: ValueType; version: number
  status: ModelStatus; modelType: string; dataNature: 'REAL' | 'SYNTHETIC'; productionEligible: boolean
  comparisonStatus: 'BETTER' | 'WORSE' | 'EQUIVALENT' | 'NOT_COMPARABLE'; metrics: Record<string, unknown>
  source?: { origin?: string; datasetName?: string; xColumns?: Array<{code: string; name: string; unit?: string}>; yColumn?: {code: string; name: string; unit?: string} };
  trainableSamples: number; trainingJobId: string | null; createdAt: string; updatedAt: string; revision: number
}
export interface ModelRelease { id: string; action: string; modelVersionId: string; previousModelVersionId?: string; reason: string; createdAt: string }
export interface ModelDetail { model: ModelVersion; snapshot?: TrainingSnapshotSummary | null; applicabilityDomain: Record<string, unknown>; modelCard: Record<string, unknown>; rejectionReasons: string[]; releases: ModelRelease[]; testActivationAllowed?: boolean }
export interface ModelActionCommand extends VersionCommand { expectedActiveModelVersionId?: string; reason: string }
export interface ModelActionResult { modelVersionId: string; status: ModelStatus; activeModelVersionId?: string; revision: number; changedAt: string }

export interface FormulaComponent { materialId: string; ratio?: number; unit: string; amountKnown: boolean }
export interface Formula {
  basis: 'MASS_PERCENT' | 'MASS_PART' | 'ABSOLUTE_MASS'
  compositionComplete: boolean; components: FormulaComponent[]; recordedTotal?: number
}
export interface PredictionTarget { targetId: string; expectedModelVersionId?: string }
export interface PredictionRequest {
  formula?: Formula; inputs: Record<string, unknown>; targets: PredictionTarget[]
}
export interface Interval { lower: number; upper: number; coverageLevel: number }
export type TypedPrediction =
  | { resultType: 'CONTINUOUS'; value: number; unit?: string; interval?: Interval }
  | { resultType: 'ORDINAL'; label: string; probabilities: Record<string, number>; thresholdProbability?: number }
  | { resultType: 'BINARY'; label: string; positiveClass: string; probability: number; decisionThreshold: number }
  | { resultType: 'CATEGORICAL'; label: string; probabilities: Record<string, number> }
export interface TargetSuccess {
  targetId: string; status: 'SUCCEEDED'; prediction: TypedPrediction; modelVersionId: string
  dataNature?: 'REAL' | 'SYNTHETIC'; productionEligible?: boolean; simulation?: boolean
  targetVersionId: string; inputSchemeId: string; snapshotId: string
  applicability: { status: DomainStatus; distance: number; reasons: string[] }
  warnings: Array<{ code: string; message: string; detail: Record<string, unknown> }>
  quality: { level: 'HIGH' | 'MEDIUM' | 'LOW'; explanation: string; policyVersionId: string; evidenceCoverage: number }
  evidence: { modelVersionId: string; targetVersionId: string; inputSchemeId: string; snapshotId: string; domainPolicyVersionId: string; qualityPolicyVersionId: string }
}
export interface TargetBlocked {
  targetId: string; status: 'BLOCKED'; modelVersionId?: string; errorCode: string; message: string; detail?: ErrorDetail
  prediction?: never
}
export interface TargetFailed {
  targetId: string; status: 'FAILED'; modelVersionId?: string; errorCode: string; message: string; detail?: Record<string, unknown>
  prediction?: never
}
export interface PredictionResponse {
  requestId: string; predictionId: string; executionStatus: 'SUCCEEDED' | 'FAILED'
  simulation?: boolean; predictionMode?: 'FORMAL' | 'SIMULATION'
  outcomeStatus?: 'SUCCEEDED' | 'PARTIAL' | 'BLOCKED'
  results: Array<TargetSuccess | TargetBlocked | TargetFailed>
  warnings?: Array<{ code: string; message: string; detail: Record<string, unknown> }>
}
export interface PredictionRunning { predictionRecordId: string; requestId: string; executionStatus: 'RUNNING'; outcomeStatus?: never; pollAfterMs: number }
export interface PredictionCatalogTarget { modelSource?: { datasetName: string; xColumns: Array<{name: string}> }; targetId: string; code: string; name: string; category: string; valueType: ValueType; targetStatus: string; formalPredictionAvailable: boolean; simulationPredictionAvailable?: boolean; predictionAvailable?: boolean; unavailableReasons: string[]; registered?: boolean; statusLabel?: string; unit?: string; testMethod?: string; testStage?: string }
export interface PredictionContextTarget { targetId: string; name: string; category: string; valueType: ValueType; available: boolean; simulation?: boolean; modelVersionId?: string; unavailableReasons: string[]; formulaRequirement: 'REQUIRED' | 'NOT_USED'; fixedConditions?: Record<string, unknown>; evidence: Record<string, string> }
export interface PredictionInputField {
  supportedRanges?: Array<{ targetId: string; targetName: string; minimum: number; maximum: number }>
  fieldVersionId: string; code: string; name: string; valueType: 'NUMBER' | 'STRING' | 'BOOLEAN' | 'CATEGORY' | 'COMPOSITION'; unit?: string; required: boolean;
  encoding?: Record<string, unknown>; allowedValues?: Array<string | number>; sourceLabel?: string; inputGroup?: 'FORMULA' | 'PROCESS' | 'CONDITION' | 'OTHER';
  fixedByTargetDefinition?: boolean; requiredByTargets?: Array<{ targetId: string; targetName: string }>
}
export interface PredictionMaterial { materialId: string; code: string; name?: string; category?: string; role: string; encoderIndex: number }
export interface PredictionContext { targets: PredictionCatalogTarget[]; selected: PredictionContextTarget[]; requiredInputs: PredictionInputField[]; materials: PredictionMaterial[]; simulationAuthorized?: boolean; configurationConflicts?: Array<{ fieldCode: string; definitions: string[] }> }
