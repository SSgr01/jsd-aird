import { httpClient } from '@/services/http/client';
import type { ApiResponse } from '@/types/api';
import { generateUUID } from '@/utils/uuid';

export type InventoryScope = 'RND' | 'PRODUCTION';
export type InventoryDirection = 'INBOUND' | 'OUTBOUND';
export type AlertStatus = 'NORMAL' | 'LOW_STOCK' | 'OUT_OF_STOCK';
export interface InventoryBalance {
  policyRevision: string;
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
  revision: string;
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
  revision: string;
  id: string;
  dispatchNo: string;
  businessDate: string;
  productId: string;
  productName: string;
  specification?: string;
  packageQuantity?: number;
  scope: InventoryScope;
  quantityKg: number;
  customerId?: string;
  customerName: string;
  recipientName: string;
  recipientPhone?: string;
  sampleAddress?: string;
  courierCompany?: string;
  trackingNo?: string;
  clientContact?: string;
  clientContactPhone?: string;
  senderName?: string;
  customerRequirement?: string;
  customerFeedback?: string;
  status: 'PENDING' | 'COMPLETED';
  transactionId: string;
  operator: string;
  createdAt: string;
}
export interface Shipment {
  revision: string;
  id: string;
  shipmentNo: string;
  businessDate: string;
  productId: string;
  productName: string;
  scope: InventoryScope;
  quantityKg: number;
  customerId?: string;
  customerName: string;
  senderName?: string;
  batchNo?: string;
  specification?: string;
  packageQuantity?: number;
  totalQuantityKg?: number;
  recipientName?: string;
  recipientPhone?: string;
  shippingAddress?: string;
  courierCompany?: string;
  trackingNo?: string;
  note?: string;
  coaNo?: string;
  status: 'PENDING' | 'COMPLETED';
  transactionId?: string;
  operator: string;
  createdAt: string;
}
export interface ShipmentHeader {
  id: string;
  shipmentNo: string;
  businessDate: string;
  detailCount: number;
  completedCount: number;
  totalQuantityKg: number;
}
export interface InventoryTransactionUpdateInput {
  revision: string;
  businessDate: string;
  documentNo: string;
  reason: string;
  note?: string;
  actorName?: string;
}
export interface SampleDispatchUpdateInput {
  revision: string;
  dispatchNo: string;
  businessDate: string;
  specification?: string;
  packageQuantity?: number;
  customerId?: string;
  recipientName: string;
  recipientPhone?: string;
  sampleAddress?: string;
  courierCompany?: string;
  trackingNo?: string;
  clientContact?: string;
  clientContactPhone?: string;
  senderName?: string;
  customerRequirement?: string;
  customerFeedback?: string;
  status?: 'PENDING' | 'COMPLETED';
}
export interface ShipmentUpdateInput {
  revision: string;
  shipmentNo: string;
  businessDate: string;
  customerId: string;
  senderName?: string;
  batchNo?: string;
  recipientName?: string;
  recipientPhone?: string;
  shippingAddress?: string;
  courierCompany?: string;
  trackingNo?: string;
  note?: string;
  coaNo?: string;
  status?: 'PENDING' | 'COMPLETED';
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
  String(input.idempotencyKey ?? (fallback || generateUUID()));
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
  async balances(params: Record<string, unknown> = {}, signal?: AbortSignal) {
    return (
      await httpClient.get<ApiResponse<BalancePage>>('/api/v1/inventory/balances', { params, signal })
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
  async transactionReasons(scope?: InventoryScope) {
    return (
      await httpClient.get<ApiResponse<string[]>>('/api/v1/inventory/transaction-reasons', {
        params: { scope },
      })
    ).data.data;
  },
  async updatePolicy(
    productId: string,
    scope: InventoryScope,
    input: { revision: string; packageKg?: number; lowStockKg: number },
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
  async reverse(id: string, revision: string, note?: string) {
    return (
      await httpClient.post<ApiResponse<InventoryTransaction>>(
        `/api/v1/inventory/transactions/${id}/reverse`,
        { note, revision },
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
  async sampleRecipientPreference(customerId: string) {
    return (
      await httpClient.get<ApiResponse<{ recipientName: string; recipientPhone: string; sampleAddress: string } | null>>(
        `/api/v1/inventory/samples/recipient-preference/${customerId}`,
      )
    ).data.data;
  },
  async createSample(input: Record<string, unknown>) {
    return (
      await httpClient.post<ApiResponse<SampleDispatch>>('/api/v1/inventory/samples', input, {
        headers: { 'Idempotency-Key': key(input, `SAMPLE:${String(input.dispatchNo ?? '')}`) },
      })
    ).data.data;
  },
  async createSampleForNewCustomer(sample: Record<string, unknown>, customerName: string) {
    return (
      await httpClient.post<ApiResponse<SampleDispatch>>(
        '/api/v1/inventory/samples/new-customer',
        { sample, customerName },
        { headers: { 'Idempotency-Key': key(sample, `SAMPLE:${typeof sample.dispatchNo === 'string' ? sample.dispatchNo : ''}`) } },
      )
    ).data.data;
  },
  async updateSampleStatuses(ids: string[]) {
    return (
      await httpClient.put<ApiResponse<number>>('/api/v1/inventory/samples/status', { ids, status: 'COMPLETED' })
    ).data.data;
  },
  async updateShipmentStatuses(ids: string[]) {
    return (
      await httpClient.put<ApiResponse<number>>('/api/v1/inventory/shipments/status', { ids, status: 'COMPLETED' })
    ).data.data;
  },
  async updateSample(id: string, input: SampleDispatchUpdateInput) {
    return (
      await httpClient.put<ApiResponse<SampleDispatch>>(`/api/v1/inventory/samples/${id}`, input)
    ).data.data;
  },
  async updateSampleForNewCustomer(id: string, sample: SampleDispatchUpdateInput, customerName: string) {
    return (
      await httpClient.put<ApiResponse<SampleDispatch>>(
        `/api/v1/inventory/samples/${id}/new-customer`,
        { sample, customerName },
      )
    ).data.data;
  },
  async shipments(params: Record<string, unknown> = {}) {
    return (
      await httpClient.get<ApiResponse<Page<Shipment>>>('/api/v1/inventory/shipments', { params })
    ).data.data;
  },
  async shipmentHeaders(params: Record<string, unknown> = {}) {
    return (await httpClient.get<ApiResponse<Page<ShipmentHeader>>>('/api/v1/inventory/shipment-headers', { params })).data.data;
  },
  async shipmentHeader(shipmentNo: string) {
    return (await httpClient.get<ApiResponse<ShipmentHeader>>(`/api/v1/inventory/shipment-headers/${encodeURIComponent(shipmentNo)}`)).data.data;
  },
  async shipmentDetails(shipmentNo: string, params: Record<string, unknown> = {}) {
    return (await httpClient.get<ApiResponse<Page<Shipment>>>(`/api/v1/inventory/shipment-headers/${encodeURIComponent(shipmentNo)}/details`, { params })).data.data;
  },
  async completeShipmentHeader(shipmentNo: string) {
    return (await httpClient.put<ApiResponse<number>>(
      `/api/v1/inventory/shipment-headers/${encodeURIComponent(shipmentNo)}/status`,
    )).data.data;
  },
  async completeShipmentHeaders(shipmentNos: string[]) {
    return (await httpClient.put<ApiResponse<{ headerCount: number; detailCount: number }>>(
      '/api/v1/inventory/shipment-headers/status', { shipmentNos },
    )).data.data;
  },
  async createShipmentHeader(input: { shipmentNo: string; businessDate: string }) {
    return (await httpClient.post<ApiResponse<ShipmentHeader>>('/api/v1/inventory/shipment-headers', input)).data.data;
  },
  async updateShipmentHeaderDate(shipmentNo: string, businessDate: string) {
    return (await httpClient.put<ApiResponse<ShipmentHeader>>(
      `/api/v1/inventory/shipment-headers/${encodeURIComponent(shipmentNo)}`, { shipmentNo, businessDate },
    )).data.data;
  },
  async createShipmentBatch(shipmentNo: string, details: Record<string, unknown>[]) {
    return (await httpClient.post<ApiResponse<Shipment[]>>(
      `/api/v1/inventory/shipment-headers/${encodeURIComponent(shipmentNo)}/details/batch`, details,
    )).data.data;
  },
  async importShipment(shipmentNo: string, details: Record<string, unknown>[]) {
    return (await httpClient.post<ApiResponse<Shipment[]>>(
      `/api/v1/inventory/shipment-headers/${encodeURIComponent(shipmentNo)}/import`, details,
    )).data.data;
  },
  async createShipment(input: Record<string, unknown>) {
    return (
      await httpClient.post<ApiResponse<Shipment>>('/api/v1/inventory/shipments', input, {
        headers: { 'Idempotency-Key': key(input, '') },
      })
    ).data.data;
  },
  async createShipmentForNewCustomer(shipment: Record<string, unknown>, customerName: string) {
    return (
      await httpClient.post<ApiResponse<Shipment>>(
        '/api/v1/inventory/shipments/new-customer',
        { shipment, customerName },
        { headers: { 'Idempotency-Key': key(shipment, '') } },
      )
    ).data.data;
  },
  async updateShipment(id: string, input: ShipmentUpdateInput) {
    return (
      await httpClient.put<ApiResponse<Shipment>>(`/api/v1/inventory/shipments/${id}`, input)
    ).data.data;
  },
  async updateShipmentForNewCustomer(id: string, shipment: ShipmentUpdateInput, customerName: string) {
    return (
      await httpClient.put<ApiResponse<Shipment>>(
        `/api/v1/inventory/shipments/${id}/new-customer`,
        { shipment, customerName },
      )
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
