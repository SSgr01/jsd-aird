import type { WorkbookTemplateInstance } from '@/features/template-workspace/workbook-instance';
import type { DataWorkbookSnapshot } from '@/services/data/data-api';

export function decodeImportWorkbook(payload: string, signal?: AbortSignal): Promise<DataWorkbookSnapshot> {
  return prepareInWorker(payload, signal);
}
export function buildImportWorkbookView(snapshot: Record<string, unknown>, instance: WorkbookTemplateInstance, signal?: AbortSignal) {
  return prepareInWorker({ snapshot, instance }, signal);
}
function prepareInWorker(payload: string | { snapshot: Record<string, unknown>; instance: WorkbookTemplateInstance }, signal?: AbortSignal): Promise<DataWorkbookSnapshot> {
  if (signal?.aborted) return Promise.reject(new DOMException('Aborted', 'AbortError'));
  if (typeof Worker === 'undefined') return typeof payload === 'string'
    ? import('./import-workbook-preparation').then(module => module.prepareImportWorkbook(payload))
    : import('./instance-workbook-view').then(module => module.instanceWorkbookView(payload.snapshot, payload.instance, true));
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL('./import-workbook.worker.ts', import.meta.url), { type: 'module' });
    const timeout = setTimeout(() => { cleanup(); reject(new Error('工作簿加载超时')); }, 60_000);
    const cleanup = () => { clearTimeout(timeout); signal?.removeEventListener('abort', abort); worker.terminate(); };
    const abort = () => { cleanup(); reject(new DOMException('Aborted', 'AbortError')); };
    signal?.addEventListener('abort', abort, { once: true });
    worker.onmessage = (event: MessageEvent<{ workbook?: DataWorkbookSnapshot; error?: string }>) => {
      cleanup();
      if (event.data.workbook) resolve(event.data.workbook);
      else reject(new Error(event.data.error || '工作簿加载失败'));
    };
    worker.onerror = (event) => { cleanup(); reject(new Error(event.message || '工作簿加载失败')); };
    worker.postMessage(payload);
  });
}
