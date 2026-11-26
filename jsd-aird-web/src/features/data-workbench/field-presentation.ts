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
  const byBinding = new Map(instance.model.fields.map(field => [field.bindingId, field]));
  const byCode = new Map<string, typeof instance.model.fields[number]>();
  for (const field of instance.model.fields) if (field.fieldCode && !byCode.has(field.fieldCode)) byCode.set(field.fieldCode, field);
  const parents = new Map(instance.mapping.map(binding => [binding.bindingId, binding]));
  const components = new Map(instance.mapping.map(binding => [binding.componentId, binding]));
  const coordinates = new Map<string, ReturnType<typeof parseRange>>();
  const coordinate = (address: string) => {
    if (!coordinates.has(address)) coordinates.set(address, parseRange(address));
    return coordinates.get(address);
  };
  const projections = new Map<string, Array<{ projection: NonNullable<WorkbookTemplateInstance['execution']>['listProjections'][number]; range: ReturnType<typeof parseRange>; sheetId?: unknown }>>();
  for (const projection of instance.execution?.listProjections ?? []) {
    const key = `${projection.domain}.${projection.valueSemantic}`;
    const values = projections.get(key) ?? [];
    values.push({ projection, range: coordinate(projection.valueRange ?? ''), sheetId: (parents.get(projection.parentBindingId ?? '') ?? components.get(projection.componentId))?.locator.sheetId });
    projections.set(key, values);
  }
  const presentation = (bindingId: string, code: string, name: string, sheetId?: string, address?: string) => {
    const field = byBinding.get(bindingId) ?? byCode.get(code);
    const group = field && groups.get(field.groupId);
    if (group) return { groupPath: group, labelPath: `${group} > ${name}` };
    const cell = coordinate(address || '');
    const projection = projections.get(code)?.find(({ range, sheetId: parentSheet }) => cell && range
      && (!sheetId || !parentSheet || sheetId === parentSheet)
      && cell.startRow >= range.startRow && cell.startRow <= range.endRow
      && cell.startColumn >= range.startColumn && cell.startColumn <= range.endColumn)?.projection;
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
    const a = coordinate(left.address || left.sourceRange || ''), b = coordinate(right.address || right.sourceRange || '');
    return a && b ? a.startRow - b.startRow || a.startColumn - b.startColumn : 0;
  };
  fields.sort(compare);
  definitions?.sort(compare);
  return { ...workbook, templateInstance: instance, fields, fieldDefinitions: definitions };
}
