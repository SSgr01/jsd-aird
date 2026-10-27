import { formatRange, migrateRange, parseRange } from './structure-migration';
import type { WorkbookStructureOperation } from './types';
import type { InstanceSheet, WorkbookCell } from './workbook-instance';

type ItemSection = { range: string; name: string };
export function migratedWorkbookMerges(sheet: InstanceSheet | undefined, operation: WorkbookStructureOperation, after: boolean, sections: ItemSection[] = []) {
  const extended = new Map(sectionMergeExtensions(sheet, operation, after, sections).map(item => [item.anchor, item.range]));
  return (sheet?.mergeData ?? []).flatMap(merge => {
    const anchor = formatRange({ startRow: merge.startRow + 1, endRow: merge.startRow + 1, startColumn: merge.startColumn + 1, endColumn: merge.startColumn + 1 });
    const range = extended.get(anchor) ?? migrateRange(formatRange({ startRow: merge.startRow + 1, endRow: merge.endRow + 1, startColumn: merge.startColumn + 1, endColumn: merge.endColumn + 1 }), operation).value;
    const parsed = range && parseRange(range);
    return parsed && (parsed.startRow !== parsed.endRow || parsed.startColumn !== parsed.endColumn)
      ? [{ startRow: parsed.startRow - 1, endRow: parsed.endRow - 1, startColumn: parsed.startColumn - 1, endColumn: parsed.endColumn - 1 }] : [];
  });
}

/** Keep inserted rows/columns inside the merged section selected by the user.
 * The SDK expands interior merges, but shifts a merge when inserting at its head
 * and leaves a gap when inserting after its tail. Coordinates here are generic. */
export function sectionMergeExtensions(
  sheet: InstanceSheet | undefined,
  operation: WorkbookStructureOperation,
  after: boolean,
  sections: ItemSection[] = [],
): Array<{ range: string; anchor: string; cell?: WorkbookCell }> {
  if (!sheet || !operation.type.startsWith('INSERT') || !operation.index || !operation.count) return [];
  const rows = operation.type === 'INSERT_ROWS';
  const index = operation.index - 1;
  let sourceIndex = after ? index - 1 : index;
  // The item region also owns insertions immediately before a neighbouring subtotal.
  // Use the same configured owner as structural extraction, including legacy totals placed in another visual section.
  for (const section of sections) {
    const range = parseRange(section.range); if (!range || !section.name) continue;
    const first = (rows ? range.startRow : range.startColumn) - 1, last = (rows ? range.endRow : range.endColumn) - 1;
    if (index !== first && index !== last + 1) continue;
    const owner = index === first ? first : last;
    if (sheet.mergeData?.some(merge => {
      const value = sheet.cellData?.[String(merge.startRow)]?.[String(merge.startColumn)]?.v;
      return (rows ? merge.startRow <= owner && merge.endRow >= owner : merge.startColumn <= owner && merge.endColumn >= owner)
        && typeof value === 'string' && value.replace(/\s/g, '') === section.name.replace(/\s/g, '');
    })) sourceIndex = owner;
  }
  return (sheet.mergeData ?? []).flatMap(merge => {
    const start = rows ? merge.startRow : merge.startColumn;
    const end = rows ? merge.endRow : merge.endColumn;
    if (end <= start || sourceIndex < start || sourceIndex > end || index < start || index > end + 1) return [];
    const next = { ...merge, ...(rows ? { endRow: merge.endRow + operation.count! } : { endColumn: merge.endColumn + operation.count! }) };
    return [{
      range: formatRange({ startRow: next.startRow + 1, endRow: next.endRow + 1, startColumn: next.startColumn + 1, endColumn: next.endColumn + 1 }),
      anchor: formatRange({ startRow: next.startRow + 1, endRow: next.startRow + 1, startColumn: next.startColumn + 1, endColumn: next.startColumn + 1 }),
      ...(index === start ? { cell: structuredClone(sheet.cellData?.[String(merge.startRow)]?.[String(merge.startColumn)]) } : {}),
    }];
  });
}
