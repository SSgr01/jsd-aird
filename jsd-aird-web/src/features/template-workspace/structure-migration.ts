import type { FieldModel, TemplateBinding, WorkbookStructureOperation } from './types';

const RANGE_KEYS = [
  'address',
  'range',
  'labelAddress',
  'labelRange',
  'anchorAddress',
  'anchorRange',
  'logicalInputRange',
  'headerRange',
  'dataRange',
  'valueRange',
  'totalRange',
  'rowHeaderRange',
  'columnHeaderRange',
] as const;

export function operationFromUniverCommand(command: {
  id: string;
  params?: object;
}): WorkbookStructureOperation | undefined {
  const params = asRecord(command.params);
  const range = asRecord(params.range);
  const sheetId = text(params.subUnitId);
  if (!sheetId || !validCommandRange(range)) return undefined;
  const rowCount = number(range.endRow) - number(range.startRow) + 1;
  const columnCount = number(range.endColumn) - number(range.startColumn) + 1;
  const base = {
    operationId: crypto.randomUUID(),
    sheetId,
    source: 'CUSTOMER' as const,
  };
  if (command.id === 'sheet.command.insert-row') {
    const after = number(params.direction) === 2;
    return {
      ...base,
      type: 'INSERT_ROWS',
      index: (after ? number(range.endRow) + 1 : number(range.startRow)) + 1,
      count: rowCount,
    };
  }
  if (command.id === 'sheet.command.remove-row') {
    return { ...base, type: 'DELETE_ROWS', index: number(range.startRow) + 1, count: rowCount };
  }
  if (command.id === 'sheet.command.insert-col') {
    const after = number(params.direction) === 1;
    return {
      ...base,
      type: 'INSERT_COLUMNS',
      index: (after ? number(range.endColumn) + 1 : number(range.startColumn)) + 1,
      count: columnCount,
    };
  }
  if (command.id === 'sheet.command.remove-col') {
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
      return {
        ...field,
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
  if (
    !removed &&
    binding?.mappingKind &&
    ['REPEAT_REGION', 'REPEAT_FIELD'].includes(binding.mappingKind)
  ) {
    const axis = binding.repeatAxis === 'COLUMN' ? 'COLUMN' : 'ROW';
    const expanded = expandRepeatTail(next, operation, axis);
    changed ||= expanded;
  }
  return { locator: next, changed, removed };
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

function migrateRange(value: string, operation: WorkbookStructureOperation) {
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

interface ParsedRange {
  startRow: number;
  endRow: number;
  startColumn: number;
  endColumn: number;
}

function parseRange(value: string): ParsedRange | undefined {
  const match = /^([A-Z]{1,4})([1-9][0-9]*)(?::([A-Z]{1,4})([1-9][0-9]*))?$/i.exec(value);
  if (!match) return undefined;
  return {
    startColumn: columnNumber(match[1] ?? ''),
    startRow: Number(match[2]),
    endColumn: columnNumber(match[3] ?? match[1] ?? ''),
    endRow: Number(match[4] ?? match[2]),
  };
}

function formatRange(range: ParsedRange) {
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

function text(value: unknown) {
  return typeof value === 'string' ? value : '';
}

function number(value: unknown) {
  return typeof value === 'number' ? value : 0;
}
