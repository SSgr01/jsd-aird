import { httpClient } from '@/services/http/client';
import type { ApiResponse } from '@/types/api';

export type ProductionUploadVisibility = 'ALL' | 'RND' | 'QUALITY' | 'PROJECT';

/**
 * Stages the backend reports while a production order is being recognised.  The
 * upload list and the workspace progress strip read the same map so the wording
 * cannot drift between the two screens.
 */
export const PRODUCTION_RECOGNITION_STAGE_LABELS: Record<string, string> = {
  QUEUED: '等待解析任务',
  LOADING_SOURCE: '正在读取源文件',
  PARSING_STRUCTURE: '正在分析文件结构',
  MATCHING_TEMPLATE: '正在匹配生产单模板',
  WAITING_TEMPLATE: '等待选择模板',
  EXTRACTING_VALUES: '正在提取字段',
  RECOGNIZING_IMAGE_VALUES: '正在识别图片字段',
  BUILDING_PREVIEW: '正在生成预览',
  REVIEW_REQUIRED: '解析完成',
  COMPLETED: '解析完成',
  FAILED: '解析失败',
};

/** Progress-strip wording for a stage the backend has not named yet. */
export function productionStageLabel(stage?: string) {
  if (!stage) return '正在识别';
  return PRODUCTION_RECOGNITION_STAGE_LABELS[stage] ?? '正在识别';
}

export interface ProductionUpload {
  id: string;
  fileId: string;
  originalName: string;
  contentType: string;
  size: number;
  sha256: string;
  productionName?: string;
  orderNo?: string;
  productName?: string;
  category?: string;
  manufactureDate?: string;
  projectId?: string;
  projectName?: string;
  stageId?: string;
  stageName?: string;
  taskId?: string;
  taskName?: string;
  visibility: ProductionUploadVisibility;
  sourceType: 'XLSX' | 'PHOTO';
  status:
    | 'QUEUED'
    | 'PARSING'
    | 'MATCHING_TEMPLATE'
    | 'EXTRACTING'
    | 'REVIEW_REQUIRED'
    | 'SAVED'
    | 'PUBLISHED'
    | 'FAILED'
    | 'DELETED';
  createdBy: string;
  createdAt: string;
  workbookSnapshot?: Record<string, unknown>;
  recognitionProgress: number;
  currentStage?: string;
  selectedTemplateVersionId?: string;
  matchMode?:
    'EXACT_MANIFEST' | 'SIMILAR_AUTO' | 'USER_REVIEW' | 'USER_SELECTED_TEMPLATE' | 'NO_TEMPLATE';
  templateMatchScore?: number;
  recognitionResult?: {
    sourceType?: 'XLSX' | 'PHOTO' | 'DOCX';
    model?: string;
    message?: string;
    recognitionWarning?: string;
    recognitionMode?: 'FULL' | 'RULE';
    data?: Record<string, unknown>;
    items?: Array<Record<string, unknown>>;
    issues?: Array<Record<string, unknown>>;
    templateCandidates?: Array<{
      templateVersionId: string;
      templateCode: string;
      templateName: string;
      score: number;
      exact: boolean;
    }>;
    requiresTemplateSelection?: boolean;
    /**
     * Legacy `.doc` uploads are binary OLE2 files that the browser cannot render.
     * The server converts them to DOCX and exposes the previewable copy here;
     * absent when the conversion was unavailable.
     */
    normalizedFileId?: string;
    normalizedFileName?: string;
    normalizationStatus?: string;
    normalizationMessage?: string;
    /**
     * Editable Word artifact.  `.docx` uploads use the original file, legacy
     * `.doc` uploads use the normalized copy; saves replace it with a patched
     * working copy.  Absent when the document structure could not be parsed.
     */
    wordDocument?: {
      sourceDocxFileId?: string;
      workingDocxFileId?: string;
      workingDocxName?: string;
      structureHash?: string;
      /** Layout revision that produced this record; older ones are regenerated. */
      layoutVersion?: number;
    };
    wordStructureStatus?: string;
    wordStructureMessage?: string;
    /**
     * Result of deriving the editable workbook from the document's own tables,
     * which is what lets a Word production order be edited in the sheet
     * workspace.  {@code NO_TABLE} means the document kept the rich-text path.
     */
    docxWorksheetStatus?: 'GENERATED' | 'NO_TABLE' | 'FAILED';
    docxWorksheetMessage?: string;
    /** What the table conversion carried over, and what stayed in the original file. */
    docxWorksheet?: {
      sheetCount?: number;
      cellCount?: number;
      /** Prose that lives in body paragraphs rather than in a table cell. */
      paragraphCharactersOutsideTables?: number;
      /** Pictures and embedded objects, which have no cell to live in. */
      imageCount?: number;
    };
    /**
     * Result of writing a photo's recognized values into its template workbook:
     * {@code GENERATED} or {@code FAILED} with a reason the workspace shows.
     */
    workbookStatus?: string;
    workbookMessage?: string;
    /**
     * Template bindings the recognized fields belong to.  Each binding states the
     * field's name/code and the cell it is written into, which is what the field
     * review panel shows next to the recognized value.
     */
    mapping?: Array<Record<string, unknown>>;
    /**
     * Recognition review in the template center's contract, so the workspace runs
     * the same 识别确认 / 字段结构 / 字段属性 panels and the same status strip.
     */
    recognitionReview?: Record<string, unknown>;
    /** The template's own field model, so the workspace need not derive one. */
    fieldModel?: Record<string, unknown>;
  };
  structureSummary?: Record<string, unknown>;
  failureMessage?: string;
  lockVersion: number;
  updatedBy?: string;
  updatedAt: string;
  allowedActions?: string[];
}

export interface ProductionUploadVersion {
  id: string;
  uploadId: string;
  versionNo: number;
  workbookSnapshot: Record<string, unknown>;
  lockVersion: number;
  changeType: 'PUBLISH';
  createdBy: string;
  createdAt: string;
}

export interface ProductionUploadField {
  id: string;
  itemKey: string;
  itemKind: string;
  bindingId?: string;
  fieldCode?: string;
  dataPath?: string;
  recordIndex?: number;
  rawValue?: unknown;
  normalizedValue?: unknown;
  sourceLocator?: Record<string, unknown>;
  confidence: number;
  reviewStatus: string;
}

export interface ProductionUploadPage {
  items: ProductionUpload[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

export interface CreateProductionUploadInput {
  fileId: string;
  sourceType?: 'XLSX' | 'PHOTO';
  templateVersionId?: string;
  replaceExisting?: boolean;
  productionName?: string;
  orderNo?: string;
  productName?: string;
  category?: string;
  manufactureDate?: string;
  projectId?: string;
  projectName?: string;
  stageId?: string;
  stageName?: string;
  taskId?: string;
  taskName?: string;
  visibility: ProductionUploadVisibility;
}

export const productionUploadApi = {
  async get(id: string) {
    const response = await httpClient.get<ApiResponse<ProductionUpload>>(
      `/api/v1/production-uploads/${id}`,
    );
    return response.data.data;
  },

  async list(params: {
    keyword?: string;
    status?: string;
    projectId?: string;
    viewableOnly?: boolean;
    page: number;
    size: number;
  }) {
    const response = await httpClient.get<ApiResponse<ProductionUploadPage>>(
      '/api/v1/production-uploads',
      { params },
    );
    return response.data.data;
  },

  async create(input: CreateProductionUploadInput) {
    const response = await httpClient.post<ApiResponse<ProductionUpload>>(
      '/api/v1/production-uploads',
      input,
    );
    return response.data.data;
  },

  async rename(id: string, input: {
    revision: number;
    name: string;
    projectId?: string;
    projectName?: string;
    stageId?: string;
    stageName?: string;
    taskId?: string;
    taskName?: string;
  }) {
    const response = await httpClient.put<ApiResponse<ProductionUpload>>(
      `/api/v1/production-uploads/${id}/rename`,
      input,
    );
    return response.data.data;
  },

  async delete(id: string) {
    await httpClient.delete(`/api/v1/production-uploads/${id}`);
  },

  async fields(id: string) {
    const response = await httpClient.get<ApiResponse<ProductionUploadField[]>>(
      `/api/v1/production-uploads/${id}/fields`,
    );
    return response.data.data;
  },

  async ensureFieldReview(id: string) {
    const response = await httpClient.post<ApiResponse<ProductionUpload>>(
      `/api/v1/production-uploads/${id}/field-review`,
    );
    return response.data.data;
  },

  async selectTemplate(id: string, templateVersionId: string) {
    const response = await httpClient.post<ApiResponse<ProductionUpload>>(
      `/api/v1/production-uploads/${id}/select-template`,
      { templateVersionId },
    );
    return response.data.data;
  },

  async retry(id: string) {
    const response = await httpClient.post<ApiResponse<ProductionUpload>>(
      `/api/v1/production-uploads/${id}/retry`,
    );
    return response.data.data;
  },

  /**
   * Saves the reviewed recognition fields.  Only the fields the reviewer touched
   * are sent; the server keeps the recognized status of the rest.
   */
  async confirmFields(
    id: string,
    fields: Array<{
      fieldCode?: string;
      dataPath?: string;
      recordIndex?: number;
      value?: string;
      reviewStatus?: string;
    }>,
  ) {
    const response = await httpClient.put<ApiResponse<ProductionUpload>>(
      `/api/v1/production-uploads/${id}/fields`,
      { fields },
    );
    return response.data.data;
  },

  /** Writes a photo upload's recognized values into its template workbook. */
  async convertToWorkbook(id: string) {
    const response = await httpClient.post<ApiResponse<ProductionUpload>>(
      `/api/v1/production-uploads/${id}/workbook`,
    );
    return response.data.data;
  },
};

export const productionOrderRecordApi = {
  async get(id: string) {
    const response = await httpClient.get<ApiResponse<ProductionUpload>>(
      `/api/v1/production-orders/records/${id}`,
    );
    return response.data.data;
  },

  async saveBatch(input: {
    records: Array<{
      id: string;
      workbookSnapshot?: Record<string, unknown>;
      lockVersion: number;
      productionName?: string;
      orderNo?: string;
      productName?: string;
      category?: string;
      manufactureDate?: string;
    }>;
  }) {
    await httpClient.put('/api/v1/production-orders/records/batch', input);
  },

  async versions(id: string) {
    const response = await httpClient.get<ApiResponse<ProductionUploadVersion[]>>(
      `/api/v1/production-orders/records/${id}/versions`,
    );
    return response.data.data;
  },

  async export(id: string) {
    const response = await httpClient.get<Blob>(
      `/api/v1/production-orders/records/${id}/export`,
      { responseType: 'blob' },
    );
    return response.data;
  },

  async publish(id: string) {
    const response = await httpClient.post<ApiResponse<ProductionUploadVersion>>(
      `/api/v1/production-orders/records/${id}/publish`,
    );
    return response.data.data;
  },
};
