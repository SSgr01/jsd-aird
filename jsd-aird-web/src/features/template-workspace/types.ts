export type TemplateFormat = 'XLSX' | 'DOCX';
export type TemplateStatus = 'DRAFT' | 'PUBLISHED' | 'RETIRED';
export type BindingRole = 'FIELD' | 'REPEAT_REGION' | 'CONDITIONAL';
export type SyncDirection = 'TWO_WAY' | 'DATA_TO_EDITOR' | 'EDITOR_TO_DATA';
export type BindingStatus = 'VALID' | 'INVALID' | 'AMBIGUOUS' | 'MISSING';
export type Editability = 'EDITABLE' | 'READ_ONLY' | 'CONDITIONAL' | 'UNKNOWN';
export type ValueSource = 'USER_INPUT' | 'FORMULA' | 'REFERENCE' | 'STATIC' | 'MIXED' | 'UNKNOWN';

export interface TemplateListItem {
  templateId: string;
  versionId: string;
  templateCode: string;
  name: string;
  purpose?: string;
  category?: string;
  format: TemplateFormat;
  status: TemplateStatus;
  versionNo: number;
  lockVersion: number;
  updatedAt: string;
  issueCount: number;
}

export interface TemplateBinding {
  bindingId: string;
  fieldId?: string;
  relationId?: string;
  markerId?: string;
  fieldCode?: string;
  dataPath: string;
  role: BindingRole;
  locatorType: string;
  locator: Record<string, unknown>;
  syncDirection: SyncDirection;
  primaryBinding: boolean;
  bindingStatus: BindingStatus;
  diagnostic?: Record<string, unknown>;
}

export type FieldKind = 'SCALAR' | 'ROW_TABLE' | 'MATRIX';
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
  groupId: string;
  name: string;
  kind: FieldKind;
  valueType: string;
  required: boolean;
  unit?: string;
  description?: string;
  interpretation?: string;
  confidence?: number;
  reviewStatus: FieldReviewStatus;
  editability?: Editability;
  valueSource?: ValueSource;
  condition?: string;
  blockId?: string;
  parentBlockId?: string;
  columns?: Array<{
    code: string;
    name: string;
    valueType?: string;
    unit?: string;
    labelRange?: string;
    valueRange?: string;
    editability?: Editability;
    valueSource?: ValueSource;
    condition?: string;
  }>;
  tableModel?: Record<string, unknown>;
  matrixModel?: Record<string, unknown>;
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
    | 'DOCUMENT_HEADER'
    | 'FORM_FIELDS'
    | 'ROW_TABLE'
    | 'MATRIX'
    | 'INSTRUCTION_LIST'
    | 'CONFIRMATION_BLOCK'
    | 'SIGNATURE_BLOCK'
    | 'NOTE_BLOCK'
    | 'LOOKUP_TABLE'
    | 'UNKNOWN';
  businessName: string;
  groupName?: string;
}

export interface FieldModel {
  modelVersion: number;
  groups: FieldGroup[];
  fields: BusinessField[];
  blocks: BusinessBlock[];
  semanticAnnotations: Array<Record<string, unknown>>;
}

export interface EditorSelection {
  sheetId: string;
  sheetName: string;
  address: string;
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
}

export interface TemplateVersionHistoryItem {
  versionId: string;
  versionNo: number;
  status: TemplateStatus;
  createdAt: string;
  updatedAt: string;
  publishedAt?: string;
  saveCount: number;
}

export interface EditorHandle {
  getSnapshot(): Record<string, unknown>;
  readBinding(binding: TemplateBinding): unknown;
  writeBinding(binding: TemplateBinding, value: unknown): Promise<void>;
  writeLabel?(binding: TemplateBinding, value: unknown): Promise<void>;
  focusBinding(binding: TemplateBinding): void;
  applyCellPatch?(patch: Record<string, unknown>): Promise<void>;
  insertWordControl?(
    role: BindingRole,
    fieldCode: string,
    dataPath: string,
  ): Promise<Pick<TemplateBinding, 'markerId' | 'locatorType' | 'locator'>>;
}
