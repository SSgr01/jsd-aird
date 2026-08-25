import { beforeEach, describe, expect, it, vi } from 'vitest';

import { httpClient } from '@/services/http/client';
import { productionOrderApi } from './production-order-api';

vi.mock('@/services/http/client', () => ({
  httpClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

describe('productionOrderApi', () => {
  beforeEach(() => vi.clearAllMocks());

  it('passes server-side list filters and pagination', async () => {
    vi.mocked(httpClient.get).mockResolvedValueOnce({
      data: { data: { items: [], page: 2, size: 20, total: 0, totalPages: 0 } },
    });
    await productionOrderApi.list({ keyword: 'PO-1', status: 'DRAFT', page: 2, size: 20 });
    expect(httpClient.get).toHaveBeenCalledWith('/api/v1/production-orders', {
      params: { keyword: 'PO-1', status: 'DRAFT', page: 2, size: 20 },
    });
  });

  it('sends product and owner links when creating an order', async () => {
    const input = { orderNo: 'PO-1', templateVersionId: 'tpl-1', productId: 'product-1', ownerId: 'user-1', quantity: 10, unitCode: 'kg', plannedDate: '2026-08-20' };
    vi.mocked(httpClient.post).mockResolvedValueOnce({ data: { data: { id: 'order-1' } } });
    await productionOrderApi.create(input);
    expect(httpClient.post).toHaveBeenCalledWith('/api/v1/production-orders', input);
  });
});
