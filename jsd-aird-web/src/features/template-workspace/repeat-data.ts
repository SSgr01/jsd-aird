export interface RepeatDisplayColumn {
  key: string;
  name: string;
  values: unknown[];
  sequence?: boolean;
}

export interface RepeatDisplayRow {
  key: string;
  index: number;
  populated: boolean;
  values: Record<string, unknown>;
}

export interface RepeatDisplayModel {
  rows: RepeatDisplayRow[];
  totalRows: number;
  filledRows: number;
}

export function buildRepeatDisplay(
  columns: RepeatDisplayColumn[],
  options: { maxRows?: number; expanded?: boolean } = {},
): RepeatDisplayModel {
  const totalRows = Math.max(
    options.maxRows || 0,
    ...columns.map((column) => column.values.length),
    0,
  );
  const contentColumns = columns.filter((column) => !column.sequence);
  const populatedColumns = contentColumns.length ? contentColumns : columns;
  const populated = Array.from({ length: totalRows }, (_, index) =>
    populatedColumns.some((column) => isMeaningfulValue(column.values[index])),
  );
  const filledRows = populated.filter(Boolean).length;
  let lastPopulated = -1;
  for (let index = populated.length - 1; index >= 0; index -= 1) {
    if (populated[index]) {
      lastPopulated = index;
      break;
    }
  }
  const visibleRows = options.expanded
    ? totalRows
    : Math.max(0, lastPopulated + 1);

  return {
    totalRows,
    filledRows,
    rows: Array.from({ length: visibleRows }, (_, index) => ({
      key: String(index),
      index: index + 1,
      populated: populated[index] || false,
      values: Object.fromEntries(
        columns.map((column) => [column.key, column.values[index] ?? null]),
      ),
    })),
  };
}

export function isMeaningfulValue(value: unknown): boolean {
  if (value === undefined || value === null) return false;
  if (typeof value === 'string') return value.trim() !== '';
  if (typeof value === 'number' || typeof value === 'boolean' || typeof value === 'bigint') {
    return true;
  }
  if (Array.isArray(value)) return value.some(isMeaningfulValue);
  if (typeof value === 'object') return Object.values(value as Record<string, unknown>).some(isMeaningfulValue);
  return false;
}

export function displayCellValue(value: unknown, fallback = '—'): string {
  if (!isMeaningfulValue(value)) return fallback;
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean' || typeof value === 'bigint') {
    return String(value);
  }
  if (Array.isArray(value)) return `${value.length} 项`;
  return '已填写';
}
