import type { TemplateBinding } from './types';
import { getAtPath, setAtPath } from './path-utils';

export interface BindingValuePair {
  dataPath: string;
  dataValue: unknown;
  editorValue: unknown;
}

/**
 * Reads one workbook into canonical business data. Repeating parents that have
 * semantic child bindings are structural only; their two-dimensional cell
 * surface must never overwrite the array of business records assembled from
 * those children.
 */
export function synchronizeStructuredData(
  source: Record<string, unknown>,
  mapping: TemplateBinding[],
  readBinding: (binding: TemplateBinding) => unknown,
) {
  let data = structuredClone(source);
  const childrenByParent = new Map<string, TemplateBinding[]>();
  for (const binding of mapping) {
    if (!binding.parentBindingId) continue;
    const children = childrenByParent.get(binding.parentBindingId) ?? [];
    children.push(binding);
    childrenByParent.set(binding.parentBindingId, children);
  }

  const values: BindingValuePair[] = [];
  for (const binding of mapping) {
    if (binding.syncDirection === 'DATA_TO_EDITOR') continue;
    if (childrenByParent.has(binding.bindingId)) continue;
    const editorValue = readBinding(binding);
    // An unresolved range is different from an explicitly empty cell.  Keep
    // the last known business value when the editor cannot read a binding
    // during workbook initialization or a structural update.
    if (editorValue === undefined) continue;
    data = setAtPath(data, binding.dataPath, editorValue);
    values.push({
      dataPath: binding.dataPath,
      dataValue: getAtPath(data, binding.dataPath),
      editorValue,
    });
  }
  for (const parent of mapping.filter((binding) => binding.mappingKind === 'MATRIX_REGION')) {
    const records = getAtPath(data, parent.dataPath);
    if (!Array.isArray(records)) continue;
    const slots = matrixSlots(parent);
    data = setAtPath(data, parent.dataPath, records.map((record, index) => {
      const current: Record<string, unknown> = isRecord(record) ? record : { value: record };
      if (isRecord(current._member) && typeof current._member.slotId === 'string') return current;
      const slot = slots[index];
      return {
        ...current,
        _member: {
          slotId: typeof slot?.slotId === 'string'
            ? slot.slotId
            : `${parent.bindingId}:${parent.repeatAxis || 'ROW'}:${index}`,
          ...(typeof slot?.label === 'string' ? { label: slot.label } : {}),
          ...(typeof slot?.identityAddress === 'string'
            ? { coordinate: slot.identityAddress }
            : {}),
        },
      };
    }));
  }
  return { data, bindingValues: values };
}

function matrixSlots(binding: TemplateBinding): Array<Record<string, unknown>> {
  for (const source of [binding.locator.columnSlots, binding.locator.rowSlots]) {
    if (Array.isArray(source)) return source.filter(isRecord);
  }
  return [];
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}
