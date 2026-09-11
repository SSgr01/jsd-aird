import type { AxiosProgressEvent, AxiosRequestConfig } from 'axios';
import { dataApi } from './data-api';

const mocks = vi.hoisted(() => ({
  post: vi.fn<(url: string, body?: unknown, config?: AxiosRequestConfig) => Promise<unknown>>(),
}));

vi.mock('@/services/http/client', () => ({ httpClient: { post: mocks.post } }));
vi.mock('@/services/files', () => ({ fetchFileBlob: vi.fn() }));

describe('dataApi', () => {
  beforeEach(() => vi.clearAllMocks());

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
