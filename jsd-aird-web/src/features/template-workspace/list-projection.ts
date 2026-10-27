import { EXPERIMENT_FIELD_OPTIONS } from './experiment-semantics';
import type { BusinessField, ExperimentListProjection, FieldModel, TemplateBinding } from './types';

export type ProjectionFieldTarget = 'labelRange' | 'valueRange';

export function projectionGroup(projection: ExperimentListProjection, model: FieldModel) {
  const field = model.fields.find((item) => item.experimentField?.domain === projection.domain);
  return model.groups.find((group) => group.name === projection.displayName)
    ?? model.groups.find((group) => group.id === field?.groupId);
}

export function projectionName(projection: ExperimentListProjection, model: FieldModel) {
  return projection.displayName || projectionGroup(projection, model)?.name
    || ({ FORMULA: '配方明细', PROCESS: '工艺明细', TEST: '测试明细' }[projection.domain]);
}

export function projectionUnit(projection: ExperimentListProjection, model: FieldModel) {
  return projection.unit ?? model.fields.find((field) =>
    field.experimentField?.domain === projection.domain
    && field.experimentField.field === projection.valueSemantic && field.unit,
  )?.unit ?? '';
}

export function projectionFieldName(projection: ExperimentListProjection, target: ProjectionFieldTarget) {
  const configured = target === 'labelRange' ? projection.labelField?.name : projection.valueField?.name;
  if (configured) return configured;
  const semantic = target === 'labelRange' ? projection.labelSemantic : projection.valueSemantic;
  if (semantic === 'MATERIAL_CODE') return '物料编码';
  if (semantic === 'MATERIAL_NAME') return '物料名称';
  if (semantic === 'ACTUAL_QTY') return '用量';
  return EXPERIMENT_FIELD_OPTIONS.find((option) =>
    option.semantic.domain === projection.domain && option.semantic.field === semantic,
  )?.label.split(' / ').at(-1) || (target === 'labelRange' ? '项目名称' : '结果');
}

export function projectionLocator(projection: ExperimentListProjection, mapping: TemplateBinding[]) {
  return mapping.find((binding) => binding.bindingId === projection.parentBindingId)?.locator
    ?? mapping.find((binding) => binding.componentId === projection.componentId)?.locator;
}

export function projectionFieldViews(projections: ExperimentListProjection[], model: FieldModel, mapping: TemplateBinding[]) {
  return projections.flatMap((projection) => (['labelRange', 'valueRange'] as const).map((target) => {
    const id = `list:${projection.listProjectionId}:${target}`;
    const parent = projectionLocator(projection, mapping);
    const range = projection[target];
    const locator = {
      sheetId: parent?.sheetId, sheetName: parent?.sheetName,
      address: range, valueRange: range, logicalInputRange: range,
      ...(target === 'valueRange' ? { rowHeaderRange: projection.labelRange } : {}),
      source: 'MANUAL',
    };
    const field: BusinessField = {
      id, bindingId: id, fieldCode: `${projection.domain}.${target === 'labelRange' ? projection.labelSemantic : projection.valueSemantic}`,
      name: projectionFieldName(projection, target), groupId: projectionGroup(projection, model)?.id ?? '',
      kind: 'SCALAR', fieldType: 'TABLE_COLUMN', mappingKind: 'REPEAT_FIELD',
      valueType: target === 'labelRange' ? 'string' : 'number', required: false,
      unit: target === 'valueRange' ? projectionUnit(projection, model) : undefined,
      description: (target === 'labelRange' ? projection.labelField : projection.valueField)?.description,
      reviewStatus: range ? 'CONFIRMED' : 'NEEDS_CONFIRMATION', labelStatus: 'NOT_APPLICABLE',
      editability: 'EDITABLE', valueSource: 'USER_INPUT', locator,
      projectionField: { projectionId: projection.listProjectionId, target },
      experimentField: { domain: projection.domain, field: target === 'labelRange' ? projection.labelSemantic : projection.valueSemantic },
    };
    const binding: TemplateBinding = {
      bindingId: id, fieldCode: field.fieldCode, dataPath: `/lists/${projection.listProjectionId}/${target}`,
      role: 'FIELD', mappingKind: 'REPEAT_FIELD', locatorType: 'CELL_RANGE', locator,
      syncDirection: 'TWO_WAY', primaryBinding: false, bindingStatus: range ? 'VALID' : 'MISSING',
    };
    return { projection, target, field, binding };
  }));
}

export function updateProjectionField(projection: ExperimentListProjection, target: ProjectionFieldTarget, update: Partial<BusinessField>) {
  const key = target === 'labelRange' ? 'labelField' : 'valueField';
  return {
    ...projection,
    [key]: { ...projection[key],
      ...(update.name !== undefined ? { name: update.name } : {}),
      ...(update.description !== undefined ? { description: update.description } : {}),
    },
  };
}
