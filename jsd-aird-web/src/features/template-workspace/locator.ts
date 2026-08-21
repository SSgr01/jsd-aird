export type LocatorSource = 'RECOGNIZED' | 'INFERRED' | 'MANUAL' | 'UNRESOLVED';
export type LocatorRelation = 'ADJACENT' | 'ABOVE' | 'TABLE_HEADER' | 'MERGED_TITLE' | 'MANUAL' | 'UNRESOLVED';

export interface LocatorPart {
  address?: string;
  range?: string;
}

export interface CanonicalLocator extends Record<string, unknown> {
  label?: LocatorPart;
  value?: LocatorPart;
  source?: LocatorSource;
  relation?: LocatorRelation;
  confidence?: number;
  locatorVersion?: number;
}

function text(value: unknown) {
  return typeof value === 'string' ? value.trim() : '';
}

function firstCell(value: string) {
  const first = value.replaceAll('$', '').split(':', 1)[0]?.trim() ?? '';
  return /^[A-Z]{1,4}[1-9][0-9]*$/i.test(first) ? first.toUpperCase() : '';
}

function part(value: unknown): LocatorPart {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
  const record = value as Record<string, unknown>;
  const range = text(record.range) || text(record.address);
  const address = firstCell(text(record.address) || range);
  return {
    ...(address ? { address } : {}),
    ...(range ? { range } : {}),
  };
}

function source(value: unknown, hasLabel: boolean): LocatorSource {
  const normalized = text(value).toUpperCase();
  if (normalized === 'RECOGNIZED' || normalized === 'INFERRED' || normalized === 'MANUAL' || normalized === 'UNRESOLVED') {
    return normalized;
  }
  if (normalized.includes('MANUAL')) return 'MANUAL';
  if (normalized.includes('INFER')) return 'INFERRED';
  return hasLabel ? 'RECOGNIZED' : 'UNRESOLVED';
}

/** Merge field and binding locators without allowing a sparse binding to erase a label. */
export function mergeLocators(
  ...sources: Array<Record<string, unknown> | undefined>
): CanonicalLocator {
  const flat: Record<string, unknown> = {};
  sources.forEach((item) => {
    if (!item) return;
    Object.entries(item).forEach(([key, value]) => { flat[key] = value; });
  });
  const nestedLabel = sources.map((item) => {
    const record = item ?? {};
    const nested = part(record.label);
    const range = text(nested.range) || text(record.labelRange) || text(record.labelAddress);
    return { ...nested, ...(range ? { range, address: firstCell(range) || nested.address } : {}) };
  }).reduce((result, item) => ({ ...result, ...item }), {});
  const nestedValue = sources.map((item) => {
    const record = item ?? {};
    const nested = part(record.value);
    const range = text(nested.range) || text(record.valueRange) || text(record.logicalInputRange)
      || text(record.address) || text(record.range);
    return { ...nested, ...(range ? { range, address: firstCell(range) || nested.address } : {}) };
  }).reduce((result, item) => ({ ...result, ...item }), {});
  const labelRange = text(nestedLabel.range);
  const valueRange = text(nestedValue.range);
  const labelAddress = text(nestedLabel.address) || firstCell(labelRange);
  const valueAddress = text(nestedValue.address) || firstCell(valueRange);
  const label = labelRange || labelAddress ? {
    ...(labelAddress ? { address: labelAddress } : {}),
    ...(labelRange || labelAddress ? { range: labelRange || labelAddress } : {}),
  } : undefined;
  const value = valueRange || valueAddress ? {
    ...(valueAddress ? { address: valueAddress } : {}),
    ...(valueRange || valueAddress ? { range: valueRange || valueAddress } : {}),
  } : undefined;
  const result: CanonicalLocator = {
    ...flat,
    ...(label ? { label } : {}),
    ...(value ? { value } : {}),
    locatorVersion: 1,
    source: source(flat.source, Boolean(label)),
    relation: (text(flat.relation) || (label ? 'ADJACENT' : 'UNRESOLVED')) as LocatorRelation,
    ...(label ? { labelAddress: label.address, labelRange: label.range } : {}),
    ...(value ? {
      address: text(flat.address) || value.address,
      range: value.range,
      valueRange: value.range,
    } : {}),
  };
  return result;
}

export function locatorLabelRange(locator?: Record<string, unknown>) {
  return text((locator?.label as Record<string, unknown> | undefined)?.range)
    || text((locator?.label as Record<string, unknown> | undefined)?.address)
    || text(locator?.labelRange)
    || text(locator?.labelAddress);
}

export function locatorValueRange(locator?: Record<string, unknown>) {
  return text((locator?.value as Record<string, unknown> | undefined)?.range)
    || text((locator?.value as Record<string, unknown> | undefined)?.address)
    || text(locator?.valueRange)
    || text(locator?.logicalInputRange)
    || text(locator?.address)
    || text(locator?.range);
}

/**
 * A structured worksheet field may use one cell for the business label and
 * the next cell for its method/condition (for example `固含 | 120℃×1h`).
 * The method is part of the semantic label, so the visual label range must
 * include the cells between the label and the value range. This is only
 * applied when the ranges are on the same row and the field has a real
 * hierarchical path; no nearby text is guessed for ordinary fields.
 */
export function expandSemanticLabelRange(
  locator: Record<string, unknown> | undefined,
  pathSegments?: string[],
): CanonicalLocator {
  const normalized = mergeLocators(locator);
  if (!pathSegments || pathSegments.filter((segment) => text(segment)).length < 3) {
    return normalized;
  }
  const label = rangeBounds(locatorLabelRange(normalized));
  const value = rangeBounds(locatorValueRange(normalized));
  if (!label || !value || label.startRow !== value.startRow || label.endRow !== value.endRow) {
    return normalized;
  }
  if (value.startColumn <= label.endColumn + 1) return normalized;
  const expanded = `${columnName(label.startColumn)}${label.startRow}:${columnName(value.startColumn - 1)}${label.endRow}`;
  return mergeLocators(normalized, {
    labelAddress: firstCell(expanded),
    labelRange: expanded,
  });
}

export function hasLocatorLabel(locator?: Record<string, unknown>) {
  return Boolean(locatorLabelRange(locator));
}

interface RangeBounds {
  startColumn: number;
  startRow: number;
  endColumn: number;
  endRow: number;
}

function rangeBounds(value: string): RangeBounds | undefined {
  const match = value.replaceAll('$', '').trim().toUpperCase().match(
    /^([A-Z]{1,4})([1-9][0-9]*)(?::([A-Z]{1,4})([1-9][0-9]*))?$/,
  );
  if (!match) return undefined;
  const startColumn = columnNumber(match[1] ?? '');
  const endColumn = columnNumber(match[3] || match[1] || '');
  const startRow = Number(match[2]);
  const endRow = Number(match[4] || match[2]);
  if (!startColumn || !endColumn || !startRow || !endRow) return undefined;
  return {
    startColumn: Math.min(startColumn, endColumn),
    startRow: Math.min(startRow, endRow),
    endColumn: Math.max(startColumn, endColumn),
    endRow: Math.max(startRow, endRow),
  };
}

function columnNumber(value: string) {
  let result = 0;
  for (const character of value) result = result * 26 + character.charCodeAt(0) - 64;
  return result;
}

function columnName(value: number) {
  let current = value;
  let result = '';
  while (current > 0) {
    const remainder = (current - 1) % 26;
    result = String.fromCharCode(65 + remainder) + result;
    current = Math.floor((current - 1) / 26);
  }
  return result;
}
