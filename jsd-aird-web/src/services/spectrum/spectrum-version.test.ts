import { spectrumApi } from './spectrum-api';

const http = vi.hoisted(() => ({ get: vi.fn(), put: vi.fn(), delete: vi.fn() }));
vi.mock('@/services/http/client', () => ({ httpClient: http }));

beforeEach(() => {
  vi.clearAllMocks();
  http.put.mockResolvedValue({ data: { data: { revision: 'next' } } });
  http.delete.mockResolvedValue({ data: { data: null } });
});

it('sends the original chart revision for edit, move and delete', async () => {
  await spectrumApi.updateChart('chart', { title: 'changed', revision: 'original' });
  await spectrumApi.assignChartCategory('chart', 'category', 'original');
  await spectrumApi.deleteChart('chart', 'original');
  expect(http.put).toHaveBeenCalledWith('/api/v1/spc/charts/chart', {
    title: 'changed',
    revision: 'original',
  });
  expect(http.put).toHaveBeenCalledWith('/api/v1/spc/charts/chart/category', {
    categoryId: 'category',
    revision: 'original',
  });
  expect(http.delete).toHaveBeenCalledWith('/api/v1/spc/charts/chart', {
    params: { revision: 'original' },
  });
});

it('sends the original category revision for rename and delete', async () => {
  await spectrumApi.updateCategory('category', { name: 'changed', revision: 'original' });
  await spectrumApi.deleteCategory('category', 'original');
  expect(http.put).toHaveBeenCalledWith('/api/v1/spc/categories/category', {
    name: 'changed',
    revision: 'original',
  });
  expect(http.delete).toHaveBeenCalledWith('/api/v1/spc/categories/category', {
    params: { revision: 'original' },
  });
});

it.each([409, 500])(
  'never fetches a newer revision or repeats a write after %s',
  async (status) => {
    http.put.mockRejectedValueOnce({ response: { status } });
    await expect(
      spectrumApi.updateChart('chart', { title: 'unsaved', revision: 'original' }),
    ).rejects.toMatchObject({ response: { status } });
    expect(http.put).toHaveBeenCalledTimes(1);
    expect(http.get).not.toHaveBeenCalled();
  },
);
