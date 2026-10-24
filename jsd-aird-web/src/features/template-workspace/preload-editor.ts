import { markPerformance, measurePerformance } from '@/services/performance/metrics';
import { preloadEditorRuntime } from './editor-loader';

/** Download only the required runtime. Import failures remain visible to lazy boundaries. */
export function preloadWorkspaceEditor(format: string) {
  markPerformance('editor-code-start');
  const request = preloadEditorRuntime(format);
  void request.then(() => {
    markPerformance('editor-code-ready');
    measurePerformance('editor-code', 'editor-code-start', 'editor-code-ready');
  }).catch(() => undefined);
}
