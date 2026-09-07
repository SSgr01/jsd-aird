import { httpClient } from '@/services/http/client';
import type { ApiResponse } from '@/types/api';

export type InventoryScope = 'RND' | 'PRODUCTION';
export type InventoryDirection = 'INBOUND' | 'OUTBOUND';
export type AlertStatus = 'NORMAL' | 'LOW_STOCK' | 'OUT_OF_STOCK';
export interface InventoryBalance {
  productId: string;
  productCode: string;
  productName: string;
  category: string;
  scope: InventoryScope;
  quantityKg: number;
  packageKg?: number;
  lowStockKg: number;
  alertStatus: AlertStatus;
  version: number;
  updatedAt: string;
}
export interface InventorySummary {
  totalKg: number;
  productCount: number;
  lowStockCount: number;
  outOfStockCount: number;
}
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}
export interface BalancePage extends Page<InventoryBalance> {
  summary: InventorySummary;
}
export interface InventoryTransaction {
  id: string;
  productId: string;
  productCode: string;
  productName: string;
  category: string;
  scope: InventoryScope;
  direction: InventoryDirection;
  reason: string;
  quantityKg: number;
  beforeKg: number;
  afterKg: number;
  businessDate: string;
  documentNo: string;
  businessType: string;
  reversalOf?: string;
  note?: string;
  actorName: string;
  createdAt: string;
  reversed: boolean;
}
export interface SampleDispatch {
  id: string;
  dispatchNo: string;
  businessDate: string;
  productId: string;
  productName: string;
  quantityKg: number;
  customerId?: string;
  customerName: string;
  recipientName: string;
  sampleAddress?: string;
  courierCompany?: string;
  trackingNo?: string;
  clientContact?: string;
  customerRequirement?: string;
  customerFeedback?: string;
  transactionId: string;
  operator: string;
  createdAt: string;
}
export interface Shipment {
  id: string;
  shipmentNo: string;
  businessDate: string;
  productId: string;
  productName: string;
  quantityKg: number;
  customerId?: string;
  customerName: string;
  note?: string;
  transactionId: string;
  operator: string;
  createdAt: string;
}
export interface InventoryTransactionUpdateInput {
  businessDate: string;
  documentNo: string;
  reason: string;
  note?: string;
}
export interface SampleDispatchUpdateInput {
  dispatchNo: string;
  businessDate: string;
  customerId: string;
  recipientName: string;
  sampleAddress?: string;
  courierCompany?: string;
  trackingNo?: string;
  clientContact?: string;
  customerRequirement?: string;
  customerFeedback?: string;
}
export interface ShipmentUpdateInput {
  shipmentNo: string;
  businessDate: string;
  customerId: string;
  note?: string;
}
export interface InitialImport {
  id: string;
  status: 'VALIDATED' | 'INVALID' | 'COMMITTED';
  rows: Array<Record<string, string | number>>;
  errors: Array<{ rowNumber: number; field: string; message: string }>;
  committedAt?: string;
}
export interface InventoryProductOption {
  id: string;
  code: string;
  name: string;
  category: string;
  status: string;
}
/**
 * Keep a user operation's idempotency key stable across a retry.
 *
 * The API already de-duplicates by this header, but generating a new UUID for
 * every call meant a browser retry could create a second inventory movement.
 * Business document numbers are unique in the domain, so they are a suitable
 * client-side retry key; callers can still provide an explicit key when a
 * document number is not available.
 */
const key = (input: Record<string, unknown>, fallback: string) =>
  String(input.idempotencyKey ?? (fallback || crypto.randomUUID()));
export const inventoryApi = {
  async createCustomProduct(name: string, scope: InventoryScope) {
    return (
      await httpClient.post<ApiResponse<InventoryProductOption>>('/api/v1/inventory/products', {
        name,
        scope,
      })
    ).data.data.id;
  },
  async productOptions(scope?: InventoryScope, keyword?: string) {
    return (
      await httpClient.get<ApiResponse<InventoryProductOption[]>>('/api/v1/inventory/products', {
        params: { scope, keyword },
      })
    ).data.data;
  },
  async balances(params: Record<string, unknown> = {}) {
    return (
      await httpClient.get<ApiResponse<BalancePage>>('/api/v1/inventory/balances', { params })
    ).data.data;
  },
  async transactions(params: Record<string, unknown> = {}) {
    return (
      await httpClient.get<ApiResponse<Page<InventoryTransaction>>>(
        '/api/v1/inventory/transactions',
        { params },
      )
    ).data.data;
  },
  async updatePolicy(
    productId: string,
    scope: InventoryScope,
    input: { packageKg?: number; lowStockKg: number },
  ) {
    return (
      await httpClient.put<ApiResponse<InventoryBalance>>(
        `/api/v1/inventory/balances/${productId}/policy`,
        input,
        { params: { scope } },
      )
    ).data.data;
  },
  async move(input: Record<string, unknown>) {
    return (
      await httpClient.post<ApiResponse<InventoryTransaction>>(
        '/api/v1/inventory/transactions',
        input,
        {
          headers: {
            'Idempotency-Key': key(
              input,
              `${String(input.businessType ?? 'INVENTORY')}:${String(input.documentNo ?? '')}`,
            ),
          },
        },
      )
    ).data.data;
  },
  async reverse(id: string, note?: string) {
    return (
      await httpClient.post<ApiResponse<InventoryTransaction>>(
        `/api/v1/inventory/transactions/${id}/reverse`,
        { note },
        { headers: { 'Idempotency-Key': `REVERSAL:${id}` } },
      )
    ).data.data;
  },
  async updateTransaction(id: string, input: InventoryTransactionUpdateInput) {
    return (
      await httpClient.put<ApiResponse<InventoryTransaction>>(
        `/api/v1/inventory/transactions/${id}`,
        input,
      )
    ).data.data;
  },
  async samples(params: Record<string, unknown> = {}) {
    return (
      await httpClient.get<ApiResponse<Page<SampleDispatch>>>('/api/v1/inventory/samples', {
        params,
      })
    ).data.data;
  },
  async createSample(input: Record<string, unknown>) {
    return (
      await httpClient.post<ApiResponse<SampleDispatch>>('/api/v1/inventory/samples', input, {
        headers: { 'Idempotency-Key': key(input, `SAMPLE:${String(input.dispatchNo ?? '')}`) },
      })
    ).data.data;
  },
  async updateSample(id: string, input: SampleDispatchUpdateInput) {
    return (
      await httpClient.put<ApiResponse<SampleDispatch>>(`/api/v1/inventory/samples/${id}`, input)
    ).data.data;
  },
  async shipments(params: Record<string, unknown> = {}) {
    return (
      await httpClient.get<ApiResponse<Page<Shipment>>>('/api/v1/inventory/shipments', { params })
    ).data.data;
  },
  async createShipment(input: Record<string, unknown>) {
    return (
      await httpClient.post<ApiResponse<Shipment>>('/api/v1/inventory/shipments', input, {
        headers: { 'Idempotency-Key': key(input, `SHIPMENT:${String(input.shipmentNo ?? '')}`) },
      })
    ).data.data;
  },
  async updateShipment(id: string, input: ShipmentUpdateInput) {
    return (
      await httpClient.put<ApiResponse<Shipment>>(`/api/v1/inventory/shipments/${id}`, input)
    ).data.data;
  },
  async uploadInitial(file: File) {
    const body = new FormData();
    body.append('file', file);
    return (
      await httpClient.post<ApiResponse<InitialImport>>(
        '/api/v1/inventory/initial-stock/imports',
        body,
      )
    ).data.data;
  },
  async commitInitial(id: string) {
    return (
      await httpClient.post<ApiResponse<InitialImport>>(
        `/api/v1/inventory/initial-stock/imports/${id}/commit`,
      )
    ).data.data;
  },
};
