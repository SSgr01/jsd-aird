export type TemplateFormat = 'XLSX' | 'DOCX';
export type TemplateStatus = 'DRAFT' | 'PUBLISHED' | 'RETIRED';
export type BindingRole = 'FIELD' | 'REPEAT_REGION' | 'CONDITIONAL';
export type MappingKind = 'SCALAR' | 'REPEAT_REGION' | 'REPEAT_FIELD';
export type RepeatAxis = 'ROW' | 'COLUMN' | 'UNKNOWN';
export type SyncDirection = 'TWO_WAY' | 'DATA_TO_EDITOR' | 'EDITOR_TO_DATA';
export type BindingStatus = 'VALID' | 'INVALID' | 'AMBIGUOUS' | 'MISSING';
export type Editability = 'EDITABLE' | 'READ_ONLY' | 'CONDITIONAL' | 'UNKNOWN';
export type ValueSource = 'USER_INPUT' | 'FORMULA' | 'REFERENCE' | 'STATIC' | 'MIXED' | 'UNKNOWN';
export type FieldOrigin = 'STANDARD' | 'TEMPLATE_LOCAL' | 'ORDER_LOCAL' | 'PENDING_STANDARD';
export type FieldUiType = 'TEXT' | 'SIGNATURE';
export type TemplateFieldType = 'FIELD' | 'TABLE_COLUMN' | 'REGION' | 'MANUAL_VALUE';
export type LabelStatus = 'RESOLVED' | 'UNRESOLVED' | 'NOT_APPLICABLE';

export interface TemplateListItem {
  templateId: string;
  versionId: string;
  templateCode: string;
  name: string;
  category?: string;
  format: TemplateFormat;
  status: TemplateStatus;
  versionNo: number;
  lockVersion: number;
  updatedAt: string;
  issueCount: number;
  categoryId?: string;
  currentPublishedVersionId?: string;
  currentPublishedVersionNo?: number;
  retiredVersionNo?: number;
  draftVersionId?: string;
  draftVersionNo?: number;
  hasDraft: boolean;
  createdBy?: string;
  createdByName?: string;
  createdAt: string;
  allowedActions?: string[];
}

export interface TemplateBinding {
  bindingId: string;
  fieldId?: string;
  relationId?: string;
  parentBindingId?: string;
  markerId?: string;
  fieldCode?: string;
  dataPath: string;
  role: BindingRole;
  mappingKind?: MappingKind;
  repeatAxis?: RepeatAxis;
  recordHeight?: number;
  recordWidth?: number;
  recordStride?: number;
  termination?: Record<string, unknown>;
  locatorType: string;
  locator: Record<string, unknown>;
  /** Semantic label path persisted with recognition results. */
  labelPath?: string;
  labelPathSegments?: string[];
  syncDirection: SyncDirection;
  primaryBinding: boolean;
  bindingStatus: BindingStatus;
  diagnostic?: Record<string, unknown>;
}

export type FieldKind = 'SCALAR' | 'FORM_REGION' | 'ROW_TABLE' | 'COLUMN_TABLE';
export type FieldReviewStatus = 'CONFIRMED' | 'NEEDS_CONFIRMATION' | 'ISSUE';

export interface FieldGroup {
  id: string;
  name: string;
  groupCode?: string;
  order: number;
}

export interface BusinessField {
  id: string;
  fieldId?: string;
  relationId?: string;
  recognitionItemId?: string;
  bindingId?: string;
  dataPath?: string;
  fieldCode?: string;
  groupId: string;
  name: string;
  displayRole?: 'REGION' | 'FIELD';
  fieldType?: TemplateFieldType;
  labelStatus?: LabelStatus;
  pathSegments?: string[];
  kind: FieldKind;
  valueType: string;
  uiType?: FieldUiType;
  required: boolean;
  unit?: string;
  description?: string;
  interpretation?: string;
  manualOverrides?: Array<'name' | 'unit' | 'valueType'>;
  recognitionDiff?: Record<string, unknown>;
  confidence?: number;
  reviewStatus: FieldReviewStatus;
  editability?: Editability;
  valueSource?: ValueSource;
  condition?: string;
  blockId?: string;
  regionId?: string;
  parentBlockId?: string;
  parentFieldId?: string;
  parentSuggestionId?: string;
  mappingKind?: MappingKind;
  repeatAxis?: RepeatAxis;
  recordHeight?: number;
  recordWidth?: number;
  recordStride?: number;
  semanticConflict?: boolean;
  conflictCode?: string;
  conflictMessage?: string;
  dictionaryVersion?: number;
  standardMatchStatus?: 'MATCHED' | 'UNMATCHED' | 'CONFIRMED';
  standardRequired?: boolean;
  requiresStandardConfirmation?: boolean;
  standardFieldId?: string;
  standardFieldVersion?: number;
  standardFieldName?: string;
  fieldOrigin?: FieldOrigin;
  standardSelectionStatus?: 'MATCHED' | 'CONFIRMED' | 'CUSTOM' | 'REQUESTED';
  runtimeInputOnly?: boolean;
  templateStatus?: 'RUNTIME_INPUT' | 'CONFIRMED';
  publishable?: boolean;
  termination?: Record<string, unknown>;
  labelRange?: string;
  valueRange?: string;
  dataStartRow?: number;
  locator?: Record<string, unknown>;
  columns?: Array<{
    code: string;
    bindingId?: string;
    relationId?: string;
    fieldId?: string;
    fieldCode?: string;
    dataPath?: string;
    name: string;
    valueType?: string;
    unit?: string;
    labelRange?: string;
    valueRange?: string;
    editability?: Editability;
    valueSource?: ValueSource;
    condition?: string;
    required?: boolean;
    dataStartRow?: number;
    semanticConflict?: boolean;
    conflictCode?: string;
    conflictMessage?: string;
    semanticAlternatives?: Array<{ fieldCode: string; name: string }>;
    dictionaryVersion?: number;
    standardMatchStatus?: 'MATCHED' | 'UNMATCHED' | 'CONFIRMED';
    requiresStandardConfirmation?: boolean;
    uiType?: FieldUiType;
    columnOffset?: number;
    columnSpan?: number;
    physicalColumnRanges?: string[];
    mergeRange?: string;
    valueMode?: string;
    locator?: Record<string, unknown>;
    fieldType?: 'TABLE_COLUMN';
    labelStatus?: LabelStatus;
  }>;
  tableModel?: Record<string, unknown>;
  /** Recognition candidates are rendered in the field tree but never persisted as formal fields. */
  candidate?: boolean;
  candidateLocatorType?: string;
  candidateLocator?: Record<string, unknown>;
}

export interface WorkbookStructureOperation {
  operationId: string;
  type: 'INSERT_ROWS' | 'DELETE_ROWS' | 'INSERT_COLUMNS' | 'DELETE_COLUMNS' | 'RENAME_SHEET';
  sheetId: string;
  sheetName?: string;
  index?: number;
  count?: number;
  previousSheetName?: string;
  nextSheetName?: string;
  source: 'CUSTOMER' | 'AI';
}

export interface BusinessBlock {
  blockId: string;
  parentBlockId?: string;
  sheetId: string;
  range: string;
  type:
    | 'FORM_REGION'
    | 'ROW_TABLE'
    | 'COLUMN_TABLE';
  businessName: string;
  groupName?: string;
}

export interface FieldModel {
  modelVersion: number;
  groups: FieldGroup[];
  fields: BusinessField[];
  blocks: BusinessBlock[];
  semanticAnnotations: Array<Record<string, unknown>>;
  staticRegions?: StaticRegion[];
}

export interface StaticRegion {
  id?: string;
  sheetId: string;
  sheetName?: string;
  address: string;
  regionType: 'STATIC_REFERENCE' | 'INSTRUCTION' | 'NOTE';
  displayName: string;
  source?: 'TEMPLATE_BASELINE' | 'MODEL' | 'HUMAN';
  locked?: boolean;
}

export interface EditorSelection {
  sheetId: string;
  sheetName: string;
  address: string;
}

export interface EditorCellChange extends EditorSelection {
  value: unknown;
  previousValue: unknown;
}

export interface TemplateWorkspace {
  templateId: string;
  versionId: string;
  recognitionRunId?: string;
  templateCode: string;
  name: string;
  format: TemplateFormat;
  status: TemplateStatus;
  versionNo: number;
  schema: Record<string, unknown>;
  mapping: TemplateBinding[];
  data: Record<string, unknown>;
  /**
   * Read-only projection of the original DOCX. It is used for compatibility
   * feedback and field placement; the downloadable OOXML file remains the
   * source of truth.
   */
  documentStructure?: DocumentStructure;
  wordDocument?: {
    sourceDocxFileId?: string;
    workingDocxFileId?: string;
    publishedDocxFileId?: string;
    documentHash?: string;
    state?: 'WORKING' | 'PUBLISHED';
  };
  inlineSnapshot?: Record<string, unknown>;
  snapshotFileId?: string;
  snapshotHash?: string;
  snapshotKind: 'UNIVER_WORKBOOK' | 'UNIVER_DOCUMENT';
  editorAppVersion: string;
  pluginManifestHash: string;
  snapshotFormatVersion: number;
  schemaHash: string;
  mappingHash: string;
  dataHash: string;
  workspaceHash: string;
  lockVersion: number;
  reconciliationRequired: boolean;
  allowedActions?: string[];
}

export interface DocumentStructure {
  schemaVersion?: number;
  documentType?: 'WORD';
  documentId?: string;
  structureHash?: string;
  nodeCount?: number;
  headingCount?: number;
  nodes?: DocumentStructureNode[];
  blocks?: Array<{
    id: string;
    sourcePath?: string;
    legacyId?: string;
    type: 'PARAGRAPH' | 'TABLE';
    text?: string;
    rowCount?: number;
    columnCount?: number;
    style?: string;
    alignment?: string;
    rows?: Array<{
      id: string;
      sourcePath?: string;
      cells?: Array<{
        id: string;
        legacyId?: string;
        sourcePath?: string;
        text?: string;
        editable?: boolean;
      }>;
    }>;
  }>;
  tables?: Array<{
    id: string;
    sourcePath?: string;
    parentTablePath?: string;
    parentCellPath?: string;
    nestingDepth?: number;
    layoutContainer?: boolean;
    rowCount?: number;
    columnCount?: number;
    rows?: Array<{
      id: string;
      sourcePath?: string;
      rowIndex?: number;
      cells?: Array<{
        id: string;
        legacyId?: string;
        sourcePath?: string;
        text?: string;
        editable?: boolean;
        rowIndex?: number;
        columnIndex?: number;
        logicalColumnStart?: number;
        logicalColumnEnd?: number;
        rowSpan?: number;
        columnSpan?: number;
        mergeRootId?: string;
        mergeContinuation?: boolean;
      }>;
    }>;
  }>;
  anchors?: Array<{
    nodeId: string;
    kind: 'PARAGRAPH' | 'RUN' | 'TEXT' | 'TABLE_CELL' | 'CONTENT_CONTROL';
    parentId?: string;
    sourcePath?: string;
    text?: string;
    editable?: boolean;
  }>;
  contentControls?: Array<{
    nodeId: string;
    contentControlId?: string;
    markerId?: string;
    tag?: string;
    alias?: string;
    text?: string;
    kind?: string;
    sourcePath?: string;
  }>;
  compatibility?: {
    status?: 'SUPPORTED' | 'DEGRADED' | 'BLOCKED';
    imageCount?: number;
    hasComments?: boolean;
    hasFootnotes?: boolean;
    hasEndnotes?: boolean;
  };
}

export type DocumentStructureNodeType =
  | 'DOCUMENT_TITLE'
  | 'HEADING'
  | 'PARAGRAPH'
  | 'LIST_ITEM'
  | 'TABLE'
  | 'TABLE_ROW'
  | 'TABLE_CELL'
  | 'IMAGE'
  | 'PAGE_BREAK'
  | 'HEADER'
  | 'FOOTER';

export interface DocumentStructureNode {
  nodeId: string;
  type: DocumentStructureNodeType;
  parentId?: string;
  level?: number;
  title?: string;
  text?: string;
  sortOrder?: number;
  sourceLocator?: Record<string, unknown>;
  editorLocator?: {
    snapshotRevision?: number;
    startOffset?: number;
    endOffset?: number;
    textHash?: string;
  };
  properties?: Record<string, unknown>;
}

export interface TemplateVersionHistoryItem {
  versionId: string;
  versionNo: number;
  status: TemplateStatus;
  createdAt: string;
  updatedAt: string;
  publishedAt?: string;
  saveCount: number;
  derivedFromVersionId?: string;
  createdBy?: string;
  createdByName?: string;
  currentPublished: boolean;
  canRollback: boolean;
}

export interface EditorHandle {
  getSnapshot(): Record<string, unknown>;
  readBinding(binding: TemplateBinding): unknown;
  writeBinding(binding: TemplateBinding, value: unknown): Promise<void>;
  writeLabel?(binding: TemplateBinding, value: unknown): Promise<void>;
  focusBinding(binding: TemplateBinding): void;
  focusCell?(sheetId: string, address: string): void;
  focusRange?(sheetId: string, address: string): void;
  writeCell?(sheetId: string, address: string, value: unknown): Promise<void>;
  focusNode?(node: DocumentStructureNode): void;
  appendRepeatRecord?(binding: TemplateBinding): Promise<void>;
  applyCellPatch?(patch: Record<string, unknown>): Promise<void>;
  insertWordControl?(
    role: BindingRole,
    fieldCode: string,
    dataPath: string,
  ): Promise<Pick<TemplateBinding, 'markerId' | 'locatorType' | 'locator'>>;
}
