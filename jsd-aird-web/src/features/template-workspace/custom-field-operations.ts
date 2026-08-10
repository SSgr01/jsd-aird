import {
  addBusinessField,
  templateLocalFieldCode,
  writeFieldModel,
} from './field-model';
import type {
  BusinessField,
  EditorSelection,
  FieldModel,
  FieldOrigin,
  TemplateBinding,
} from './types';

export type CustomFieldKind = 'SCALAR' | 'REPEAT_FIELD' | 'MATRIX_FIELD';

export interface CreateCustomFieldInput {
  ownerId: string;
  origin: Extract<FieldOrigin, 'TEMPLATE_LOCAL' | 'ORDER_LOCAL'>;
  kind: CustomFieldKind;
  name: string;
  valueType?: string;
  groupId?: string;
  parentField?: BusinessField;
  parentBinding?: TemplateBinding;
  sheet?: Pick<EditorSelection, 'sheetId' | 'sheetName'>;
  labelRange?: string;
  valueRange?: string;
}

export function createCustomFieldWorkspace(
  schema: Record<string, unknown>,
  model: FieldModel,
  mapping: TemplateBinding[],
  input: CreateCustomFieldInput,
) {
  const id = crypto.randomUUID();
  const bindingId = crypto.randomUUID();
  const key = `field_${id.replaceAll('-', '')}`;
  const parent = input.parentField;
  const parentBinding = input.parentBinding;
  if (input.kind !== 'SCALAR' && (!parent || !parentBinding || !parent.dataPath)) {
    throw new Error('明细或矩阵字段缺少父级区域');
  }
  const dataPath = parent?.dataPath
    ? `${parent.dataPath}/*/${key}`
    : `${input.origin === 'ORDER_LOCAL' ? '/orderLocalFields' : '/customFields'}/${key}`;
  const groupId = input.groupId || parent?.groupId || model.groups[0]?.id || 'group-other';
  const fieldCode = input.origin === 'TEMPLATE_LOCAL'
    ? templateLocalFieldCode(input.ownerId, { id, name: input.name, dataPath })
    : `ORDER_LOCAL.${shortKey(input.ownerId)}.${key.toUpperCase()}`;
  const valueMode = structuredValueMode(parent);
  const valueRange = normalizeRange(input.valueRange);
  const labelRange = normalizeRange(input.labelRange);
  const mappingKind = input.kind;
  const field: BusinessField = {
    id,
    fieldId: id,
    bindingId,
    dataPath,
    fieldCode,
    parentFieldId: parent?.id,
    regionId: parent?.regionId,
    blockId: parent?.blockId,
    parentBlockId: parent?.parentBlockId ?? parent?.blockId,
    groupId,
    name: input.name.trim() || '新字段',
    kind: 'SCALAR',
    valueType: input.valueType || 'string',
    required: false,
    reviewStatus: valueRange ? 'CONFIRMED' : 'NEEDS_CONFIRMATION',
    editability: 'EDITABLE',
    valueSource: 'USER_INPUT',
    mappingKind,
    matrixRole: input.kind === 'MATRIX_FIELD' ? 'MEASURE' : undefined,
    repeatAxis: parent?.repeatAxis,
    recordHeight: parent?.recordHeight,
    recordWidth: parent?.recordWidth,
    recordStride: parent?.recordStride,
    labelRange: labelRange || undefined,
    valueRange: valueRange || undefined,
    fieldOrigin: input.origin,
    standardSelectionStatus: 'CUSTOM',
    standardMatchStatus: 'CONFIRMED',
    requiresStandardConfirmation: false,
    locator: {
      ...(input.sheet ?? {}),
      labelAddress: firstCell(labelRange),
      labelRange,
      address: valueRange,
      valueRange,
      logicalInputRange: valueRange,
      anchorAddress: firstCell(valueRange),
      anchorRange: valueRange,
      valueMode,
      ...(input.kind === 'MATRIX_FIELD' ? { matrixRole: 'MEASURE' } : {}),
    },
  };
  const binding: TemplateBinding = {
    bindingId,
    fieldId: id,
    parentBindingId: parentBinding?.bindingId,
    fieldCode,
    dataPath,
    role: 'FIELD',
    mappingKind,
    repeatAxis: parent?.repeatAxis,
    recordHeight: parent?.recordHeight,
    recordWidth: parent?.recordWidth,
    recordStride: parent?.recordStride,
    locatorType: 'CELL_RANGE',
    locator: structuredClone(field.locator ?? {}),
    syncDirection: 'TWO_WAY',
    primaryBinding: true,
    bindingStatus: valueRange ? 'VALID' : 'MISSING',
    diagnostic: {
      source: input.origin === 'ORDER_LOCAL' ? 'ORDER_CUSTOMER_CREATED' : 'CUSTOMER_CREATED',
      displayName: field.name,
      editability: 'EDITABLE',
      valueSource: 'USER_INPUT',
      regionId: parent?.regionId,
      blockId: parent?.blockId,
      parentBlockId: parent?.parentBlockId ?? parent?.blockId,
      parentFieldId: parent?.id,
      parentBindingId: parentBinding?.bindingId,
      ...(input.kind === 'MATRIX_FIELD' ? { matrixRole: 'MEASURE' } : {}),
    },
  };
  const added = addBusinessField(schema, model, field);
  const nextModel = parent && input.kind === 'REPEAT_FIELD'
    ? appendParentColumn(added.model, parent.id, field)
    : added.model;
  return {
    field,
    binding,
    model: nextModel,
    schema: writeFieldModel(added.schema, nextModel),
    mapping: [...mapping, binding],
  };
}

function appendParentColumn(model: FieldModel, parentId: string, field: BusinessField): FieldModel {
  return {
    ...model,
    fields: model.fields.map((item) => item.id === parentId
      ? {
          ...item,
          columns: [
            ...(item.columns ?? []),
            {
              code: field.dataPath?.split('/').at(-1) || field.id,
              bindingId: field.bindingId,
              fieldId: field.id,
              fieldCode: field.fieldCode,
              dataPath: field.dataPath,
              name: field.name,
              valueType: field.valueType,
              labelRange: field.labelRange,
              valueRange: field.valueRange,
              editability: 'EDITABLE',
              valueSource: 'USER_INPUT',
            },
          ],
        }
      : item),
  };
}

function structuredValueMode(parent?: BusinessField) {
  const axis = parent?.matrixModel?.recordAxis || parent?.repeatAxis;
  return axis === 'COLUMN' ? 'ARRAY_ROW' : 'ARRAY_COLUMN';
}

function normalizeRange(value?: string) {
  return (value || '').replaceAll('$', '').trim().toUpperCase();
}

function firstCell(value: string) {
  return value.split(':')[0] || '';
}

function shortKey(value: string) {
  return value.replaceAll('-', '').slice(0, 12).toUpperCase() || 'ORDER';
}
