/** Pure guards used by the live, non-AI field discovery path. */
export function isSingleCellAddress(address: string) {
  const normalized = address.trim().toUpperCase().replace(/\$/g, '');
  if (!normalized) return false;
  const cells = normalized.split(':');
  return cells.length === 1 || cells[0] === cells[1];
}

/**
 * Only a genuine empty-to-text edit may create a new template field. Editing
 * a non-empty bound label is a rename and is handled by onEditorLabel.
 */
export function isNewFieldLabelChange(previousValue: unknown, nextValue: unknown) {
  return isEmptyCellValue(previousValue)
    && stableCellValue(previousValue) !== stableCellValue(nextValue);
}

/**
 * A short text entered in an unbound ordinary cell is treated as a field
 * label. Values, formulas, URLs and inline `label: value` text are not labels.
 */
export function potentialFieldLabel(value: unknown) {
  const raw = typeof value === 'string'
    ? value
    : value && typeof value === 'object'
      ? (() => {
          const record = value as Record<string, unknown>;
          return typeof record.v === 'string'
            ? record.v
            : typeof record.value === 'string' ? record.value : undefined;
        })()
      : undefined;
  if (raw === undefined) return undefined;
  const label = raw.trim();
  if (!label || label.length > 40 || /[\r\n]/.test(label)) return undefined;
  if (/^=/.test(label) || /^https?:\/\//i.test(label)) return undefined;
  if (/^[-+]?\d+(?:\.\d+)?%?$/.test(label)) return undefined;
  if (/^.{1,16}[：:]\s*\S{3,}$/.test(label)) return undefined;
  return label.replace(/[：:]$/, '').trim() || undefined;
}

export function isCellMutationCommand(commandId: string, params?: unknown) {
  const id = (commandId || '').toLowerCase();
  // Univer emits formatting, selection and merge commands through the same
  // command bus. They must not turn an already selected label into a new
  // field. Value/edit commands are intentionally checked before the fallback
  // parameter inspection because command ids differ between Univer builds.
  if (/selection|format|style|border|merge|freeze|scroll|zoom|row-height|column-width|hide|show/.test(id)) {
    return false;
  }
  if ([
    'value',
    'set-range-values',
    'set-cell-value',
    'set-values',
    'set-cell-edit',
    'set-range-data',
    'formula',
    'paste',
    'cut',
    'clear',
    'delete',
    'fill',
    'autofill',
    'cell-data',
    // Univer versions use different command namespaces for direct editing,
    // merged-cell input and paste results. These tokens cover those commands
    // without treating selection/formatting-only commands as value changes.
    'edit-cell-value',
    'update-cell-value',
    'replace-cell-value',
    'edit-cell',
    'cell-edit',
    'input',
    'replace',
    'mutation',
  ].some((token) => id.includes(token))) return true;

  // Some versions expose direct typing as a generic command id while the
  // payload still contains the changed value/range. This fallback makes the
  // editor resilient to that API difference without treating arbitrary
  // commands as cell edits.
  if (!params || typeof params !== 'object') return false;
  const record = params as Record<string, unknown>;
  return ['value', 'values', 'cellValue', 'newValue', 'data'].some((key) => key in record)
    && ('range' in record || 'row' in record || 'startRow' in record);
}

type RangeRecord = Record<string, unknown>;

/**
 * A repeating table or matrix owns its value surface. Text typed in that
 * surface is business data, even when it is short enough to look like a
 * label. Keep automatic discovery limited to the header/label bands and to
 * genuinely unstructured cells.
 */
export function isStructuredDataCell(
  selection: { sheetId: string; address: string },
  bindings: Array<{ mappingKind?: string; role?: string; locator?: RangeRecord }>,
  regions: Array<{ kind?: string; sheetId?: string; range?: string; structures?: RangeRecord }>,
) {
  const cell = rangeBounds(selection.address);
  if (!cell) return false;
  const structuredKinds = new Set([
    'REPEAT_REGION', 'REPEAT_FIELD', 'MATRIX_REGION', 'MATRIX_FIELD',
  ]);
  const contains = (range: unknown) => {
    const bounds = rangeBounds(typeof range === 'string' ? range : '');
    return Boolean(bounds && bounds[0] <= cell[0] && bounds[1] <= cell[1]
      && bounds[2] >= cell[2] && bounds[3] >= cell[3]);
  };
  const isHeader = (locator: RangeRecord) => [
    locator.headerRange, locator.labelRange, locator.labelAddress,
    locator.rowHeaderRange, locator.columnHeaderRange, locator.identityRange,
  ].some(contains);

  for (const binding of bindings) {
    if (!selection.sheetId || !structuredKinds.has(String(binding.mappingKind ?? ''))) continue;
    const locator = binding.locator ?? {};
    if (typeof locator.sheetId !== 'string' || locator.sheetId !== selection.sheetId) continue;
    if (isHeader(locator)) continue;
    if ([locator.dataRange, locator.crossDataRange, locator.valueRange,
      locator.logicalInputRange, locator.recordRange, locator.measureRange].some(contains)) {
      return true;
    }
  }

  for (const region of regions) {
    if (region.sheetId !== selection.sheetId
      || !['ROW_TABLE', 'COLUMN_TABLE', 'MATRIX'].includes(region.kind ?? '')) continue;
    const structures = region.structures ?? {};
    // `region.range` is the whole envelope and may include the header band.
    // Only value/projection ranges suppress live discovery; otherwise a blank
    // template header cell would be incorrectly treated as business data.
    const ranges = [
      structures.dataRange,
      structures.crossDataRange,
      structures.recordProjection && (structures.recordProjection as RangeRecord).sourceRange,
    ];
    const headers = [
      structures.headerRange, structures.rowHeaderRange,
      structures.columnHeaderRange, structures.cornerRange,
    ];
    if (headers.some(contains)) continue;
    if (ranges.some(contains)) return true;
  }
  return false;
}

function rangeBounds(address: string) {
  const parts = address.trim().toUpperCase().replace(/\$/g, '').split(':', 2);
  if (!parts[0]) return undefined;
  const first = cellPosition(parts[0]);
  const last = cellPosition(parts[1] ?? parts[0]);
  if (!first || !last) return undefined;
  return [Math.min(first[0], last[0]), Math.min(first[1], last[1]),
    Math.max(first[0], last[0]), Math.max(first[1], last[1])] as const;
}

function cellPosition(address: string) {
  const match = /^([A-Z]+)([1-9][0-9]*)$/.exec(address);
  if (!match) return undefined;
  let column = 0;
  for (const letter of match[1] ?? '') column = column * 26 + letter.charCodeAt(0) - 64;
  return [column, Number(match[2])] as const;
}

function isEmptyCellValue(value: unknown) {
  if (value === null || value === undefined || value === '') return true;
  if (typeof value !== 'object' || Array.isArray(value)) return false;
  const record = value as Record<string, unknown>;
  return record.v === null || record.v === undefined || record.v === '';
}

function stableCellValue(value: unknown) {
  if (value === null || value === undefined) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean' || typeof value === 'bigint') return `${value}`;
  try {
    return JSON.stringify(value) ?? '';
  } catch {
    return Object.prototype.toString.call(value);
  }
}
