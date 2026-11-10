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
  const readable: Array<{ binding: TemplateBinding; editorValue: unknown }> = [];
  const repeatLengths = new Map<string, number>();
  for (const binding of mapping) {
    if (binding.syncDirection === 'DATA_TO_EDITOR') continue;
    if (childrenByParent.has(binding.bindingId)) continue;
    const editorValue = readBinding(binding);
    // An unresolved range is different from an explicitly empty cell.  Keep
    // the last known business value when the editor cannot read a binding
    // during workbook initialization or a structural update.
    if (editorValue === undefined) continue;
    readable.push({ binding, editorValue });
    const repeatPath = binding.dataPath.split('/*/')[0];
    if (binding.mappingKind === 'REPEAT_FIELD' && repeatPath && Array.isArray(editorValue)) {
      repeatLengths.set(repeatPath, Math.max(repeatLengths.get(repeatPath) ?? 0, editorValue.length));
    }
  }
  for (const [repeatPath, length] of repeatLengths) {
    const existing = getAtPath(data, repeatPath);
    if (Array.isArray(existing) && existing.length > length) {
      data = setAtPath(data, repeatPath, existing.slice(0, length));
    }
  }
  for (const { binding, editorValue } of readable) {
    const repeatPath = binding.dataPath.split('/*/')[0];
    const repeatLength = binding.mappingKind === 'REPEAT_FIELD'
      ? repeatLengths.get(repeatPath ?? '') : undefined;
    const normalizedValue = Array.isArray(editorValue) && repeatLength !== undefined
      ? [...editorValue, ...Array(Math.max(0, repeatLength - editorValue.length)).fill(null)]
      : editorValue;
    data = setAtPath(data, binding.dataPath, normalizedValue);
  }
  for (const { binding, editorValue } of readable) {
    const repeatPath = binding.dataPath.split('/*/')[0];
    const repeatLength = binding.mappingKind === 'REPEAT_FIELD'
      ? repeatLengths.get(repeatPath ?? '') : undefined;
    const normalizedValue = Array.isArray(editorValue) && repeatLength !== undefined
      ? [...editorValue, ...Array(Math.max(0, repeatLength - editorValue.length)).fill(null)]
      : editorValue;
    values.push({
      dataPath: binding.dataPath,
      dataValue: getAtPath(data, binding.dataPath),
      editorValue: normalizedValue,
    });
  }
  return { data, bindingValues: values };
}
