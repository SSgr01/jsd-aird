import { generateUUID } from '@/utils/uuid';

import type { FieldModel, TemplateBinding, WorkbookStructureOperation } from './types';

const RANGE_KEYS = [
  'address',
  'range',
  'labelAddress',
  'labelAnchor',
  'labelRange',
  'anchorAddress',
  'anchorRange',
  'logicalInputRange',
  'headerRange',
  'dataRange',
  'valueRange',
  'valueAddress',
  'valueAnchor',
  'totalRange',
  'identityRange',
  'recordRange',
  'parentRange',
  'sourceRange',
] as const;

export function operationFromUniverCommand(command: {
  id: string;
  params?: object;
}): WorkbookStructureOperation | undefined {
  const params = asRecord(command.params);
  if (command.id === 'sheet.command.set-worksheet-name' && text(params.subUnitId) && text(params.name)) return {
    operationId: generateUUID(), type: 'RENAME_SHEET', sheetId: text(params.subUnitId),
    previousSheetName: text(params.oldName), nextSheetName: text(params.name), source: 'CUSTOMER',
  };
  const range = asRecord(params.range);
  const sheetId = text(params.subUnitId);
  if (!sheetId || !validCommandRange(range)) return undefined;
  const rowCount = number(range.endRow) - number(range.startRow) + 1;
  const columnCount = number(range.endColumn) - number(range.startColumn) + 1;
  const base = {
    operationId: generateUUID(),
    sheetId,
    source: 'CUSTOMER' as const,
  };
  if (command.id === 'sheet.command.insert-row') {
    return {
      ...base,
      type: 'INSERT_ROWS',
      index: number(range.startRow) + 1,
      count: rowCount,
    };
  }
  if (['sheet.command.remove-row', 'sheet.command.remove-row-by-range'].includes(command.id)) {
    return { ...base, type: 'DELETE_ROWS', index: number(range.startRow) + 1, count: rowCount };
  }
  if (command.id === 'sheet.command.insert-col') {
    return {
      ...base,
      type: 'INSERT_COLUMNS',
      index: number(range.startColumn) + 1,
      count: columnCount,
    };
  }
  if (['sheet.command.remove-col', 'sheet.command.remove-col-by-range'].includes(command.id)) {
    return {
      ...base,
      type: 'DELETE_COLUMNS',
      index: number(range.startColumn) + 1,
      count: columnCount,
    };
  }
  return undefined;
}

export function migrateWorkspaceStructure(
  mapping: TemplateBinding[],
  model: FieldModel,
  operation: WorkbookStructureOperation,
) {
  const statuses = new Map<string, TemplateBinding['bindingStatus']>();
  const nextMapping = mapping.map((binding) => {
    if (text(binding.locator.sheetId) !== operation.sheetId) return binding;
    const migrated = migrateLocator(binding.locator, operation, binding);
    const bindingStatus = migrated.removed
      ? ('MISSING' as const)
      : migrated.changed && binding.bindingStatus !== 'MISSING'
        ? ('VALID' as const)
        : binding.bindingStatus;
    statuses.set(binding.bindingId, bindingStatus);
    return {
      ...binding,
      locator: migrated.locator,
      bindingStatus,
      diagnostic: {
        ...binding.diagnostic,
        ...(migrated.removed
          ? {
              relocationReason: 'BOUND_RANGE_REMOVED',
              lastKnownLocator: structuredClone(binding.locator),
            }
          : {}),
      },
    };
  });
  const nextModel: FieldModel = {
    ...structuredClone(model),
    fields: model.fields.map((field) => {
      const candidate = field.candidateLocator
        ? migrateLocator(field.candidateLocator, operation)
        : undefined;
      const status = field.bindingId ? statuses.get(field.bindingId) : undefined;
      const binding = field.bindingId
        ? nextMapping.find((item) => item.bindingId === field.bindingId)
        : undefined;
      return {
        ...field,
        ...(binding
          ? {
              locator: structuredClone(binding.locator),
              valueRange: text(binding.locator.valueRange) || undefined,
              labelRange: text(binding.locator.labelRange) || undefined,
              columns: field.columns?.map((column) => {
                const child = nextMapping.find((item) => item.bindingId === column.bindingId);
                return child
                  ? {
                      ...column,
                      valueRange: text(child.locator.valueRange) || undefined,
                      labelRange: text(child.locator.labelRange) || undefined,
                    }
                  : column;
              }),
            }
          : {}),
        ...(candidate ? { candidateLocator: candidate.locator } : {}),
        ...(status === 'MISSING' ? { reviewStatus: 'ISSUE' as const } : {}),
      };
    }),
    blocks: model.blocks.map((block) => {
      if (block.sheetId !== operation.sheetId) return block;
      const migrated = migrateRange(block.range, operation);
      return migrated.value ? { ...block, range: migrated.value } : block;
    }),
    semanticAnnotations: model.semanticAnnotations.map((annotation) => {
      if (text(annotation.sheetId) !== operation.sheetId) return annotation;
      const migrated = migrateRange(text(annotation.range), operation);
      return migrated.value ? { ...annotation, range: migrated.value } : annotation;
    }),
    staticRegions: (model.staticRegions ?? []).flatMap((region) => {
      if (region.sheetId !== operation.sheetId) return [region];
      const migrated = migrateRange(region.address, operation);
      return migrated.value ? [{ ...region, address: migrated.value }] : [];
    }),
  };
  return { mapping: nextMapping, model: nextModel };
}

function migrateLocator(
  locator: Record<string, unknown>,
  operation: WorkbookStructureOperation,
  binding?: TemplateBinding,
) {
  const next = structuredClone(locator);
  let changed = false;
  let removed = false;
  if (operation.type === 'RENAME_SHEET') {
    if (operation.nextSheetName) next.sheetName = operation.nextSheetName;
    return { locator: next, changed: Boolean(operation.nextSheetName), removed: false };
  }
  for (const key of RANGE_KEYS) {
    const current = text(next[key]);
    if (!current) continue;
    const migrated = migrateRange(current, operation);
    if (migrated.removed) {
      delete next[key];
      removed ||= ['address', 'range', 'anchorAddress', 'logicalInputRange', 'dataRange'].includes(
        key,
      );
      changed = true;
    } else if (migrated.value && migrated.value !== current) {
      next[key] = migrated.value;
      changed = true;
    }
  }
  for (const key of ['tableModel', 'label', 'value', 'anchor']) {
    if (isObjectRecord(next[key])) {
      next[key] = migrateStructuredRecord(asRecord(next[key]), operation);
    }
  }
  if (
    !removed &&
    binding?.mappingKind &&
    ['REPEAT_REGION', 'REPEAT_FIELD'].includes(binding.mappingKind)
  ) {
    const axis = binding.repeatAxis === 'COLUMN' ? 'COLUMN' : 'ROW';
    const expanded = expandRepeatTail(next, operation, axis);
    changed ||= expanded;
    // Legacy locators retain nested value/label aliases; the server normalizer
    // consumes these too, so they must be in the same structure transaction.
    if (isObjectRecord(next.value)) {
      const value = asRecord(next.value);
      const range = text(next.valueRange) || text(next.logicalInputRange) || text(next.address);
      if (range) value.range = range;
      const address = text(next.anchorAddress) || range.split(':')[0];
      if (address) value.address = address;
      next.value = value;
    }
  }
  return { locator: next, changed, removed };
}

function migrateStructuredRecord(
  source: Record<string, unknown>,
  operation: WorkbookStructureOperation,
): Record<string, unknown> {
  const next = structuredClone(source);
  for (const [key, value] of Object.entries(next)) {
    if (RANGE_KEYS.includes(key as (typeof RANGE_KEYS)[number]) && typeof value === 'string') {
      const migrated = migrateRange(value, operation);
      if (migrated.value) next[key] = migrated.value;
      else if (migrated.removed) delete next[key];
      continue;
    }
    if (Array.isArray(value)) {
      const values = unknownArray(value);
      next[key] = values.map((item) =>
        isObjectRecord(item) ? migrateStructuredRecord(asRecord(item), operation) : item,
      );
    } else if (isObjectRecord(value)) {
      next[key] = migrateStructuredRecord(asRecord(value), operation);
    }
  }
  return next;
}

function expandRepeatTail(
  locator: Record<string, unknown>,
  operation: WorkbookStructureOperation,
  axis: 'ROW' | 'COLUMN',
) {
  const isRows = axis === 'ROW';
  const isInsert = operation.type === (isRows ? 'INSERT_ROWS' : 'INSERT_COLUMNS');
  if (!isInsert || !operation.index || !operation.count) return false;
  let changed = false;
  for (const key of ['dataRange', 'valueRange', 'address', 'range', 'logicalInputRange']) {
    const current = text(locator[key]);
    const parsed = parseRange(current);
    if (!parsed) continue;
    const end = isRows ? parsed.endRow : parsed.endColumn;
    if (operation.index !== end + 1) continue;
    const next = isRows
      ? formatRange({ ...parsed, endRow: parsed.endRow + operation.count })
      : formatRange({ ...parsed, endColumn: parsed.endColumn + operation.count });
    if (next !== current) {
      locator[key] = next;
      changed = true;
    }
  }
  return changed;
}

export function migrateRange(value: string, operation: WorkbookStructureOperation) {
  const parsed = parseRange(value);
  if (!parsed || !operation.index || !operation.count) return { value, removed: false };
  const rows = operation.type.endsWith('ROWS');
  const insert = operation.type.startsWith('INSERT');
  const start = rows ? parsed.startRow : parsed.startColumn;
  const end = rows ? parsed.endRow : parsed.endColumn;
  const migrated = insert
    ? insertInterval(start, end, operation.index, operation.count)
    : deleteInterval(start, end, operation.index, operation.count);
  if (!migrated) return { value: '', removed: true };
  const next = rows
    ? { ...parsed, startRow: migrated.start, endRow: migrated.end }
    : { ...parsed, startColumn: migrated.start, endColumn: migrated.end };
  return { value: formatRange(next), removed: false };
}

function insertInterval(start: number, end: number, index: number, count: number) {
  if (end < index) return { start, end };
  if (start >= index) return { start: start + count, end: end + count };
  return { start, end: end + count };
}

function deleteInterval(start: number, end: number, index: number, count: number) {
  const deletionEnd = index + count - 1;
  if (end < index) return { start, end };
  if (start > deletionEnd) return { start: start - count, end: end - count };
  if (start >= index && end <= deletionEnd) return undefined;
  const nextStart = start >= index ? index : start;
  const nextEnd = end > deletionEnd ? end - count : index - 1;
  return nextEnd < nextStart ? undefined : { start: nextStart, end: nextEnd };
}

export interface ParsedRange {
  startRow: number;
  endRow: number;
  startColumn: number;
  endColumn: number;
}

export function parseRange(value: string): ParsedRange | undefined {
  const match = /^([A-Z]{1,4})([1-9][0-9]*)(?::([A-Z]{1,4})([1-9][0-9]*))?$/i.exec(value);
  if (!match) return undefined;
  return {
    startColumn: columnNumber(match[1] ?? ''),
    startRow: Number(match[2]),
    endColumn: columnNumber(match[3] ?? match[1] ?? ''),
    endRow: Number(match[4] ?? match[2]),
  };
}

export function formatRange(range: ParsedRange) {
  const start = `${columnLetters(range.startColumn)}${range.startRow}`;
  const end = `${columnLetters(range.endColumn)}${range.endRow}`;
  return start === end ? start : `${start}:${end}`;
}

function columnNumber(value: string) {
  return [...value.toUpperCase()].reduce(
    (result, letter) => result * 26 + letter.charCodeAt(0) - 64,
    0,
  );
}

function columnLetters(column: number) {
  let value = column;
  let result = '';
  while (value > 0) {
    value -= 1;
    result = String.fromCharCode(65 + (value % 26)) + result;
    value = Math.floor(value / 26);
  }
  return result;
}

function validCommandRange(range: Record<string, unknown>) {
  return ['startRow', 'endRow', 'startColumn', 'endColumn'].every((key) =>
    Number.isInteger(range[key]),
  );
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

function isObjectRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function unknownArray(value: unknown[]): unknown[] {
  return value;
}

function text(value: unknown) {
  return typeof value === 'string' ? value : '';
}

function number(value: unknown) {
  return typeof value === 'number' ? value : 0;
}
