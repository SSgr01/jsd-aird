import type { ApiResponse } from '@/types/api';
import type { DataWorkbookSnapshot } from '@/services/data/data-api';
import { resolveInstanceDefinition } from '@/features/template-workspace/instance-definition';
import { labWorkbookInstance } from '@/features/experiment-workspace/lab-structure-adapter';
import { presentWorkbookFields } from './field-presentation';
import { instanceWorkbookView } from './instance-workbook-view';

/** Pure preparation shared by the worker and focused tests. No editor or DOM work here. */
export function prepareImportWorkbook(payload: string | DataWorkbookSnapshot): DataWorkbookSnapshot {
  let workbook = typeof payload === 'string' ? (JSON.parse(payload) as ApiResponse<DataWorkbookSnapshot>).data : payload;
  const instance = resolveInstanceDefinition(workbook.snapshot, workbook.templateInstance) ?? labWorkbookInstance(workbook.snapshot);
  // Read-only views share one definition. Univer creates its own mutable snapshot on mount.
  if (instance) {
    const sheet = Object.values(workbook.snapshot.sheets as Record<string, { custom?: Record<string, unknown> }>)[0];
    if (sheet) sheet.custom = { ...sheet.custom, templateInstance: instance };
    if (workbook.fieldResultsSource === 'SNAPSHOT') {
      const view = instanceWorkbookView(workbook.snapshot, instance, workbook.editable);
      workbook = { ...workbook, regions: view.regions, records: view.records,
        fields: view.fields, fieldDefinitions: view.fieldDefinitions };
    }
  }
  return presentWorkbookFields(workbook, instance);
}
