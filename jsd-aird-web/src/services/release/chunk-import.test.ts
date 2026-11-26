import { retryChunkImport } from './chunk-import';

afterEach(() => vi.useRealTimers());

it('recovers a transient module download without reloading the document', async () => {
  vi.useFakeTimers();
  const importer = vi.fn().mockRejectedValueOnce(new Error('Failed to fetch dynamically imported module'))
    .mockResolvedValue({ default: 'loaded' });
  const result = expect(retryChunkImport(importer)).resolves.toEqual({ default: 'loaded' });
  await vi.runAllTimersAsync();
  await result;
  expect(importer).toHaveBeenCalledTimes(2);
});

it('bounds retries for a persistent CSS or module failure', async () => {
  vi.useFakeTimers();
  const error = new Error('Unable to preload CSS for /assets/editor.css');
  const importer = vi.fn().mockRejectedValue(error);
  const result = expect(retryChunkImport(importer)).rejects.toBe(error);
  await vi.runAllTimersAsync();
  await result;
  expect(importer).toHaveBeenCalledTimes(3);
});

it('does not retry application exceptions', async () => {
  const error = new Error('render initialization failed');
  const importer = vi.fn().mockRejectedValue(error);
  await expect(retryChunkImport(importer)).rejects.toBe(error);
  expect(importer).toHaveBeenCalledTimes(1);
});
