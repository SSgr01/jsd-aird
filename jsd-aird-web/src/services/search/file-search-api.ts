import type { ApiResponse } from '@/types/api';
import { httpClient } from '@/services/http/client';

export interface FileSearchResult {
  files: Array<{
    fileObjectId: string;
    logicalDocumentId?: string;
    fileVersionId: string;
    resourceType: 'KNOWLEDGE_DOCUMENT' | 'DATA_IMPORT_JOB';
    resourceId: string;
    sourceModule: 'KNOWLEDGE' | 'DATA_CENTER';
    title: string;
    originalName: string;
    contentType: string;
    size: number;
    version: number;
    tags: string[];
    updatedAt: string;
    matchType: 'EXACT_FILENAME' | 'EXACT_IDENTIFIER' | 'CONTENT' | 'FULL_TEXT';
    matchedFields: string[];
    matchedTerms: string[];
    relatedProjects: Array<{
      projectId: string;
      projectCode: string;
      projectName: string;
      stageId?: string;
      stageName?: string;
      taskId?: string;
      taskName?: string;
    }>;
    sourceOwner?: 'DATA_CENTER' | 'EXPERIMENT';
    recognitionMode?: 'FREEFORM' | 'TEMPLATE_GUIDED';
    experimentId?: string;
    experimentVersionId?: string;
    hits: Array<{
      id: string;
      snippet: string;
      score: number;
      anchor: {
        pageNo?: number;
        sheetName?: string;
        cellRange?: string;
        paragraphId?: string;
        bbox?: number[];
        startTimeMs?: number;
        endTimeMs?: number;
        section?: string;
        rowNumber?: number;
        columnName?: string;
      };
    }>;
  }>;
}

export const fileSearchApi = {
  async fileSearch(input: {
    query: string;
    aiOnly?: boolean;
    limit?: number;
    knowledgeCategoryIds?: string[];
    dataCategoryIds?: string[];
    projectId?: string;
  }) {
    const response = await httpClient.post<ApiResponse<FileSearchResult>>(
      '/api/v1/search/files',
      input,
    );
    return response.data.data;
  },
};
