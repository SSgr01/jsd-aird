import { readFieldModel } from './field-model';
import { parseRange } from './structure-migration';
import { locatorValueRange } from './locator';
import { readWorkbookInstance, type WorkbookTemplateInstance } from './workbook-instance';

/** Same normalization as the template workspace, with instance identities and extensions intact. */
export function normalizeInstanceDefinition(instance: WorkbookTemplateInstance, snapshot: Record<string, unknown>) {
  const model = readFieldModel({ 'x-jsd-field-model': instance.model }, instance.mapping, snapshot);
  const bindings = new Map(instance.mapping.map(binding => [binding.bindingId, binding]));
  const ranges = new Map(model.fields.map(field => [field.id, parseRange(locatorValueRange(field.locator))]));
  for (const field of model.fields) {
    const binding = bindings.get(field.bindingId ?? '');
    if (binding?.mappingKind === 'REPEAT_REGION' &&
      (field.id === `region:${binding.bindingId}` || (field.id === 'comparison' && binding.dataPath === '/sourceGroups')))
      field.structuralOnly = true;
  }
  model.fields.sort((a, b) => {
    const left = ranges.get(a.id), right = ranges.get(b.id);
    return left && right ? left.startRow - right.startRow || left.startColumn - right.startColumn : 0;
  });
  return { ...instance, model };
}

export function resolveInstanceDefinition(snapshot: Record<string, unknown>, verified?: WorkbookTemplateInstance) {
  const instance = readWorkbookInstance(snapshot) ?? verified;
  return instance?.version === 2 ? normalizeInstanceDefinition(instance, snapshot) : undefined;
}
