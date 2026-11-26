import { prepareImportWorkbook } from './import-workbook-preparation';
import { instanceWorkbookView } from './instance-workbook-view';
import type { WorkbookTemplateInstance } from '@/features/template-workspace/workbook-instance';
self.onmessage = (event: MessageEvent<string | { snapshot: Record<string, unknown>; instance: WorkbookTemplateInstance }>) => {
  try {
    const workbook = typeof event.data === 'string' ? prepareImportWorkbook(event.data)
      : instanceWorkbookView(event.data.snapshot, event.data.instance, true);
    self.postMessage({ workbook });
  } catch (error) { self.postMessage({ error: error instanceof Error ? error.message : '工作簿加载失败' }); }
};
