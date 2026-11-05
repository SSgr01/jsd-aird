import { httpClient } from '@/services/http/client';
import type { ApiResponse } from '@/types/api';

export type QuotationImportStatus = 'PARSING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type QuotationSourceFormat = 'XLSX' | 'XLS' | 'CSV';

export interface QuotationImportJob {
  id: string;
  sourceFileId: string;
  sourceFileName: string;
  sourceSha256: string;
  sourceFormat: QuotationSourceFormat;
  status: QuotationImportStatus;
  quotationId?: string | null;
  quotationNo?: string | null;
  customerName?: string | null;
  itemCount: number;
  errorMessage?: string | null;
  createdAt: string;
  progress: number;
  currentStage?: string | null;
  templateVersionId?: string | null;
  projectName?: string | null;
  stageName?: string | null;
  taskName?: string | null;
  visibility?: 'ALL' | 'RND' | 'PROJECT';
}

export interface QuotationSummary {
  id: string;
  quotationNo: string;
  customerName: string;
  relatedCompany?: string | null;
  contactName?: string | null;
  phone?: string | null;
  sourceFileId?: string | null;
  sourceFileName?: string | null;
  itemCount: number;
  firstQuoteDate?: string | null;
  lastQuoteDate?: string | null;
  createdAt: string;
  reviewStatus: QuotationReviewStatus;
}

export interface QuotationItem {
  id: string;
  lineNo: number;
  sourceRowNo: number;
  productName: string;
  unitPrice?: string | null;
  specification?: string | null;
  quoteDate?: string | null;
  customerCode?: string | null;
  note?: string | null;
  priceOptions: QuotationPriceOption[];
}

export interface QuotationPriceOption {
  id: string;
  position: number;
  unitPrice: number;
  specification?: string | null;
}

export interface QuotationContact {
  id: string;
  position: number;
  sourceColumn: number;
  name?: string | null;
  title?: string | null;
  phone?: string | null;
}

export interface QuotationDetail {
  summary: QuotationSummary;
  contactTitle?: string | null;
  fax?: string | null;
  email?: string | null;
  addressLine1?: string | null;
  addressLine2?: string | null;
  website?: string | null;
  note?: string | null;
  sourceFormat?: string | null;
  contacts: QuotationContact[];
  items: QuotationItem[];
  workbookSnapshot?: Record<string, unknown> | null;
  versionNo: number;
}

export interface QuotationVersion {
  versionNo: number;
  createdAt: string;
  createdBy: string;
  createdByName?: string | null;
  detail: QuotationDetail;
}

export type QuotationReviewStatus = 'NOT_SUBMITTED' | 'SUBMITTED' | 'APPROVED' | 'REJECTED';

export interface QuotationReviewEvent {
  id: string;
  fromStatus?: QuotationReviewStatus | null;
  toStatus: QuotationReviewStatus;
  quotationVersion: number;
  actorId: string;
  actorName: string;
  comment?: string | null;
  createdAt: string;
}

export interface QuotationReview {
  quotationId: string;
  quotationVersion: number;
  status: QuotationReviewStatus;
  submittedBy?: string | null;
  submittedAt?: string | null;
  reviewedBy?: string | null;
  reviewedAt?: string | null;
  comment?: string | null;
  lockVersion: number;
  events: QuotationReviewEvent[];
}

export interface QuotationPage {
  items: QuotationSummary[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

const data = <T>(response: { data: ApiResponse<T> }) => response.data.data;

export async function createQuotationImport(input: {
  fileId: string;
  fileName: string;
  sha256: string;
  format: QuotationSourceFormat;
  templateVersionId: string;
  projectId?: string;
  projectName?: string;
  stageId?: string;
  stageName?: string;
  taskId?: string;
  taskName?: string;
  visibility: 'ALL' | 'RND' | 'PROJECT';
}) {
  return data(
    await httpClient.post<ApiResponse<{ jobId: string; quotationId?: string | null; status: string }>>(
      '/api/v1/quotation-imports',
      input,
    ),
  );
}

export async function listQuotationImports() {
  return data(await httpClient.get<ApiResponse<QuotationImportJob[]>>('/api/v1/quotation-imports'));
}

export async function retryQuotationImport(id: string) {
  return data(
    await httpClient.post<ApiResponse<{ jobId: string; status: string }>>(
      `/api/v1/quotation-imports/${id}/retry`,
    ),
  );
}

export async function deleteQuotationImport(id: string) {
  return data(await httpClient.delete<ApiResponse<null>>(`/api/v1/quotation-imports/${id}`));
}

export async function listQuotations(
  params: Record<string, unknown>,
  signal?: AbortSignal,
) {
  return data(
    await httpClient.get<ApiResponse<QuotationPage>>('/api/v1/quotations', { params, signal }),
  );
}

export async function getQuotation(id: string) {
  return data(await httpClient.get<ApiResponse<QuotationDetail>>(`/api/v1/quotations/${id}`));
}

export async function getQuotationSourceWorkbook(id: string) {
  return data(await httpClient.get<ApiResponse<Record<string, unknown>>>(`/api/v1/quotations/${id}/source-workbook`));
}

export type QuotationEdit = Pick<QuotationDetail,
  'contactTitle' | 'fax' | 'email' | 'addressLine1' | 'addressLine2' | 'website' | 'note'> & {
  customerName: string;
  relatedCompany?: string | null;
  contactName?: string | null;
  phone?: string | null;
  contacts: Array<Pick<QuotationContact, 'sourceColumn' | 'name' | 'title' | 'phone'>>;
  items: Array<Pick<QuotationItem,
    'sourceRowNo' | 'productName' | 'unitPrice' | 'specification' | 'quoteDate' | 'customerCode' | 'note'> & {
    priceOptions: Array<Pick<QuotationPriceOption, 'unitPrice' | 'specification'>>;
  }>;
  workbookSnapshot?: Record<string, unknown> | null;
  versionNo: number;
};

export async function updateQuotation(id: string, input: QuotationEdit) {
  return data(await httpClient.put<ApiResponse<QuotationDetail>>(`/api/v1/quotations/${id}`, input));
}

export async function listQuotationVersions(id: string) {
  return data(await httpClient.get<ApiResponse<QuotationVersion[]>>(`/api/v1/quotations/${id}/versions`));
}

export async function rollbackQuotationVersion(id: string, versionNo: number, expectedVersion: number) {
  return data(await httpClient.post<ApiResponse<QuotationDetail>>(
    `/api/v1/quotations/${id}/versions/${versionNo}/rollback`, { expectedVersion }));
}

export async function getQuotationReview(id: string) {
  return data(await httpClient.get<ApiResponse<QuotationReview>>(`/api/v1/quotations/${id}/review`));
}

export async function submitQuotationReview(id: string, quotationVersion: number, reviewVersion: number) {
  return data(await httpClient.post<ApiResponse<QuotationReview>>(
    `/api/v1/quotations/${id}/review/submit`, { quotationVersion, reviewVersion }));
}

export async function approveQuotationReview(id: string, quotationVersion: number, reviewVersion: number) {
  return data(await httpClient.post<ApiResponse<QuotationReview>>(
    `/api/v1/quotations/${id}/review/approve`, { quotationVersion, reviewVersion }));
}

export async function rejectQuotationReview(id: string, quotationVersion: number,
                                            reviewVersion: number, comment: string) {
  return data(await httpClient.post<ApiResponse<QuotationReview>>(
    `/api/v1/quotations/${id}/review/reject`, { quotationVersion, reviewVersion, comment }));
}

export async function deleteQuotation(id: string) {
  return data(await httpClient.delete<ApiResponse<null>>(`/api/v1/quotations/${id}`));
}
