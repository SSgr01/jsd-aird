import type { DataFieldValueView, DataRow } from '@/services/data/data-api';

/** Value corrections retain field coordinates/definitions; refresh only server results. */
export function refreshFieldResults(fields: DataFieldValueView[], rows: DataRow[]): DataFieldValueView[] {
  const values = new Map(rows.map(row => [row.id, row]));
  return fields.map(field => {
    const row = field.recordId ? values.get(field.recordId) : undefined;
    if (!row) return field;
    const candidates = Object.entries(row.normalizedValues).filter(([, value]) =>
      value && typeof value === 'object' && value.bindingId === field.bindingId && value.valuePath === field.valuePath);
    if (candidates.length !== 1) return field;
    const [key, value] = candidates[0]!;
    const correctedValue = row.correctedValues[key]?.correctedValue ?? null;
    return { ...field, rawValue: value.rawValue, normalizedValue: value.normalizedValue, correctedValue,
      effectiveValue: correctedValue ?? value.normalizedValue, excluded: row.excluded ?? false,
      exclusionReason: row.exclusionReason, valueStatus: row.status };
  });
}
