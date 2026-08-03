import type { EditorSelection, TemplateBinding } from './types';

export interface BindingSelectionMatch {
  binding: TemplateBinding;
  hitKind: 'LABEL' | 'VALUE';
  candidateIndex: number;
  candidateCount: number;
}

interface CandidateMatch {
  binding: TemplateBinding;
  hitKind: 'LABEL' | 'VALUE';
  area: number;
  order: number;
}

export function resolveBindingSelection(
  bindings: TemplateBinding[],
  selection: EditorSelection,
  cycleIndex = 0,
): BindingSelectionMatch | undefined {
  const selectedCell = selection.address.split(':')[0] ?? selection.address;
  const matches: CandidateMatch[] = [];
  bindings.forEach((binding, order) => {
    if (!sameSheet(binding, selection)) return;
    const labelRanges = [binding.locator.labelRange, binding.locator.labelAddress]
      .map(stringValue).filter(Boolean);
    const valueRanges = [
      binding.locator.logicalInputRange,
      binding.locator.dataRange,
      binding.locator.address,
      binding.locator.range,
      binding.locator.anchorAddress,
      binding.locator.rowHeaderRange,
      binding.locator.columnHeaderRange,
    ]
      .map(stringValue).filter(Boolean);
    if (labelRanges.some((range) => cellInRange(selectedCell, range))) {
      matches.push({ binding, hitKind: 'LABEL', area: smallestArea(labelRanges), order });
      return;
    }
    if (valueRanges.some((range) => cellInRange(selectedCell, range))) {
      matches.push({ binding, hitKind: 'VALUE', area: smallestArea(valueRanges), order });
    }
  });
  matches.sort((left, right) => {
    const kind = Number(left.hitKind === 'VALUE') - Number(right.hitKind === 'VALUE');
    return kind || left.area - right.area || left.order - right.order;
  });
  if (!matches.length) return undefined;
  const index = ((cycleIndex % matches.length) + matches.length) % matches.length;
  const selected = matches[index];
  if (!selected) return undefined;
  return {
    binding: selected.binding,
    hitKind: selected.hitKind,
    candidateIndex: index,
    candidateCount: matches.length,
  };
}

export function selectionCycleKey(selection: EditorSelection) {
  return `${selection.sheetId}|${selection.sheetName}|${selection.address.split(':')[0] ?? selection.address}`;
}

function sameSheet(binding: TemplateBinding, selection: EditorSelection) {
  const sheetId = stringValue(binding.locator.sheetId);
  const sheetName = stringValue(binding.locator.sheetName) || stringValue(binding.locator.sheetCode);
  if (sheetId) return sheetId === selection.sheetId;
  if (sheetName) return sheetName === selection.sheetName;
  return true;
}

function smallestArea(ranges: string[]) {
  return Math.min(...ranges.map(rangeArea));
}

function rangeArea(range: string) {
  const [startValue, endValue = startValue] = range.replaceAll('$', '').split(':');
  const start = parseCell(startValue ?? '');
  const end = parseCell(endValue ?? '');
  if (!start || !end) return Number.MAX_SAFE_INTEGER;
  return (Math.abs(end.row - start.row) + 1) * (Math.abs(end.column - start.column) + 1);
}

function cellInRange(cell: string, range: string) {
  const point = parseCell(cell);
  const [startValue, endValue = startValue] = range.replaceAll('$', '').split(':');
  const start = parseCell(startValue ?? '');
  const end = parseCell(endValue ?? '');
  if (!point || !start || !end) return false;
  return point.row >= Math.min(start.row, end.row) && point.row <= Math.max(start.row, end.row)
    && point.column >= Math.min(start.column, end.column)
    && point.column <= Math.max(start.column, end.column);
}

function parseCell(value: string) {
  const match = /^([A-Z]+)([1-9][0-9]*)$/i.exec(value);
  if (!match?.[1] || !match[2]) return undefined;
  let column = 0;
  for (const character of match[1].toUpperCase()) column = column * 26 + character.charCodeAt(0) - 64;
  return { column, row: Number(match[2]) };
}

function stringValue(value: unknown) {
  return typeof value === 'string' ? value.trim() : '';
}
