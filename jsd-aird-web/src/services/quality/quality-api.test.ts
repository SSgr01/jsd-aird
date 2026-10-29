import { describe, expect, it, vi } from 'vitest';
import { httpClient } from '@/services/http/client';
import { qualityApi } from './quality-api';

vi.mock('@/services/http/client', () => ({ httpClient: { get: vi.fn() } }));

describe('qualityApi.records', () => {
  it('passes released COA status and search text to the paged query', async () => {
    vi.mocked(httpClient.get).mockResolvedValueOnce({
      data: { data: { items: [], page: 1, size: 20, total: 0, totalPages: 0 } },
    });

    await qualityApi.records({ type: 'coa', status: '已签发', keyword: 'COA-001', page: 1, size: 20 });

    expect(httpClient.get).toHaveBeenCalledWith('/api/v1/quality/records', {
      params: { type: 'coa', status: '已签发', keyword: 'COA-001', page: 1, size: 20 },
    });
  });
  it('looks up an existing COA by its complete number', async () => {
    vi.mocked(httpClient.get).mockResolvedValueOnce({
      data: { data: { businessNo: 'COA-001', data: { status: '已签发' } } },
    });

    await qualityApi.recordByBusinessNo('coa', 'COA-001');

    expect(httpClient.get).toHaveBeenCalledWith('/api/v1/quality/records/by-number', {
      params: { type: 'coa', businessNo: 'COA-001' },
    });
  });
});
