import type { ApiResponse } from '@/types/api';
import { httpClient } from '@/services/http/client';
import { fetchFileBlob } from '@/services/files';

export type SourceOwner = 'DATA_CENTER' | 'EXPERIMENT';
export type RecognitionStatus = 'QUEUED' | 'PARSING' | 'WAITING_MAPPING' | 'COMMITTING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

export interface SourceCoordinate {
  kind: string;
  order?: number;
  sheetId?: string;
  address?: string;
  pageNo?: number;
  [key: string]: unknown;
}

export interface RecognitionCandidate {
  candidateId: string;
  category: 'BASIC' | 'FORMULA' | 'PROCESS' | 'TEST' | 'OTHER';
  fieldCode: string;
  labelPath?: string;
  rawText: string;
  rawUnit?: string;
  parsedValue?: unknown;
  correctedValue?: unknown;
  standardUnit?: string;
  sourceCoordinate: SourceCoordinate;
  confidence: number;
  parserVersion: string;
  status: 'SUGGESTED' | 'REVIEW_REQUIRED' | 'CONFIRMED' | 'CORRECTED' | 'IGNORED';
  experimentBoundaryId: string;
  sampleBoundaryId: string;
  sourceGroupKey: string;
  observationId?: string;
  replicateGroupKey?: string;
  measurementIndex?: number;
}

export interface RecognitionFragment {
  fragmentId: string;
  rawText: string;
  confidence: number;
  sourceCoordinate: SourceCoordinate;
}

export interface RecognitionIssue {
  issueId: string;
  severity: 'INFO' | 'WARNING' | 'BLOCKER';
  type: string;
  message: string;
}

export interface SampleBoundary {
  sampleBoundaryId: string;
  logicalSampleKey: string;
  sourceGroupKeys: string[];
  title: string;
}

export interface ExperimentBoundary {
  experimentBoundaryId: string;
  title: string;
  confirmed: boolean;
  sourceCoordinates: SourceCoordinate;
  samples: SampleBoundary[];
  sharedConditions: unknown[];
}

export interface RecognitionWorkspace {
  schemaVersion: number;
  sourceOwner: SourceOwner;
  recognitionMode: 'FREEFORM' | 'TEMPLATE_GUIDED';
  sourceFileId: string;
  sourceFileName: string;
  sourceFileSha256: string;
  sourceFormat: string;
  parserVersion?: string;
  documentSnapshot?: Record<string, unknown>;
  structureSummary?: Record<string, unknown>;
  experiments?: ExperimentBoundary[];
  candidates?: RecognitionCandidate[];
  unrecognizedFragments?: RecognitionFragment[];
  issues?: RecognitionIssue[];
  projectId?: string;
  stageId?: string;
  taskId?: string;
  experimentDate?: string;
  ownerName?: string;
}

export interface RecognitionJob {
  id: string;
  organizationId: string;
  sourceFileId: string;
  sourceFileName: string;
  sourceSha256: string;
  sourceFormat: string;
  sourceOwner: SourceOwner;
  recognitionMode: 'FREEFORM' | 'TEMPLATE_GUIDED';
  templateVersionId?: string;
  categoryId?: string;
  importPurpose: 'DATA_ONLY' | 'EXPERIMENT_DRAFT';
  targetExperimentCategoryId?: string;
  visibility: 'ALL' | 'QUALITY' | 'PROJECT';
  status: RecognitionStatus;
  progress: number;
  currentStage?: string;
  parserVersion?: string;
  workspace: RecognitionWorkspace;
  boundaries: ExperimentBoundary[];
  recognitionRevision: number;
  recognitionProfileId?: string;
  sourceSequence: number;
  finalizedAt?: string;
  errorMessage?: string;
  createdAt: string;
  updatedAt: string;
}

export interface FinalizeResult {
  recognitionJobId: string;
  confirmedSubmissionId?: string;
  submissionRevision?: number;
  drafts: Array<{ experimentId: string; experimentVersionId: string; experimentNo: string; title: string; experimentBoundaryId: string; logicalSampleKeys: string[] }>;
}

function base(owner: SourceOwner) {
  return owner === 'EXPERIMENT' ? '/api/v1/experiment-imports' : '/api/v1/data/recognition-jobs';
}

export const sourceRecognitionApi = {
  async get(owner: SourceOwner, id: string) {
    const response = await httpClient.get<ApiResponse<RecognitionJob>>(`${base(owner)}/${id}/workspace`);
    return response.data.data;
  },
  async updateBoundaries(owner: SourceOwner, id: string, expectedRevision: number, items: ExperimentBoundary[]) {
    const response = await httpClient.put<ApiResponse<RecognitionJob>>(`${base(owner)}/${id}/boundaries`, { expectedRevision, items });
    return response.data.data;
  },
  async updateMappings(owner: SourceOwner, id: string, expectedRevision: number, input: {
    candidates: RecognitionCandidate[]; unrecognizedFragments: RecognitionFragment[]; issues: RecognitionIssue[];
  }) {
    const response = await httpClient.put<ApiResponse<RecognitionJob>>(`${base(owner)}/${id}/mappings`, { expectedRevision, ...input });
    return response.data.data;
  },
  async retry(owner: SourceOwner, id: string) {
    const response = await httpClient.post<ApiResponse<RecognitionJob>>(`${base(owner)}/${id}/retry`);
    return response.data.data;
  },
  async saveProfile(owner: SourceOwner, id: string, name: string, expectedRevision: number) {
    const response = await httpClient.post<ApiResponse<{ id: string; name: string }>>(`${base(owner)}/${id}/recognition-profiles`, { name, expectedRevision });
    return response.data.data;
  },
  async finalize(owner: SourceOwner, id: string, expectedRevision: number) {
    const response = await httpClient.post<ApiResponse<FinalizeResult>>(`${base(owner)}/${id}/finalize`, { expectedRevision });
    return response.data.data;
  },
  sourceBlob(fileId: string) { return fetchFileBlob(fileId); },
};
