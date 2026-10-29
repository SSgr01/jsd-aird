import type { BusinessField, FieldModel, TemplateBinding } from './types';

/** Keep display paths and parent column summaries in step with the actual definition. */
export function updateFieldDefinition(model: FieldModel, mapping: TemplateBinding[], id: string, changes: Partial<BusinessField>) {
  const old = model.fields.find(field => field.id === id);
  if (!old) return { model, mapping };
  const field = { ...old, ...changes };
  if (changes.name?.trim()) field.labelTextMissing = false;
  const path = [...(changes.pathSegments ?? (old.pathSegments?.length ? old.pathSegments : [old.name]))];
  if (changes.groupId !== undefined && path.length > 1) path[0] = model.groups.find(group => group.id === field.groupId)?.name ?? path[0]!;
  if (changes.name !== undefined) path[path.length - 1] = field.name;
  field.pathSegments = path.filter(Boolean);
  const attributes = (['name', 'unit', 'valueType'] as const).filter(key => Object.hasOwn(changes, key));
  if (attributes.length) field.manualOverrides = [...new Set([...(old.manualOverrides ?? []), ...attributes])];
  const fields = model.fields.map(item => item.id === id ? field : { ...item,
    columns: item.columns?.map(column => Boolean(field.bindingId && column.bindingId === field.bindingId) || column.fieldId === id
      ? { ...column, name: field.name, valueType: field.valueType, unit: field.unit, required: field.required, experimentField: field.experimentField }
      : column),
  });
  return { model: { ...model, fields }, mapping: mapping.map(binding => binding.bindingId !== field.bindingId ? binding : {
    ...binding, fieldCode: field.fieldCode, labelPath: (field.pathSegments ?? []).join(' > '), experimentField: field.experimentField,
    locator: { ...binding.locator, unit: field.unit, valueType: field.valueType, displayName: field.name },
    diagnostic: { ...binding.diagnostic, displayName: field.name, groupName: model.groups.find(group => group.id === field.groupId)?.name,
      unit: field.unit, valueType: field.valueType, manualOverrides: field.manualOverrides },
  }) };
}

/** A region owns its descendants, including bindings without a visible field node. */
export function removeFieldDefinitions(model: FieldModel, mapping: TemplateBinding[], ids: Iterable<string>) {
  const fieldIds = new Set(ids), bindingIds = new Set<string>();
  let changed = true;
  while (changed) {
    const size = fieldIds.size + bindingIds.size;
    for (const field of model.fields) if (fieldIds.has(field.id) || field.parentFieldId && fieldIds.has(field.parentFieldId) || field.bindingId && bindingIds.has(field.bindingId)) {
      fieldIds.add(field.id); if (field.bindingId) bindingIds.add(field.bindingId);
    }
    for (const binding of mapping) if (binding.parentBindingId && bindingIds.has(binding.parentBindingId)) bindingIds.add(binding.bindingId);
    changed = size !== fieldIds.size + bindingIds.size;
  }
  return { fieldIds, bindingIds,
    model: { ...model, fields: model.fields.filter(field => !fieldIds.has(field.id)).map(field => ({ ...field,
      columns: field.columns?.filter(column => !(column.fieldId && fieldIds.has(column.fieldId)) && !(column.bindingId && bindingIds.has(column.bindingId))),
    })) },
    mapping: mapping.filter(binding => !bindingIds.has(binding.bindingId)),
  };
}
