import { httpClient } from '@/services/http/client'
import type { ApiResponse } from '@/types/api'

const root = '/api/v1/ai/rnd/test-metric-catalog'
const unwrap = <T>(response: { data: ApiResponse<T> }) => response.data.data

export type TestMetricCatalogImport = {
  id: string
  sourceName: string
  catalogVersion: number
  sourceRowCount: number
  proposalCount: number
  createdAt: string
}

export type TestMetricCatalogIndicator = {
  id: string
  sourceRow: number
  rawItemName: string
  rawTestMethod?: string | null
  rawExampleResult?: string | null
  rawInfluenceFactors?: string | null
  atomicName: string
  suggestedUnit?: string | null
  aliases: string[]
  factorSuggestions: string[]
}

export type TestMetricCatalogIndicatorPage = {
  content: TestMetricCatalogIndicator[]
  page: number
  size: number
  totalPages: number
  totalElements: number
}

export const testMetricCatalogApi = {
  async latest() {
    const result = unwrap(await httpClient.get<ApiResponse<TestMetricCatalogImport[]>>(`${root}/imports`, { params: { limit: 1 } }))
    return result[0]
  },
  async importFile(fileId: string) {
    return unwrap(await httpClient.post<ApiResponse<TestMetricCatalogImport>>(`${root}/imports`, { fileId }))
  },
  async indicators(importId: string) {
    return unwrap(await httpClient.get<ApiResponse<TestMetricCatalogIndicatorPage>>(`${root}/imports/${encodeURIComponent(importId)}/indicators`, { params: { page: 1, size: 200 } }))
  },
}
