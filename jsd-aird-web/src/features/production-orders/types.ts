import type { DocumentStructure, TemplateBinding, TemplateFormat } from '@/features/template-workspace/types';

export type ProductionOrderStatus = 'DRAFT' | 'SUBMITTED' | 'CANCELLED';

export interface ProductionOrderListItem {
  id: string;
  orderNo: string;
  status: ProductionOrderStatus;
  templateVersionId: string;
  templateName: string;
  templateCode: string;
  format: TemplateFormat;
  productId?: string;
  productName?: string;
  quantity?: number;
  unitCode?: string;
  plannedDate?: string;
  ownerId?: string;
  ownerName?: string;
  createdBy: string;
  createdByName: string;
  createdAt: string;
  updatedBy?: string;
  updatedByName?: string;
  updatedAt: string;
  allowedActions?: string[];
}

export interface ProductionWorkspace {
  id: string;
  orderNo: string;
  status: ProductionOrderStatus;
  templateVersionId: string;
  templateName: string;
  templateCode: string;
  format: TemplateFormat;
  productId?: string;
  productName?: string;
  quantity?: number;
  unitCode?: string;
  plannedDate?: string;
  ownerId?: string;
  ownerName?: string;
  schema: Record<string, unknown>;
  mapping: TemplateBinding[];
  data: Record<string, unknown>;
  documentStructure?: DocumentStructure;
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
  createdBy: string;
  createdByName: string;
  createdAt: string;
  updatedBy?: string;
  updatedByName?: string;
  updatedAt: string;
  allowedActions?: string[];
}

export interface ProductionOrderPage {
  items: ProductionOrderListItem[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

export interface ProductionOrderLookupOption {
  id: string;
  code: string;
  name: string;
  defaultUnit?: string;
}
