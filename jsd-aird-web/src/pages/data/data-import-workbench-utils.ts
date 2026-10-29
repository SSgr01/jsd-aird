import type { EditorSelection } from '@/features/template-workspace/types';
import type { DataFieldValueView, DataMapping } from '@/services/data/data-api';
import { fieldMatchesSheet, findFieldForCell, normalizeCellAddress } from '@/features/data-workbench/field-cell-selection';

/** A deliberate IGNORE is resolved, not a pending mapping. */
export function isPendingImportMapping(mapping: Pick<DataMapping, 'action' | 'fieldCode'>) {
  return mapping.action !== 'IGNORE' && (mapping.action !== 'MAP' || !mapping.fieldCode);
}

/**
 * Univer may report an absolute address or a one-cell range. The import API
 * stores the canonical A1 address, so compare the two forms consistently.
 */
export function normalizeImportCellAddress(address?: string) {
  return normalizeCellAddress(address);
}

export function importFieldForCell(fields: DataFieldValueView[], selection: EditorSelection) {
  return findFieldForCell(fields, selection);
}

export function importFieldInSheetRow(fields: DataFieldValueView[], selection: EditorSelection, row: number) {
  return fields.find((field) => fieldMatchesSheet(field, selection) && field.rowNumber === row);
}
