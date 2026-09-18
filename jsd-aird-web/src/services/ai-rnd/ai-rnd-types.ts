/** Frozen ai-rnd.v1 transport types. R01 intentionally exports no HTTP client. */
export const AI_RND_CONTRACT_VERSION = 'ai-rnd.v1' as const

export type ValueType = 'CONTINUOUS' | 'ORDINAL' | 'BINARY' | 'CATEGORICAL'
export type EligibilityState = 'TRAINABLE' | 'EXCLUDED' | 'REVIEW_REQUIRED'
export type DomainStatus = 'IN_DOMAIN' | 'NEAR_BOUNDARY' | 'OUT_OF_DOMAIN'
export type ModelStatus = 'CANDIDATE' | 'ACTIVE' | 'PAUSED' | 'RETIRED' | 'REJECTED'

export interface Page<T> { items: T[]; total: number; page: number; size: number; totalPages?: number }
export interface ErrorDetail { targetId?: string; fieldCode?: string; missingFields?: string[]; evidence?: unknown }
export interface ErrorResponse { code: string; message: string; requestId: string; detail?: ErrorDetail }
export interface VersionCommand { expectedRevision: number }
export interface ReasonedVersionCommand extends VersionCommand { reason: string }

export interface PredictionTargetSummary {
  id: string; code: string; name: string; category: string; valueType: ValueType
  status: 'DRAFT' | 'ACTIVE' | 'PAUSED' | 'RETIRED'; currentVersionId?: string
  currentInputSchemeId?: string; activeModelVersionId?: string; revision: number; updatedAt: string
  currentVersion?: number; currentInputSchemeName?: string
  evaluationStatus: 'NOT_EVALUATED' | 'RUNNING' | 'COMPLETED' | 'FAILED'; trainingStatus: 'NOT_TRAINED' | 'ACTIVE'
}
export interface TargetCommand {
  code: string; name: string; category: string; valueType: ValueType; unit?: string; classes: string[]
  definition: Record<string, unknown>; observationSemantics: Record<string, unknown>; expectedRevision?: number
  resultStandardFieldCode?: string; catalogProposalId?: string
}
export interface TargetVersion {
  id: string; targetId: string; version: number; status: 'DRAFT' | 'PUBLISHED' | 'RETIRED'
  valueType: ValueType
  unit?: string; classes: string[]; definition: Record<string, unknown>
  observationSemantics: Record<string, unknown>; configHash: string; publishedAt?: string
  resultStandardFieldDictionaryId?: string; catalogProposalId?: string
}
export interface SourceMappingCommand {
  targetVersionId: string; sourceType: 'DATA_CENTER' | 'EXPERIMENT'
  mapping: Record<string, unknown>; expectedRevision: number
}
export interface InputFieldSummary {
  id: string; code: string; name: string; valueType: InputValueType; unit?: string
  availabilityStage: AvailabilityStage; status: 'DRAFT' | 'ACTIVE' | 'PAUSED' | 'RETIRED'
  currentVersionId?: string; currentVersion?: number; standardFieldCode?: string; standardFieldName?: string; standardFieldUnit?: string
  revision: number; updatedAt: string
}
export type InputValueType = 'NUMBER' | 'STRING' | 'BOOLEAN' | 'CATEGORY' | 'COMPOSITION'
export type AvailabilityStage = 'PRE_EXPERIMENT' | 'POST_EXPERIMENT'
export interface InputFieldCommand {
  code: string; name: string; valueType: InputValueType; unit?: string
  availabilityStage: AvailabilityStage; standardFieldDictionaryId?: string
  definition: Record<string, unknown>; preprocessing: Record<string, unknown>; expectedRevision?: number
}
export interface InputFieldVersion {
  id: string; inputFieldId: string; version: number; status: 'DRAFT' | 'PUBLISHED' | 'RETIRED'
  valueType: InputValueType; unit?: string; availabilityStage: AvailabilityStage
  standardFieldDictionaryId?: string; standardFieldCode?: string; standardFieldName?: string; standardFieldUnit?: string
  definition: Record<string, unknown>; preprocessing: Record<string, unknown>; configHash: string
}
export interface InputSchemeField {
  inputFieldVersionId: string; required: boolean; ordinal: number; override: Record<string, unknown>
  fieldCode?: string; fieldName?: string; valueType?: InputValueType; unit?: string; availabilityStage?: AvailabilityStage
}
export interface InputScheme {
  id: string; targetId: string; targetVersionId: string; materialDictionaryVersionId?: string; materialDictionaryCode?: string
  code: string; name: string; version: number
  status: 'DRAFT' | 'FROZEN' | 'RETIRED'; fields: InputSchemeField[]
  configHash: string; revision: number; frozenAt?: string
}
export interface InputSchemeCommand {
  targetVersionId: string; materialDictionaryVersionId?: string; code: string; name: string; fields: InputSchemeField[]
  preprocessing: Record<string, unknown>; expectedRevision: number
}
export interface CoveragePreview {
  evaluationStatus: 'NOT_EVALUATED' | 'EVALUATED'; unavailableReason?: 'DATA_PIPELINE_NOT_READY'
  validationIssues: Array<{ code: string; fieldCode?: string; message: string }>; schemaHash: string
  candidateSamples?: number; trainable?: number; excluded?: number; reviewRequired?: number
  changedSampleIds: string[]
}
export interface FreezeResult {
  id: string; status: 'FROZEN'; recomputeStatus: 'DEFERRED' | 'QUEUED'
  currentInputSchemeId: string; targetRevision: number
}
export interface SourceMappingVersion {
  id: string; targetId: string; targetVersionId: string; sourceType: 'DATA_CENTER' | 'EXPERIMENT'; version: number
  status: 'DRAFT' | 'PUBLISHED' | 'RETIRED'; mapping: Record<string, unknown>; mappingHash: string
}
export interface SourceSamplePreview { sourceType: 'DATA_CENTER'|'EXPERIMENT'; sampleName: string; value: string; location: string }
export interface SourceMappingSuggestion {
  candidateKey: string; fieldName: string; unit?: string; testMethod?: string; load?: string; substrate?: string; stage?: string
  dataCenterCount: number; experimentCount: number; confidence: number; confirmationRequired: boolean; samples: SourceSamplePreview[]
}
export interface SourceMappingSuggestions {
  targetId: string; targetVersionId: string; dataCenterCount: number; experimentCount: number; pendingConfirmationCount: number; suggestions: SourceMappingSuggestion[]
}
export interface ConfirmSourceMappingResult { mappings: SourceMappingVersion[]; recomputeStatus: string; recomputeRunId?: string }

export interface TrainingPolicy {
  id: string; targetId: string; version: number; status: 'DRAFT' | 'PUBLISHED' | 'RETIRED'
  qualification: Record<string, unknown>; validation: Record<string, unknown>; training: Record<string, unknown>
  policyHash: string; publishedAt?: string; createdAt: string
}
export interface TrainingPolicyCommand {
  targetId: string; qualification: Record<string, unknown>; validation: Record<string, unknown>
  training: Record<string, unknown>; expectedRevision: number
}
export interface StructuredTrainingPolicy {
  minimumTrainableSamples: number; minimumIndependentLineages: number; minimumSourceGroups: number
  minimumPerClass?: number; foldCount: number; primaryMetric: string; metricThreshold: number
  requireFairComparison: boolean; autoTrainingEnabled: boolean; dataNature: 'REAL' | 'SYNTHETIC'
  retrainMinimumNewSamples: number; minimumIntervalHours: number
  replicateHandling: 'KEEP_GROUPED' | 'MEAN' | 'MEDIAN' | 'MEDIAN_GRADE' | 'MAJORITY' | 'CONSENSUS_ONLY'
  candidateAlgorithms: string[]; seed: number; timeoutMinutes: number
}
export interface StandardFieldReference {
  id: string; code: string; version: number; name: string; valueType: string; unit?: string; groupCode?: string
}
export interface MaterialReference { id: string; code: string; name: string; category: string; status: string }
export interface MaterialAlias {
  id: string; materialId: string; materialCode: string; materialName: string; alias: string
  normalizedAlias: string; status: 'ACTIVE' | 'RETIRED'; revision: number; updatedAt: string
}
export interface MaterialDictionaryItem { materialId: string; role: string; ordinal: number; token: string }
export interface MaterialDictionary {
  id: string; code: string; version: number; status: 'DRAFT' | 'FROZEN' | 'RETIRED'
  items: MaterialDictionaryItem[]; encoder: Record<string, unknown>; dictionaryHash: string
  revision: number; frozenAt?: string; createdAt: string
}
export interface TrainingSampleSummary {
  id: string; logicalSampleKey: string; identityVersion: number; authoritySourceType?: 'DATA_CENTER' | 'EXPERIMENT'
  status: 'ACTIVE' | 'SUSPENDED' | 'INVALIDATED'; takeoverExperimentId?: string
  takeoverExperimentVersionId?: string; revision: number; sourceCount: number; updatedAt: string
}
export interface TrainingSampleSource {
  id: string; sourceType: 'DATA_CENTER' | 'EXPERIMENT'; sourceBusinessKey: string
  sampleBoundaryId: string; logicalSampleKey: string; sourceGroupKeys: string[]
  sourceVersion: string; sourceSequence: number; contentHash: string
  status: 'CURRENT' | 'SUPERSEDED' | 'TAKEN_OVER' | 'INVALIDATED'
  confirmedSubmissionItemId?: string; experimentVersionId?: string; recognitionJobId?: string
  experimentId?: string; revisionId?: string
  composition?: Record<string, unknown>; process?: Record<string, unknown>; conditions?: Record<string, unknown>
  observations?: Record<string, unknown>; facts?: Record<string, unknown>; permissionScope?: Record<string, unknown>
  sourceCoordinates?: Record<string, unknown>; factHash?: string; createdAt: string; redacted: boolean
}
export interface SampleIdentityIssue {
  id: string; issueType: string; status: 'OPEN' | 'CONFIRMED_SAME' | 'CONFIRMED_DISTINCT' | 'DISMISSED'
  evidence: Record<string, unknown>; resolution?: Record<string, unknown>; revision: number
  createdAt: string; resolvedAt?: string
}
export interface TrainingSampleDetail {
  summary: TrainingSampleSummary; sources: TrainingSampleSource[]; identityIssues: SampleIdentityIssue[]
}

export interface QualificationReason { code: string; message: string; fieldCode?: string; evidence?: unknown }
export interface Eligibility {
  id: string; evaluationRunId?: string; sampleRevisionId: string; targetVersionId: string; inputSchemeId: string
  state: EligibilityState; reasons: QualificationReason[]; warnings: QualificationReason[]
  primaryReasonCode?: string; evidence?: Record<string, unknown>; revision: number; evaluatedAt: string
}
export interface FunnelStep { code: string; label: string; count: number; denominator: number }
export interface FieldCoverage { fieldCode: string; fieldName: string; available: number; denominator: number; ratio: number; reasonCodes: string[] }
export interface EligibilityEvaluationRun {
  id: string; targetId: string; targetVersionId: string; inputSchemeId: string; modelingPolicyVersionId?: string
  ruleFingerprint: string; asyncJobId?: string; status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  totalSamples?: number; trainable?: number; excluded?: number; reviewRequired?: number; funnel: FunnelStep[]
  errorCode?: string; errorMessage?: string; createdAt?: string; startedAt?: string; finishedAt?: string
}
export interface EligibilitySummary {
  evaluationStatus: 'NOT_EVALUATED' | 'RUNNING' | 'COMPLETED' | 'FAILED'; unavailableReason?: string
  latestRun?: EligibilityEvaluationRun; total?: number; trainable?: number; excluded?: number; reviewRequired?: number
  reasonCounts: Record<string, number>; funnel: FunnelStep[]; fieldCoverage: FieldCoverage[]; evaluatedAt?: string
}
export type EligibilityPage = Page<Eligibility & { logicalSampleKey?: string; authoritySourceType?: string }>
export interface EligibilityReview { id: string; reviewType: string; reasonCode?: string; status: string; priority: number; evidence: Record<string, unknown>; revision: number; createdAt: string; updatedAt: string }
export interface EligibilityDetail { eligibility: Eligibility; sample: Record<string, unknown>; reviews: EligibilityReview[] }
export interface ReviewDecisionCommand extends VersionCommand { decision: 'KEEP' | 'EXCLUDE' | 'MERGE' | 'KEEP_SEPARATE' | 'REMAP'; reason: string; materialId?: string }
export interface ReviewDecision { reviewId: string; decision: string; reason: string; recomputeRunId?: string; revision: number; decidedAt: string }
export interface RecomputeAccepted { runId: string; asyncJobId: string; targetId: string; targetVersionId: string; inputSchemeId: string; status: EligibilityEvaluationRun['status']; ruleFingerprint: string }

export interface TrainingSettings { autoLearningEnabled: boolean; lastEvaluatedAt?: string; revision: number; updatedAt?: string }
export interface TargetTrainingEvaluation { targetId: string; targetName: string; result: 'QUEUED' | 'BLOCKED' | 'EXISTING'; code?: string; trainingJobId?: string; snapshotId?: string }
export interface TrainingEvaluationResult { targetsChecked: number; jobsCreated: number; blocked: number; targets: TargetTrainingEvaluation[] }
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
  trainableSamples: number; trainingJobId: string; createdAt: string; updatedAt: string; revision: number
}
export interface ModelRelease { id: string; action: string; modelVersionId: string; previousModelVersionId?: string; reason: string; createdAt: string }
export interface ModelDetail { model: ModelVersion; snapshot: TrainingSnapshotSummary; applicabilityDomain: Record<string, unknown>; modelCard: Record<string, unknown>; rejectionReasons: string[]; releases: ModelRelease[] }
export interface ModelComparison { candidateId: string; activeModelId?: string; status: string; sameComparisonSet: boolean; candidateMetrics: Record<string, unknown>; activeMetrics: Record<string, unknown>; baselineMetrics: Record<string, unknown>; reasons: string[] }
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
  outcomeStatus?: 'SUCCEEDED' | 'PARTIAL' | 'BLOCKED'
  results: Array<TargetSuccess | TargetBlocked | TargetFailed>
  warnings?: Array<{ code: string; message: string; detail: Record<string, unknown> }>
}
export interface PredictionRunning { predictionRecordId: string; requestId: string; executionStatus: 'RUNNING'; outcomeStatus?: never; pollAfterMs: number }
export interface PredictionCatalogTarget { targetId: string; code: string; name: string; category: string; valueType: ValueType; targetStatus: string; formalPredictionAvailable: boolean; unavailableReasons: string[] }
export interface PredictionContextTarget { targetId: string; name: string; category: string; valueType: ValueType; available: boolean; modelVersionId?: string; unavailableReasons: string[]; formulaRequirement: 'REQUIRED' | 'NOT_USED'; evidence: Record<string, string> }
export interface PredictionInputField { fieldVersionId: string; code: string; name: string; valueType: 'NUMBER' | 'STRING' | 'BOOLEAN' | 'CATEGORY' | 'COMPOSITION'; unit?: string; required: boolean; encoding?: Record<string, unknown> }
export interface PredictionMaterial { materialId: string; code: string; name?: string; category?: string; role: string; encoderIndex: number }
export interface PredictionContext { targets: PredictionCatalogTarget[]; selected: PredictionContextTarget[]; requiredInputs: PredictionInputField[]; materials: PredictionMaterial[]; configurationConflicts?: Array<{ fieldCode: string; definitions: string[] }> }
export interface QualityRule { code: string; priority: number; when: Record<string, unknown>; trustLevel: 'HIGH' | 'MEDIUM' | 'LOW'; explanation: string }
export interface QualityPolicy { id: string; targetId: string; version: number; status: 'DRAFT' | 'PUBLISHED' | 'RETIRED'; kind: 'QUALITY'; defaultTrustLevel: 'HIGH' | 'MEDIUM' | 'LOW'; defaultExplanation: string; rules: QualityRule[]; policyHash: string; revision: number; publishedAt?: string; createdAt: string }

export interface TypedGoal {
  targetId: string; expectedModelVersionId?: string; operator: string; typedValue: unknown
  mandatory: boolean; weight: number; minimumProbability?: number
}
export interface SearchSpace {
  allowedMaterialIds: string[]; variableMaterialIds: string[]
  bounds: Record<string, unknown>; variableInputs: Record<string, unknown>
}
export interface FormulaDesignRequest {
  typedGoals: TypedGoal[]; constraints: Record<string, unknown>
  fixedInputs: Record<string, unknown>; searchSpace: SearchSpace; candidateCount?: number
}
export interface OptimizationRequest extends Omit<FormulaDesignRequest, 'candidateCount'> {
  baselineRef: { type: 'EXPERIMENT' | 'CANDIDATE' | 'DATA_CENTER'; id: string; versionId: string }
}
export interface RunAccepted { runId: string; status: 'QUEUED'; modelBindings: Record<string, string> }
export interface Candidate {
  id: string; candidateNo: number; title: string; formula: Formula
  targetResults: Record<string, TypedPrediction>; ruleCheck: Record<string, unknown>
  applicability: Record<string, unknown>; score?: number; warnings: QualificationReason[]
}
export interface ResearchRun {
  id: string; runType: 'FORMULA_PREDICTION' | 'EXPERIMENT_OPTIMIZATION'
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'PARTIAL' | 'BLOCKED' | 'FAILED' | 'CANCELLED'
  candidates: Candidate[]; modelBindings: Record<string, string>; error?: ErrorResponse; updatedAt: string
}
