import { generateUUID } from '@/utils/uuid';

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

export type CustomFieldKind = 'SCALAR' | 'REPEAT_FIELD';

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
  const id = generateUUID();
  const bindingId = generateUUID();
  const key = `field_${id.replaceAll('-', '')}`;
  const parent = input.parentField;
  const parentBinding = input.parentBinding;
  if (input.kind !== 'SCALAR' && (!parent || !parentBinding || !parent.dataPath)) {
    throw new Error('明细字段缺少父级区域');
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
  const parentSheet = parentBinding?.locator ?? {};
  const sheet = input.sheet ?? {
    sheetId: String(parentSheet.sheetId || ''),
    sheetName: String(parentSheet.sheetName || ''),
  };
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
      ...sheet,
      labelAddress: firstCell(labelRange),
      labelRange,
      address: valueRange,
      valueRange,
      logicalInputRange: valueRange,
      anchorAddress: firstCell(valueRange),
      anchorRange: valueRange,
      valueMode,
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

/** Extend a row table by one adjacent column when a new child is positioned there. */
export function expandAdjacentRepeatRegion(
  mapping: TemplateBinding[],
  model: FieldModel,
  child: TemplateBinding,
): { mapping: TemplateBinding[]; model: FieldModel } {
  if (child.mappingKind !== 'REPEAT_FIELD' || !child.parentBindingId) return { mapping, model };
  const parent = mapping.find((item) => item.bindingId === child.parentBindingId);
  if (!parent || parent.mappingKind !== 'REPEAT_REGION' || parent.repeatAxis !== 'ROW') {
    return { mapping, model };
  }
  const parentSheet = String(parent.locator.sheetId || parent.locator.sheetName || '');
  const childSheet = String(child.locator.sheetId || child.locator.sheetName || '');
  if (!parentSheet || parentSheet !== childSheet) return { mapping, model };
  const dataRange = parseRange(String(parent.locator.dataRange || parent.locator.range || ''));
  const childRange = parseRange(String(child.locator.logicalInputRange || child.locator.valueRange || child.locator.address || ''));
  if (!dataRange || !childRange
      || (childRange.left !== dataRange.right + 1 && childRange.left !== dataRange.right)
      || childRange.right !== childRange.left
      || childRange.top < dataRange.top || childRange.bottom > dataRange.bottom) {
    return { mapping, model };
  }
  const nextLocator = { ...parent.locator };
  for (const key of ['range', 'recordRange', 'dataRange', 'valueRange', 'logicalInputRange', 'address', 'headerRange'] as const) {
    const range = parseRange(String(nextLocator[key] || ''));
    if (range && range.right < childRange.right) nextLocator[key] = formatRange({ ...range, right: childRange.right });
  }
  const nestedValue = nextLocator.value;
  if (nestedValue && typeof nestedValue === 'object' && !Array.isArray(nestedValue)) {
    const value = { ...nestedValue } as Record<string, unknown>;
    const valueRange = parseRange(String(value.range || ''));
    if (valueRange && valueRange.right < childRange.right) {
      value.range = formatRange({ ...valueRange, right: childRange.right });
      nextLocator.value = value;
    }
  }
  const nextWidth = Math.max(Number(parent.recordWidth || 0), childRange.right - dataRange.left + 1);
  return {
    mapping: mapping.map((item) => item.bindingId === parent.bindingId
      ? { ...item, locator: nextLocator, recordWidth: nextWidth } : item),
    model: {
      ...model,
      fields: model.fields.map((field) => field.bindingId === parent.bindingId
        ? { ...field, locator: nextLocator, valueRange: String(nextLocator.valueRange || field.valueRange || ''), recordWidth: nextWidth } : field),
    },
  };
}

function parseRange(value: string) {
  const match = /^([A-Z]+)([1-9][0-9]*)(?::([A-Z]+)([1-9][0-9]*))?$/i.exec(value);
  if (!match) return null;
  const column = (letters: string) => [...letters.toUpperCase()].reduce((total, char) => total * 26 + char.charCodeAt(0) - 64, 0);
  return {
    left: column(match[1] || ''),
    top: Number(match[2] || 0),
    right: column(match[3] || match[1] || ''),
    bottom: Number(match[4] || match[2] || 0),
  };
}

function formatRange(range: { left: number; top: number; right: number; bottom: number }) {
  const column = (number: number) => {
    let result = '';
    for (let value = number; value > 0; value = Math.floor((value - 1) / 26)) {
      result = String.fromCharCode(65 + (value - 1) % 26) + result;
    }
    return result;
  };
  return `${column(range.left)}${range.top}:${column(range.right)}${range.bottom}`;
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
  const axis = parent?.repeatAxis;
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
