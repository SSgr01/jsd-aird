import { projectionFieldViews } from './list-projection';
import { mergeLocators } from './locator';
import { resolveBindingSelection } from './selection-resolver';
import { formatRange, parseRange } from './structure-migration';
import type { EditorSelection, TemplateBinding } from './types';
import type { WorkbookTemplateInstance } from './workbook-instance';

/** Reveal an entire anchor cell with minimal movement; frozen axes never scroll. */
export function focusScrollDelta(
  cell: { startX: number; endX: number; startY: number; endY: number },
  viewport: { left: number; right: number; top: number; bottom: number },
  frozen = { x: false, y: false },
) {
  const axis = (start: number, end: number, visibleStart: number, visibleEnd: number) => {
    if (![start, end, visibleStart, visibleEnd].every(Number.isFinite)
      || visibleEnd <= visibleStart) return 0;
    // An oversized merge cannot fit. Keep its beginning visible without jumping to its end.
    if (end - start > visibleEnd - visibleStart)
      return start < visibleStart || start >= visibleEnd ? start - visibleStart : 0;
    // Leave room for the highlight stroke and the canvas scrollbar overlay.
    const padding = Math.min(8, visibleEnd - visibleStart - (end - start));
    if (start < visibleStart) return start - visibleStart - padding;
    if (end > visibleEnd) return end - visibleEnd + padding;
    return 0;
  };
  return {
    offsetX: frozen.x ? 0 : axis(cell.startX, cell.endX, viewport.left, viewport.right),
    offsetY: frozen.y ? 0 : axis(cell.startY, cell.endY, viewport.top, viewport.bottom),
  };
}

/** A source-location click focuses this record's cells, with its configured field/header context. */
export function sourceFocusBinding(
  bindings: TemplateBinding[],
  selection: EditorSelection,
  instance?: WorkbookTemplateInstance,
): TemplateBinding | undefined {
  const target = parseRange(selection.address.replaceAll('$', ''));
  if (!target || target.startRow > target.endRow || target.startColumn > target.endColumn) return undefined;
  const address = formatRange(target);
  const projections = instance?.execution
    ? projectionFieldViews(instance.execution.listProjections, instance.model, instance.mapping)
        .map((view) => view.binding)
    : [];
  const field = resolveBindingSelection(
    [...bindings, ...projections].filter((binding) => binding.role !== 'REPEAT_REGION'
      && binding.mappingKind !== 'REPEAT_REGION')
      .map((binding) => ({ ...binding, locator: mergeLocators(binding.locator) })),
    { ...selection, address },
  )?.binding;
  const locator = field?.locator ?? {};
  const sliceHeader = (property: 'rowHeaderRange' | 'columnHeaderRange') => {
    const header = parseRange(typeof locator[property] === 'string' ? locator[property] : '');
    if (!header) return undefined;
    if (property === 'rowHeaderRange' && target.startRow >= header.startRow && target.endRow <= header.endRow)
      return formatRange({ ...header, startRow: target.startRow, endRow: target.endRow });
    if (property === 'columnHeaderRange' && target.startColumn >= header.startColumn && target.endColumn <= header.endColumn)
      return formatRange({ ...header, startColumn: target.startColumn, endColumn: target.endColumn });
    return formatRange(header);
  };
  return {
    ...field,
    bindingId: field?.bindingId ?? `source:${selection.sheetId}:${address}`,
    dataPath: field?.dataPath ?? '',
    role: 'FIELD',
    mappingKind: field?.mappingKind === 'REPEAT_FIELD' ? 'REPEAT_FIELD' : 'SCALAR',
    locatorType: 'CELL_RANGE',
    syncDirection: field?.syncDirection ?? 'EDITOR_TO_DATA',
    primaryBinding: false,
    bindingStatus: 'VALID',
    locator: {
      ...locator,
      sheetId: selection.sheetId,
      sheetName: selection.sheetName,
      address, range: address, valueRange: address, logicalInputRange: address,
      anchorAddress: address, anchorRange: address, dataRange: address,
      // Replace canonical aliases too; a parent range must never override this sample's cell.
      value: { address: address.split(':')[0], range: address },
      rowHeaderRange: sliceHeader('rowHeaderRange'),
      columnHeaderRange: sliceHeader('columnHeaderRange'),
    },
  };
}
