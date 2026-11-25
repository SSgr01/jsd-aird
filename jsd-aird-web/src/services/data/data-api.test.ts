import type { AxiosProgressEvent, AxiosRequestConfig } from 'axios';
import { dataApi } from './data-api';

const mocks = vi.hoisted(() => ({
  post: vi.fn<(url: string, body?: unknown, config?: AxiosRequestConfig) => Promise<unknown>>(),
  put: vi.fn<(url: string, body?: unknown, config?: AxiosRequestConfig) => Promise<unknown>>(),
  get: vi.fn<(url: string, config?: AxiosRequestConfig) => Promise<unknown>>(),
}));

vi.mock('@/services/http/client', () => ({ httpClient: { post: mocks.post, put: mocks.put, get: mocks.get }, LARGE_PAYLOAD_TIMEOUT: 0 }));
vi.mock('@/services/files', () => ({ fetchFileBlob: vi.fn() }));

describe('dataApi', () => {
  beforeEach(() => vi.clearAllMocks());

  it.each([false, true])('allows the complete grouped preview response to arrive (experiment=%s)', async (experiment) => {
    mocks.get.mockResolvedValueOnce({ data: { data: { rows: [] } } });
    await expect(dataApi.preview('job', experiment)).resolves.toEqual({ rows: [] });
    expect(mocks.get).toHaveBeenCalledWith(`/api/v1/data/${experiment ? 'experiment-import-jobs' : 'import-jobs'}/job/preview`, { timeout: 0 });
  });

  it('waits for whole-workbook validation when saving import mappings', async () => {
    mocks.put.mockResolvedValueOnce({ data: { data: { id: 'job', status: 'WAITING_CONFIRM' } } });
    await expect(dataApi.saveMappings('job', 'revision', [])).resolves.toEqual({ id: 'job', status: 'WAITING_CONFIRM' });
    expect(mocks.put).toHaveBeenCalledWith('/api/v1/data/import-jobs/job/mappings', { revision: 'revision', items: [] }, { timeout: 0 });
  });

  it.each([false, true])('waits for grouped import confirmation within the server operation budget (experiment=%s)', async (experiment) => {
    mocks.post.mockResolvedValueOnce({ data: { data: { id: 'job', status: 'COMPLETED' } } });
    await expect(dataApi.commit('job', experiment)).resolves.toEqual({ id: 'job', status: 'COMPLETED' });
    expect(mocks.post).toHaveBeenCalledWith(`/api/v1/data/${experiment ? 'experiment-import-jobs' : 'import-jobs'}/job/commit`, undefined, { timeout: 0 });
  });

  it('reports the uploaded byte percentage while staging a data source', async () => {
    mocks.post.mockImplementationOnce((_url, _body, config) => {
      config?.onUploadProgress?.({ loaded: 25, total: 100 } as AxiosProgressEvent);
      return Promise.resolve({ data: { data: { fileId: 'file-1', sha256: 'hash', status: 'STAGED' } } });
    });
    const onProgress = vi.fn();

    await dataApi.stageSource(new File(['data'], 'source.csv'), onProgress);

    expect(onProgress).toHaveBeenCalledWith(25);
    expect(mocks.post).toHaveBeenCalledTimes(1);
    const [url, body, config] = mocks.post.mock.calls[0]!;
    expect(url).toBe('/api/v1/files/staged?kind=DATA_SOURCE');
    expect(body).toBeInstanceOf(FormData);
    expect(typeof config?.onUploadProgress).toBe('function');
  });
});
