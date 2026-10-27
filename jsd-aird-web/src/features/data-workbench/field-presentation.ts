import type { DataWorkbookSnapshot } from '@/services/data/data-api';
import { projectionGroup, projectionName, projectionUnit } from '@/features/template-workspace/list-projection';
import { parseRange } from '@/features/template-workspace/structure-migration';
import type { WorkbookTemplateInstance } from '@/features/template-workspace/workbook-instance';

/** Use the current field model's display names without changing imported values or identities. */
export function presentWorkbookFields(
  workbook: DataWorkbookSnapshot,
  instance?: WorkbookTemplateInstance,
): DataWorkbookSnapshot {
  if (!instance) return workbook;
  const groups = new Map(instance.model.groups.map((group) => [group.id, group.name]));
  const order = new Map(instance.model.groups.map((group, index) => [group.name, group.order ?? index]));
  const presentation = (bindingId: string, code: string, name: string, sheetId?: string, address?: string) => {
    const field = instance.model.fields.find((item) => item.bindingId === bindingId)
      ?? instance.model.fields.find((item) => item.fieldCode === code);
    const group = field && groups.get(field.groupId);
    if (group) return { groupPath: group, labelPath: `${group} > ${name}` };
    const cell = parseRange(address || '');
    const projection = instance.execution?.listProjections.find((item) => {
      if (code !== `${item.domain}.${item.valueSemantic}` || !cell) return false;
      const parent = instance.mapping.find((binding) => binding.bindingId === item.parentBindingId
        || binding.componentId === item.componentId);
      if (sheetId && parent?.locator.sheetId && sheetId !== parent.locator.sheetId) return false;
      const range = parseRange(item.valueRange);
      return range && cell.startRow >= range.startRow && cell.startRow <= range.endRow
        && cell.startColumn >= range.startColumn && cell.startColumn <= range.endColumn;
    });
    if (!projection) return {};
    const groupName = projectionGroup(projection, instance.model)?.name ?? projectionName(projection, instance.model);
    return { groupPath: groupName, labelPath: `${groupName} > ${name}`, unit: projectionUnit(projection, instance.model) };
  };
  const fields = workbook.fields.map((field) => ({ ...field,
    ...presentation(field.bindingId, field.fieldCode, field.fieldName, field.sheetId, field.address),
  }));
  const definitions = workbook.fieldDefinitions?.map((field) => ({ ...field,
    ...presentation(field.bindingId, field.fieldCode, field.displayName, field.sheetId, field.sourceRange),
  }));
  const compare = (left: { groupPath?: string; address?: string; sourceRange?: string }, right: { groupPath?: string; address?: string; sourceRange?: string }) => {
    const groupOrder = (order.get(left.groupPath || '') ?? Number.MAX_SAFE_INTEGER)
      - (order.get(right.groupPath || '') ?? Number.MAX_SAFE_INTEGER);
    if (groupOrder) return groupOrder;
    const a = parseRange(left.address || left.sourceRange || ''), b = parseRange(right.address || right.sourceRange || '');
    return a && b ? a.startRow - b.startRow || a.startColumn - b.startColumn : 0;
  };
  fields.sort(compare);
  definitions?.sort(compare);
  return { ...workbook, templateInstance: instance, fields, fieldDefinitions: definitions };
}
