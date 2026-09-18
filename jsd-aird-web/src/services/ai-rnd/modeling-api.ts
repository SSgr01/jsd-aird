import { httpClient } from '@/services/http/client'
import { generateUUID } from '@/utils/uuid'
import type {
  CoveragePreview, FreezeResult, InputFieldCommand, InputFieldSummary,
  InputFieldVersion, InputScheme, InputSchemeCommand, MaterialAlias, MaterialDictionary,
  MaterialDictionaryItem, MaterialReference, Page, PredictionTargetSummary, SourceMappingCommand,
  SourceMappingVersion, SourceMappingSuggestions, ConfirmSourceMappingResult, StandardFieldReference, TargetCommand, TargetVersion, TrainingPolicy,
  TrainingPolicyCommand,
  TrainingSampleDetail, TrainingSampleSummary,
  EligibilitySummary, EligibilityPage, EligibilityDetail, ReviewDecisionCommand, ReviewDecision, RecomputeAccepted,
} from './ai-rnd-types'

interface ApiResponse<T> { data: T }
const root = '/api/v1/ai/rnd'
const key = () => ({ headers: { 'Idempotency-Key': generateUUID() } })
const data = <T>(response: { data: ApiResponse<T> }) => response.data.data

export const modelingApi = {
  async targets(params: { keyword?: string; status?: string; valueType?: string; category?: string; page?: number; size?: number } = {}) {
    return data(await httpClient.get<ApiResponse<Page<PredictionTargetSummary>>>(`${root}/targets`, { params }))
  },
  async target(id: string) { return data(await httpClient.get<ApiResponse<PredictionTargetSummary>>(`${root}/targets/${id}`)) },
  async targetVersions(id: string) { return data(await httpClient.get<ApiResponse<TargetVersion[]>>(`${root}/targets/${id}/versions`)) },
  async createTarget(input: TargetCommand) { return data(await httpClient.post<ApiResponse<PredictionTargetSummary>>(`${root}/targets`, input, key())) },
  async createTargetVersion(id: string, input: TargetCommand) { return data(await httpClient.post<ApiResponse<TargetVersion>>(`${root}/targets/${id}/versions`, input, key())) },
  async updateTargetVersion(id: string, input: TargetCommand) { return data(await httpClient.patch<ApiResponse<TargetVersion>>(`${root}/target-versions/${id}`, input, key())) },
  async publishTargetVersion(id: string, expectedRevision: number) { return data(await httpClient.post<ApiResponse<PredictionTargetSummary>>(`${root}/target-versions/${id}/publish`, { expectedRevision }, key())) },
  async inputFields(params: { keyword?: string; status?: string; valueType?: string; page?: number; size?: number } = {}) {
    return data(await httpClient.get<ApiResponse<Page<InputFieldSummary>>>(`${root}/input-fields`, { params }))
  },
  async inputFieldVersions(id: string) { return data(await httpClient.get<ApiResponse<InputFieldVersion[]>>(`${root}/input-fields/${id}/versions`)) },
  async createInputField(input: InputFieldCommand) { return data(await httpClient.post<ApiResponse<InputFieldSummary>>(`${root}/input-fields`, input, key())) },
  async createInputFieldVersion(id: string, input: InputFieldCommand) { return data(await httpClient.post<ApiResponse<InputFieldVersion>>(`${root}/input-fields/${id}/versions`, input, key())) },
  async publishInputFieldVersion(id: string, expectedRevision: number) { return data(await httpClient.post<ApiResponse<InputFieldSummary>>(`${root}/input-field-versions/${id}/publish`, { expectedRevision }, key())) },

  async sourceMappings(targetId: string) { return data(await httpClient.get<ApiResponse<SourceMappingVersion[]>>(`${root}/targets/${targetId}/source-mappings`)) },
  async sourceMappingSuggestions(targetId: string, targetVersionId?: string) { return data(await httpClient.get<ApiResponse<SourceMappingSuggestions>>(`${root}/targets/${targetId}/source-mapping-suggestions`, { params: targetVersionId ? { targetVersionId } : undefined })) },
  async confirmSourceMapping(targetId: string, input: { targetVersionId: string; candidateKey: string; expectedRevision: number }) { return data(await httpClient.post<ApiResponse<ConfirmSourceMappingResult>>(`${root}/targets/${targetId}/source-mappings/confirm`, input, key())) },
  async createSourceMapping(targetId: string, input: SourceMappingCommand) { return data(await httpClient.post<ApiResponse<SourceMappingVersion>>(`${root}/targets/${targetId}/source-mappings`, input, key())) },
  async publishSourceMapping(id: string, expectedRevision: number) { return data(await httpClient.post<ApiResponse<SourceMappingVersion>>(`${root}/source-mappings/${id}/publish`, { expectedRevision }, key())) },
  async inputSchemes(targetId: string) { return data(await httpClient.get<ApiResponse<InputScheme[]>>(`${root}/targets/${targetId}/input-schemes`)) },
  async createInputScheme(targetId: string, input: InputSchemeCommand) { return data(await httpClient.post<ApiResponse<InputScheme>>(`${root}/targets/${targetId}/input-schemes`, input, key())) },
  async previewInputScheme(id: string) { return data(await httpClient.post<ApiResponse<CoveragePreview>>(`${root}/input-schemes/${id}/preview`, undefined, key())) },
  async freezeInputScheme(id: string, expectedRevision: number) { return data(await httpClient.post<ApiResponse<FreezeResult>>(`${root}/input-schemes/${id}/freeze`, { expectedRevision }, key())) },

  async trainingPolicies(targetId?: string) { return data(await httpClient.get<ApiResponse<TrainingPolicy[]>>(`${root}/training-policies`, { params: targetId ? { targetId } : undefined })) },
  async createTrainingPolicy(input: TrainingPolicyCommand) { return data(await httpClient.post<ApiResponse<TrainingPolicy>>(`${root}/training-policies`, input, key())) },
  async publishTrainingPolicy(id: string, expectedRevision: number) { return data(await httpClient.post<ApiResponse<TrainingPolicy>>(`${root}/training-policies/${id}/publish`, { expectedRevision }, key())) },

  async standardFields(keyword?: string) { return data(await httpClient.get<ApiResponse<StandardFieldReference[]>>(`${root}/reference/standard-fields`, { params: keyword ? { keyword } : undefined })) },
  async materials(keyword?: string) { return data(await httpClient.get<ApiResponse<MaterialReference[]>>(`${root}/reference/materials`, { params: keyword ? { keyword } : undefined })) },
  async materialAliases(keyword?: string) { return data(await httpClient.get<ApiResponse<MaterialAlias[]>>(`${root}/material-aliases`, { params: keyword ? { keyword } : undefined })) },
  async createMaterialAlias(input: { materialId: string; alias: string; expectedRevision?: number }) { return data(await httpClient.post<ApiResponse<MaterialAlias>>(`${root}/material-aliases`, input, key())) },
  async retireMaterialAlias(id: string, expectedRevision: number, reason: string) { return data(await httpClient.post<ApiResponse<MaterialAlias>>(`${root}/material-aliases/${id}/retire`, { expectedRevision, reason }, key())) },
  async materialDictionaries() { return data(await httpClient.get<ApiResponse<MaterialDictionary[]>>(`${root}/material-dictionaries`)) },
  async createMaterialDictionary(input: { code: string; items: MaterialDictionaryItem[]; encoder: Record<string, unknown>; expectedRevision?: number }) { return data(await httpClient.post<ApiResponse<MaterialDictionary>>(`${root}/material-dictionaries`, input, key())) },
  async freezeMaterialDictionary(id: string, expectedRevision: number) { return data(await httpClient.post<ApiResponse<MaterialDictionary>>(`${root}/material-dictionaries/${id}/freeze`, { expectedRevision }, key())) },
  async retireMaterialDictionary(id: string, expectedRevision: number, reason: string) { return data(await httpClient.post<ApiResponse<MaterialDictionary>>(`${root}/material-dictionaries/${id}/retire`, { expectedRevision, reason }, key())) },

  async samples(params: { keyword?: string; status?: string; sourceType?: string; page?: number; size?: number } = {}) {
    return data(await httpClient.get<ApiResponse<Page<TrainingSampleSummary>>>(`${root}/samples`, { params }))
  },
  async sample(id: string) { return data(await httpClient.get<ApiResponse<TrainingSampleDetail>>(`${root}/samples/${id}`)) },
  async reconcileFacts() { return data(await httpClient.post<ApiResponse<{ projectedSubmissionItems: number; projectedExperimentSamples: number; scannedSources: number }>>(`${root}/facts/reconcile`, undefined, key())) },

  async eligibilitySummary(targetVersionId: string, inputSchemeId: string) {
    return data(await httpClient.get<ApiResponse<EligibilitySummary>>(`${root}/eligibility/summary`, { params: { targetVersionId, inputSchemeId } }))
  },
  async eligibilityPage(params: { targetVersionId: string; inputSchemeId: string; state?: string; reasonCode?: string; sourceType?: string; keyword?: string; page?: number; size?: number }) {
    return data(await httpClient.get<ApiResponse<EligibilityPage>>(`${root}/eligibility`, { params }))
  },
  async eligibilityDetail(id: string) { return data(await httpClient.get<ApiResponse<EligibilityDetail>>(`${root}/eligibility/${id}`)) },
  async reevaluateTarget(id: string) { return data(await httpClient.post<ApiResponse<RecomputeAccepted>>(`${root}/targets/${id}/reevaluate`, undefined, key())) },
  async decideReview(id: string, input: ReviewDecisionCommand) { return data(await httpClient.post<ApiResponse<ReviewDecision>>(`${root}/reviews/${id}/decisions`, input, key())) },

}
