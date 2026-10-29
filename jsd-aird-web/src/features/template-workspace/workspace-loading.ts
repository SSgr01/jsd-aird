import { useCallback, useEffect, useRef } from 'react';
import { markPerformance, measurePerformance } from '@/services/performance/metrics';

/** Late results cannot replace a newer version or update an unmounted workspace. */
export function useWorkspaceLoadGeneration() {
  const generation = useRef(0);
  useEffect(() => () => { generation.current += 1; }, []);
  return useCallback(() => {
    const current = ++generation.current;
    markPerformance('workspace-open');
    return () => current === generation.current;
  }, []);
}

export async function trackSnapshot<T>(request: Promise<T>): Promise<T> {
  markPerformance('document-download-start');
  const snapshot = await request;
  markPerformance('document-downloaded');
  measurePerformance('document-download', 'document-download-start', 'document-downloaded');
  return snapshot;
}

export function editorReady() {
  markPerformance('editor-ready');
  measurePerformance('editor-initialize', 'editor-init-start', 'editor-ready');
  measurePerformance('workspace-open', 'workspace-open', 'editor-ready');
  if (!performance.getEntriesByName('aird:boot-ready').length) {
    markPerformance('boot-ready');
    measurePerformance('boot', 'boot-start', 'boot-ready');
  }
}
