import type { BusinessField, FieldModel, TemplateBinding } from './types';
import { percentageFormat } from '@/features/data-workbench/cell-format';

/** A new business field inherits layout, not the preceding field's number format. */
export function fieldNumberFormatEdits(previous: FieldModel, next: FieldModel, bindings: TemplateBinding[]) {
  return next.fields.flatMap(field => {
    if (field.displayRole === 'REGION' || field.mappingKind === 'REPEAT_REGION') return [];
    const old = previous.fields.find(item => item.id === field.id);
    const initialize = !old && field.fieldOrigin !== 'STANDARD';
    const changed = old && ((old.unit ?? '') !== (field.unit ?? '') || old.valueType !== field.valueType);
    const binding = bindings.find(item => item.bindingId === field.bindingId);
    return binding && (initialize || changed) ? [{ field, binding, initialize, typeChanged: Boolean(old && old.valueType !== field.valueType) }] : [];
  });
}

export function fieldNumberFormat(field: BusinessField, initialize: boolean, pattern: string, typeChanged = false) {
  const defaults: Record<string, string> = { string: '@', date: 'yyyy-mm-dd', datetime: 'yyyy-mm-dd hh:mm:ss', time: 'hh:mm:ss' };
  const regular = defaults[field.valueType] ?? 'General';
  if (initialize || typeChanged) return regular;
  // Ignore escaped/quoted literal % characters in custom Excel formats.
  const percent = percentageFormat(pattern);
  const percentageField = field.unit?.trim().replace('％', '%') === '%' && field.valueType === 'number';
  return percent && !percentageField ? regular : pattern;
}
