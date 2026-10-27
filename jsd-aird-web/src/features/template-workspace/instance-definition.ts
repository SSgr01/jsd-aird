import { readFieldModel } from './field-model';
import { parseRange } from './structure-migration';
import { locatorValueRange } from './locator';
import { readWorkbookInstance, type WorkbookTemplateInstance } from './workbook-instance';

/** Same normalization as the template workspace, with instance identities and extensions intact. */
export function normalizeInstanceDefinition(instance: WorkbookTemplateInstance, snapshot: Record<string, unknown>) {
  const model = readFieldModel({ 'x-jsd-field-model': instance.model }, instance.mapping, snapshot);
  for (const field of model.fields) {
    const binding = instance.mapping.find((item) => item.bindingId === field.bindingId);
    if (binding?.mappingKind === 'REPEAT_REGION' &&
      (field.id === `region:${binding.bindingId}` || (field.id === 'comparison' && binding.dataPath === '/sourceGroups')))
      field.structuralOnly = true;
  }
  model.fields.sort((a, b) => {
    const left = parseRange(locatorValueRange(a.locator)), right = parseRange(locatorValueRange(b.locator));
    return left && right ? left.startRow - right.startRow || left.startColumn - right.startColumn : 0;
  });
  return { ...instance, model };
}

export function resolveInstanceDefinition(snapshot: Record<string, unknown>, verified?: WorkbookTemplateInstance) {
  const instance = readWorkbookInstance(snapshot) ?? verified;
  return instance?.version === 2 ? normalizeInstanceDefinition(instance, snapshot) : undefined;
}
