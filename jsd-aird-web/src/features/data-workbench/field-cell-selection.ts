import type { EditorSelection } from '@/features/template-workspace/types';
import type { DataFieldValueView } from '@/services/data/data-api';

export function normalizeCellAddress(address?: string) {
  const normalized = (address || '').trim().toUpperCase().replace(/\$/g, '');
  const [start, end] = normalized.split(':');
  return start && (!end || start === end) ? start : normalized;
}

export function selectionAnchor(address?: string) {
  return normalizeCellAddress(address).split(':')[0] || '';
}

export function fieldMatchesSheet(field: DataFieldValueView, selection: EditorSelection) {
  const id = selection.sheetId.trim().toLowerCase();
  const name = selection.sheetName.trim().toLowerCase();
  return Boolean(id && field.sheetId?.trim().toLowerCase() === id)
    || Boolean(name && field.sheetName?.trim().toLowerCase() === name);
}

export function findFieldForCell(fields: DataFieldValueView[], selection: EditorSelection) {
  const address = selectionAnchor(selection.address);
  return address ? fields.find(field => fieldMatchesSheet(field, selection)
    && normalizeCellAddress(field.address) === address) : undefined;
}
