import type { EditorSelection } from '@/features/template-workspace/types';
import type { DataFieldValueView } from '@/services/data/data-api';

/**
 * Univer may report an absolute address or a one-cell range. The import API
 * stores the canonical A1 address, so compare the two forms consistently.
 */
export function normalizeImportCellAddress(address?: string) {
  const normalized = (address || '').trim().toUpperCase().replace(/\$/g, '');
  if (!normalized) return '';
  const [start, end] = normalized.split(':');
  return start && (!end || start === end) ? start : normalized;
}

export function importFieldForCell(fields: DataFieldValueView[], selection: EditorSelection) {
  const address = normalizeImportCellAddress(selection.address);
  if (!address) return undefined;
  const sheetId = selection.sheetId.trim().toLowerCase();
  const sheetName = selection.sheetName.trim().toLowerCase();
  return fields.find((field) => {
    const sameSheet = (field.sheetId || '').trim().toLowerCase() === sheetId
      || (field.sheetName || '').trim().toLowerCase() === sheetName;
    return sameSheet && normalizeImportCellAddress(field.address) === address;
  });
}

export function importFieldInSheetRow(fields: DataFieldValueView[], selection: EditorSelection, row: number) {
  const sheetId = selection.sheetId.trim().toLowerCase();
  const sheetName = selection.sheetName.trim().toLowerCase();
  return fields.find((field) => {
    const sameSheet = (field.sheetId || '').trim().toLowerCase() === sheetId
      || (field.sheetName || '').trim().toLowerCase() === sheetName;
    return sameSheet && field.rowNumber === row;
  });
}
