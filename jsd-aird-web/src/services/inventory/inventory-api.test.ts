import { beforeEach, describe, expect, it, vi } from 'vitest';
import { httpClient } from '@/services/http/client';
import { inventoryApi } from './inventory-api';

vi.mock('@/services/http/client', () => ({ httpClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() } }));
describe('inventoryApi', () => {
  beforeEach(() => vi.clearAllMocks());
  it('passes inventory filters to the balance endpoint', async () => {
    vi.mocked(httpClient.get).mockResolvedValueOnce({
      data: {
        data: {
          items: [],
          page: 1,
          size: 20,
          total: 0,
          totalPages: 0,
          summary: { totalKg: 0, productCount: 0, lowStockCount: 0, outOfStockCount: 0 },
        },
      },
    });
    await inventoryApi.balances({ scope: 'RND', alert: 'LOW_STOCK' });
    expect(httpClient.get).toHaveBeenCalledWith('/api/v1/inventory/balances', {
      params: { scope: 'RND', alert: 'LOW_STOCK' },
    });
  });
  it('sends an idempotency key for stock mutations', async () => {
    vi.mocked(httpClient.post).mockResolvedValueOnce({ data: { data: { id: 'tx-1' } } });
    await inventoryApi.move({ productId: 'p1', quantityKg: 1 });
    expect(httpClient.post).toHaveBeenCalledWith(
      '/api/v1/inventory/transactions',
      { productId: 'p1', quantityKg: 1 },
      expect.objectContaining({
        headers: expect.objectContaining({ 'Idempotency-Key': expect.any(String) }),
      }),
    );
  });
  it('creates a custom product in the current inventory scope', async () => {
    vi.mocked(httpClient.post).mockResolvedValueOnce({
      data: {
        data: {
          id: 'product-1',
          code: 'RD-1234',
          name: '自定义树脂',
          category: '研发产品',
          status: 'ACTIVE',
        },
      },
    });
    await expect(inventoryApi.createCustomProduct('自定义树脂', 'RND')).resolves.toBe('product-1');
    expect(httpClient.post).toHaveBeenCalledWith(
      '/api/v1/inventory/products',
      expect.objectContaining({
        name: '自定义树脂',
        scope: 'RND',
      }),
    );
  });
  it('creates a shipment and customer together with a stable idempotency key', async () => {
    vi.mocked(httpClient.post).mockResolvedValueOnce({ data: { data: { id: 'shipment-1' } } });
    const shipment = { shipmentNo: 'CH-001', packageQuantity: 3, specification: '2.5', idempotencyKey: 'detail-request-1' };
    await inventoryApi.createShipmentForNewCustomer(shipment, '新客户');
    expect(httpClient.post).toHaveBeenCalledWith(
      '/api/v1/inventory/shipments/new-customer',
      { shipment, customerName: '新客户' },
      { headers: { 'Idempotency-Key': 'detail-request-1' } },
    );
  });
  it('marks selected shipments completed through the status endpoint', async () => {
    vi.mocked(httpClient.put).mockResolvedValueOnce({ data: { data: 2 } });
    await expect(inventoryApi.updateShipmentStatuses(['shipment-1', 'shipment-2'])).resolves.toBe(2);
    expect(httpClient.put).toHaveBeenCalledWith('/api/v1/inventory/shipments/status', {
      ids: ['shipment-1', 'shipment-2'], status: 'COMPLETED',
    });
  });
  it('completes the pending details of one shipment header', async () => {
    vi.mocked(httpClient.put).mockResolvedValueOnce({ data: { data: 2 } });
    await expect(inventoryApi.completeShipmentHeader('CH 001')).resolves.toBe(2);
    expect(httpClient.put).toHaveBeenCalledWith('/api/v1/inventory/shipment-headers/CH%20001/status');
  });
  it('sends selected shipment headers for bulk completion', async () => {
    vi.mocked(httpClient.put).mockResolvedValueOnce({ data: { data: { headerCount: 2, detailCount: 3 } } });
    await expect(inventoryApi.completeShipmentHeaders(['CH-1', 'CH-2'])).resolves.toEqual({ headerCount: 2, detailCount: 3 });
    expect(httpClient.put).toHaveBeenCalledWith('/api/v1/inventory/shipment-headers/status', {
      shipmentNos: ['CH-1', 'CH-2'],
    });
  });
  it('updates a shipment header with its note', async () => {
    vi.mocked(httpClient.put).mockResolvedValueOnce({ data: { data: {} } });
    await inventoryApi.updateShipmentHeader('CH 001', { businessDate: '2026-10-28', note: '备注' });
    expect(httpClient.put).toHaveBeenCalledWith('/api/v1/inventory/shipment-headers/CH%20001', {
      shipmentNo: 'CH 001', businessDate: '2026-10-28', note: '备注',
    });
  });
  it('deletes selected pending details from their shipment header', async () => {
    vi.mocked(httpClient.delete).mockResolvedValueOnce({ data: { data: 2 } });
    await expect(inventoryApi.deleteShipmentDetails('CH 001', ['detail-1', 'detail-2'])).resolves.toBe(2);
    expect(httpClient.delete).toHaveBeenCalledWith('/api/v1/inventory/shipment-headers/CH%20001/details', {
      data: ['detail-1', 'detail-2'],
    });
  });
  it('deletes a shipment header', async () => {
    vi.mocked(httpClient.delete).mockResolvedValueOnce({ data: { data: 2 } });
    await expect(inventoryApi.deleteShipmentHeader('CH 001')).resolves.toBe(2);
    expect(httpClient.delete).toHaveBeenCalledWith('/api/v1/inventory/shipment-headers/CH%20001');
  });
  it('deletes multiple shipment headers', async () => {
    vi.mocked(httpClient.delete).mockResolvedValueOnce({ data: { data: 2 } });
    await expect(inventoryApi.deleteShipmentHeaders(['CH-1', 'CH-2'])).resolves.toBe(2);
    expect(httpClient.delete).toHaveBeenCalledWith('/api/v1/inventory/shipment-headers', {
      data: ['CH-1', 'CH-2'],
    });
  });
  it('deletes selected samples', async () => {
    vi.mocked(httpClient.delete).mockResolvedValueOnce({ data: { data: 2 } });
    await expect(inventoryApi.deleteSamples(['sample-1', 'sample-2'])).resolves.toBe(2);
    expect(httpClient.delete).toHaveBeenCalledWith('/api/v1/inventory/samples', {
      data: ['sample-1', 'sample-2'],
    });
  });
  it('uploads initial stock as multipart form data', async () => {
    vi.mocked(httpClient.post).mockResolvedValueOnce({
      data: { data: { id: 'i1', status: 'VALIDATED', rows: [], errors: [] } },
    });
    const file = new File(['x'], 'stock.xlsx');
    await inventoryApi.uploadInitial(file);
    const body = vi.mocked(httpClient.post).mock.calls[0]![1] as FormData;
    expect(body.get('file')).toBe(file);
  });
});
