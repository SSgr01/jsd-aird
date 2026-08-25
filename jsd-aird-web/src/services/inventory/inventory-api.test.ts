import { beforeEach, describe, expect, it, vi } from 'vitest';
import { httpClient } from '@/services/http/client';
import { inventoryApi } from './inventory-api';

vi.mock('@/services/http/client', () => ({ httpClient: { get: vi.fn(), post: vi.fn() } }));
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
