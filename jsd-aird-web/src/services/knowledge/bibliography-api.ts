import type { ApiResponse } from '@/types/api';
import { httpClient } from '@/services/http/client';

export type Bibliography = {
  documentId: string; versionId: string; revision: number; status: string; documentType: string;
  accepted: Record<string, unknown>; provenance: Record<string, { source?: string; excerpt?: string; anchor?: { page?: number } }>;
  candidates: Array<Record<string, unknown>>; message?: string;
};
export const bibliographyStatus: Record<string, string> = {
  PENDING: '正在自动补全书目信息', ACCEPTED: '已补全', NEEDS_REVIEW: '部分书目信息暂未补全', NO_MATCH: '暂未找到匹配的书目信息',
  MISSING_PARSE: '缺少解析结果', FAILED: '补全失败', STALE: '文件或解析结果已变化', WAITING_PROVIDER: '等待书目服务恢复',
};
export const bibliographyApi = {
  async get(doc: string, version: string) {
    return (await httpClient.get<ApiResponse<Bibliography>>(`/api/v1/knowledge/documents/${doc}/versions/${version}/bibliography`)).data.data;
  },
  async confirm(doc: string, version: string, revision: number, documentType: string, fields: Record<string, unknown>) {
    return (await httpClient.put<ApiResponse<Bibliography>>(`/api/v1/knowledge/documents/${doc}/versions/${version}/bibliography`, { revision, documentType, fields })).data.data;
  },
};
