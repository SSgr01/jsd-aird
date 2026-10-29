import { beforeEach, describe, expect, it, vi } from 'vitest';
import { preloadWorkspaceEditor } from './preload-editor';

const { preloadEditorRuntime, markPerformance, measurePerformance } = vi.hoisted(() => ({
  preloadEditorRuntime: vi.fn(),
  markPerformance: vi.fn(),
  measurePerformance: vi.fn(),
}));

vi.mock('./editor-loader', () => ({ preloadEditorRuntime }));
vi.mock('@/services/performance/metrics', () => ({ markPerformance, measurePerformance }));

describe('workspace editor preloading', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    preloadEditorRuntime.mockReset().mockResolvedValue(undefined);
  });

  it.each(['DOCX', 'XLSX', 'word', 'excel', 'WORD', 'EXCEL'])(
    'preserves support for the %s format through the shared runtime loader',
    async (format) => {
      preloadWorkspaceEditor(format);
      expect(preloadEditorRuntime).toHaveBeenCalledWith(format);
      await Promise.resolve();
    },
  );

  it('starts without waiting and records readiness only after the runtime loads', async () => {
    let finish!: () => void;
    preloadEditorRuntime.mockReturnValue(new Promise<void>((resolve) => { finish = resolve; }));

    expect(preloadWorkspaceEditor('DOCX')).toBeUndefined();
    expect(markPerformance).toHaveBeenCalledExactlyOnceWith('editor-code-start');
    expect(measurePerformance).not.toHaveBeenCalled();

    finish();
    await Promise.resolve();
    expect(markPerformance).toHaveBeenNthCalledWith(2, 'editor-code-ready');
    expect(measurePerformance).toHaveBeenCalledExactlyOnceWith(
      'editor-code', 'editor-code-start', 'editor-code-ready',
    );
  });

  it('handles a rejected preload without marking it ready or leaking a rejection', async () => {
    preloadEditorRuntime.mockRejectedValue(new Error('runtime unavailable'));

    preloadWorkspaceEditor('XLSX');
    await Promise.resolve();
    await Promise.resolve();

    expect(markPerformance).toHaveBeenCalledExactlyOnceWith('editor-code-start');
    expect(measurePerformance).not.toHaveBeenCalled();
  });
});
