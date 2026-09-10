import { httpClient } from '@/services/http/client';
import type { ApiResponse } from '@/types/api';

export type ResearchRunStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'PARTIAL' | 'FAILED' | 'CANCELLED';

export interface TargetReadiness {
  targetKey: string;
  name: string;
  valueType: string;
  unit?: string;
  direction: string;
  eligibleCaseCount: number;
  status: string;
  message: string;
  ordinalLabels?: string[];
  positiveClass?: string;
  decisionThreshold?: number;
}

export interface BaselineOption {
  analysisRowId: string;
  experimentId: string;
  experimentVersionId: string;
  experimentNo: string;
  sourceIdentity?: string;
  experimentDate?: string;
  title: string;
  formulaSummary: string;
}

export interface MaterialOption {
  materialCode: string;
  role: string;
  modelAllowed: boolean;
}

export interface FormulationReadiness {
  taskProfileCode: string;
  analysisProfileVersion: string;
  mode: 'CASE_STAT_RULE' | 'MODEL' | 'HYBRID';
  targets: TargetReadiness[];
  baselines: BaselineOption[];
  materials: MaterialOption[];
}

export interface ResearchGoal {
  targetKey: string;
  mode: string;
  mandatory: boolean;
  weight: number;
  value?: number;
  minimum?: number;
  maximum?: number;
  tolerance?: number;
  minimumProbability?: number;
}

export interface ParsedResearchRequest {
  originalText: string;
  goals: ResearchGoal[];
  warnings: string[];
  draft: ResearchDraft;
  unresolvedFields: UnresolvedResearchField[];
  confirmationStatus: ResearchConfirmationStatus;
  interpretationMode?: 'LLM_ASSISTED' | 'DETERMINISTIC_FALLBACK';
  interpretationModel?: string;
  interpretationPromptVersion?: string;
  fallbackReason?: string;
}

export type ResearchConfirmationStatus = 'NEEDS_INPUT' | 'NEEDS_CONFIRMATION' | 'READY' | 'CONFLICT';

export interface UnresolvedResearchField {
  field: string;
  code: string;
  message: string;
}

export interface ResearchDraft {
  goals: ResearchGoal[];
  context: Record<string, unknown>;
  constraints: ResearchConstraints;
  candidateCount: number;
  baselineAnalysisRowId?: string;
}

export interface ParseResearchRequest {
  text: string;
  runType: 'FORMULA_PREDICTION' | 'EXPERIMENT_OPTIMIZATION';
  currentDraft: ResearchDraft;
}

export interface ResearchConstraints {
  requiredMaterials: string[];
  forbiddenMaterials: string[];
  fixedMaterials: Record<string, number>;
  materialRanges: Record<string, { minimum?: number; maximum?: number }>;
  forbiddenMaterialCombinations: string[][];
  processRanges: Record<string, { minimum?: number; maximum?: number }>;
  maxMaterialCount?: number;
  requireCostCheck: boolean;
  requireInventoryCheck: boolean;
}

export interface ResearchRequest {
  taskProfileCode: string;
  idempotencyKey: string;
  projectId?: string;
  categoryId?: string;
  baselineAnalysisRowId?: string;
  goals: ResearchGoal[];
  context: Record<string, unknown>;
  constraints: ResearchConstraints;
  candidateCount: number;
}

export interface FormulaComponent {
  materialCode?: string;
  materialName?: string;
  ratioPercent?: number;
  rawRatio?: number;
  [key: string]: unknown;
}

export interface TargetStatistics {
  targetKey: string;
  name: string;
  valueType: string;
  unit?: string;
  caseCount: number;
  exactCount: number;
  lowerBoundCount: number;
  statisticsLevel: 'NONE' | 'CASES_ONLY' | 'SIMPLE_RANGE' | 'FULL_STATISTICS';
  confidence: 'NONE' | 'LOW' | 'MEDIUM' | 'MODEL';
  minimum?: number;
  maximum?: number;
  pointEstimate?: number | string;
  q25?: number;
  q75?: number;
  positiveRate?: number;
  distribution?: Record<string, number>;
  lowerBoundEvidence?: Array<{ analysisRowId: string; rawValue: string; minimum?: number }>;
  cases?: SimilarCaseEvidence[];
  predictionSource?: 'MODEL' | 'T06_SIMILAR_CASE';
  modelVersionId?: string;
  predictedClass?: string;
  probability?: number;
  classProbabilities?: Record<string, number>;
  lowerClass90?: string;
  upperClass90?: string;
  applicabilityDomain?: { status?: string; reasons?: string[] };
}

export interface SimilarCaseEvidence {
  analysisRowId: string;
  experimentId: string;
  experimentNo: string;
  title: string;
  sourceIdentity?: string;
  similarity: number;
  rawValue?: string;
  numericValue?: number;
  ordinalValue?: string;
  observationType: string;
  unit?: string;
  experimentDate?: string;
}

export interface ResearchCandidate {
  id: string;
  candidateNo: number;
  strategy: string;
  title: string;
  formula: FormulaComponent[];
  process: Record<string, unknown>;
  modelContext: Record<string, unknown>;
  estimates: Record<string, TargetStatistics>;
  ruleCheck: Record<string, unknown>;
  evidence: Record<string, unknown>;
  confidence: 'NONE' | 'LOW' | 'MEDIUM';
  score: number;
  contentHash: string;
}

export interface ResearchRun {
  id: string;
  runType: 'FORMULA_PREDICTION' | 'EXPERIMENT_OPTIMIZATION';
  mode: 'CASE_STAT_RULE' | 'MODEL' | 'HYBRID';
  status: ResearchRunStatus;
  taskProfileCode: string;
  analysisProfileVersion?: string;
  request: ResearchRequest;
  result?: {
    overallConfidence?: 'NONE' | 'LOW' | 'MEDIUM';
    targetStatistics?: TargetStatistics[];
    warnings?: string[];
    reason?: string;
    [key: string]: unknown;
  };
  errorCode?: string;
  errorMessage?: string;
  candidates: ResearchCandidate[];
  createdAt: string;
  startedAt?: string;
  finishedAt?: string;
}

export interface ExperimentDraftRequest {
  candidateIds: string[];
  categoryId: string;
  projectId?: string;
  stageId?: string;
  taskId?: string;
  ownerName?: string;
  plannedExperimentDate: string;
  idempotencyKey: string;
}

export interface CreatedExperimentDraft {
  candidateId: string;
  experimentId: string;
  experimentVersionId: string;
  experimentNo: string;
  title: string;
}

const data = <T>(response: { data: ApiResponse<T> }) => response.data.data;

export const formulaResearchApi = {
  async readiness(params?: { projectId?: string; categoryId?: string }) {
    return data(await httpClient.get<ApiResponse<FormulationReadiness>>('/api/v1/ai/formulation-readiness', { params }));
  },
  async parse(request: ParseResearchRequest) {
    return data(await httpClient.post<ApiResponse<ParsedResearchRequest>>('/api/v1/ai/research-requests/parse', request));
  },
  async submitFormulaPrediction(request: ResearchRequest) {
    return data(await httpClient.post<ApiResponse<{ runId: string; status: ResearchRunStatus }>>('/api/v1/ai/formula-predictions', request));
  },
  async submitExperimentOptimization(request: ResearchRequest) {
    return data(await httpClient.post<ApiResponse<{ runId: string; status: ResearchRunStatus }>>('/api/v1/ai/experiment-optimizations', request));
  },
  async run(runId: string) {
    return data(await httpClient.get<ApiResponse<ResearchRun>>(`/api/v1/ai/research-runs/${runId}`));
  },
  async createExperimentDrafts(runId: string, request: ExperimentDraftRequest) {
    return data(await httpClient.post<ApiResponse<CreatedExperimentDraft[]>>(`/api/v1/ai/research-runs/${runId}/experiment-drafts`, request));
  },
};
