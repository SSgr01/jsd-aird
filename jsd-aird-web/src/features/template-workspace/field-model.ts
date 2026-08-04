import type { RecognitionSuggestion } from '@/services/templates/template-api';

import type {
  BusinessField,
  FieldKind,
  FieldModel,
  TemplateBinding,
} from './types';
import { groupCode, normalizeFieldModel, normalizeGroupName } from './group-normalizer';

const FIELD_MODEL_KEY = 'x-jsd-field-model';

export function readFieldModel(
  schema: Record<string, unknown>,
  mapping: TemplateBinding[],
): FieldModel {
  const stored = schema[FIELD_MODEL_KEY];
  if (isRecord(stored) && Array.isArray(stored.groups) && Array.isArray(stored.fields)) {
    return normalizeFieldModel(structuredClone(stored) as unknown as FieldModel);
  }
  const groupId = 'group-other';
  return {
    modelVersion: 4,
    groups: [{ id: groupId, name: '其他信息', order: 0 }],
    fields: mapping.map((binding) => ({
      id: binding.bindingId,
      bindingId: binding.bindingId,
      dataPath: binding.dataPath,
      groupId,
      name: displayName(binding),
      kind: kindFromBinding(binding),
      valueType: 'string',
      required: false,
      description: stringValue(binding.diagnostic?.description),
      interpretation: interpretation(kindFromBinding(binding), displayName(binding)),
      reviewStatus: binding.bindingStatus === 'VALID' ? 'CONFIRMED' : 'ISSUE',
      editability: stringValue(binding.diagnostic?.editability) as BusinessField['editability'] || 'UNKNOWN',
      valueSource: stringValue(binding.diagnostic?.valueSource) as BusinessField['valueSource'] || 'UNKNOWN',
    })),
    blocks: [],
    semanticAnnotations: [],
  };
}

export function writeFieldModel(
  schema: Record<string, unknown>,
  fieldModel: FieldModel,
): Record<string, unknown> {
  return { ...structuredClone(schema), [FIELD_MODEL_KEY]: structuredClone(fieldModel) };
}

export function applySuggestion(
  schema: Record<string, unknown>,
  mapping: TemplateBinding[],
  model: FieldModel,
  suggestion: RecognitionSuggestion,
  locatorOverride?: Record<string, unknown>,
) {
  const existing = mapping.find((binding) => binding.dataPath === suggestion.payload.dataPath);
  if (existing) return { schema, mapping, model, binding: existing };

  const groupName = normalizeGroupName(
    suggestion.payload.groupName?.trim() || '基础信息',
  );
  let group = model.groups.find((item) => item.name === groupName);
  const nextModel = structuredClone(model);
  if (!group) {
    group = {
      id: `group-${crypto.randomUUID()}`,
      name: groupName,
      groupCode: groupCode(groupName),
      order: nextModel.groups.length,
    };
    nextModel.groups.push(group);
  }
  const bindingId = suggestion.payload.bindingId || crypto.randomUUID();
  const fieldId = suggestion.payload.fieldId || bindingId;
  const kind = suggestionKind(suggestion);
  const locator = locatorOverride ?? suggestion.payload.locator;
  const editability = suggestion.payload.editability ?? 'EDITABLE';
  const valueSource = suggestion.payload.valueSource ?? 'USER_INPUT';
  const validBinding = editability !== 'UNKNOWN' && valueSource !== 'UNKNOWN';
  const binding: TemplateBinding = {
    bindingId,
    fieldId,
    relationId: suggestion.payload.relationId,
    fieldCode: suggestion.payload.fieldCode,
    dataPath: suggestion.payload.dataPath,
    role: kind === 'SCALAR' ? 'FIELD' : 'REPEAT_REGION',
    locatorType: suggestion.payload.locatorType,
    locator,
    syncDirection: syncDirection(editability, valueSource),
    primaryBinding: true,
    bindingStatus: validBinding ? 'VALID' : 'AMBIGUOUS',
    diagnostic: {
      source: locatorOverride ? 'CUSTOMER_CONFIRMED' : 'AUTO_RECOGNIZED',
      kind,
      groupName,
      description: suggestion.payload.reason,
      recognitionItemId: suggestion.id,
      editability,
      valueSource,
      condition: suggestion.payload.condition,
      blockId: suggestion.payload.blockId,
    },
  };
  const field: BusinessField = {
    id: fieldId,
    fieldId,
    relationId: suggestion.payload.relationId,
    recognitionItemId: suggestion.id,
    ...(validBinding ? { bindingId } : {}),
    dataPath: suggestion.payload.dataPath,
    groupId: group.id,
    name: suggestion.payload.fieldName,
    kind,
    valueType: suggestion.payload.valueType,
    required: suggestion.payload.required,
    unit: suggestion.payload.unit,
    description: suggestion.payload.reason,
    interpretation:
      suggestion.payload.interpretation || interpretation(kind, suggestion.payload.fieldName),
    confidence: suggestion.confidence,
    reviewStatus: suggestion.decision === 'ACCEPTED' ? 'CONFIRMED' : 'NEEDS_CONFIRMATION',
    editability,
    valueSource,
    condition: suggestion.payload.condition,
    blockId: suggestion.payload.blockId,
    parentBlockId: suggestion.payload.parentBlockId,
    columns: suggestion.payload.columns,
    tableModel: suggestion.payload.tableModel,
    matrixModel: suggestion.payload.matrixModel,
  };
  const existingFieldIndex = nextModel.fields.findIndex(
    (item) => item.dataPath === suggestion.payload.dataPath,
  );
  if (existingFieldIndex >= 0) {
    field.id = nextModel.fields[existingFieldIndex]?.id ?? field.id;
    nextModel.fields[existingFieldIndex] = field;
  } else {
    nextModel.fields.push(field);
  }
  const nextSchema = addFieldSchema(schema, suggestion, kind);
  return {
    schema: writeFieldModel(nextSchema, nextModel),
    mapping: validBinding ? [...mapping, binding] : mapping,
    model: nextModel,
    binding,
  };
}

function syncDirection(
  editability: NonNullable<BusinessField['editability']>,
  valueSource: NonNullable<BusinessField['valueSource']>,
): TemplateBinding['syncDirection'] {
  if (editability === 'EDITABLE' || editability === 'CONDITIONAL') return 'TWO_WAY';
  if (valueSource === 'FORMULA' || valueSource === 'STATIC' || valueSource === 'REFERENCE' || valueSource === 'MIXED') {
    return 'EDITOR_TO_DATA';
  }
  return 'EDITOR_TO_DATA';
}

export function updateBusinessField(
  schema: Record<string, unknown>,
  model: FieldModel,
  fieldId: string,
  update: Partial<BusinessField>,
) {
  const next = structuredClone(model);
  next.fields = next.fields.map((field) => (field.id === fieldId ? { ...field, ...update } : field));
  const updatedField = next.fields.find((field) => field.id === fieldId);
  const nextSchema = updatedField ? updateFieldSchema(schema, updatedField) : schema;
  return { model: next, schema: writeFieldModel(nextSchema, next) };
}

export function removeBusinessField(
  schema: Record<string, unknown>,
  model: FieldModel,
  fieldId: string,
) {
  const field = model.fields.find((item) => item.id === fieldId);
  const nextModel = {
    ...structuredClone(model),
    fields: model.fields.filter((item) => item.id !== fieldId),
  };
  const nextSchema = structuredClone(schema);
  if (field?.dataPath) removeSchemaAtPath(nextSchema, field.dataPath);
  return { model: nextModel, schema: writeFieldModel(nextSchema, nextModel) };
}

function addFieldSchema(
  source: Record<string, unknown>,
  suggestion: RecognitionSuggestion,
  kind: FieldKind,
) {
  const schema = structuredClone(source);
  const segments = pointerSegments(suggestion.payload.dataPath);
  let current: Record<string, unknown> = schema;
  segments.forEach((segment, index) => {
    const properties = ensureRecord(current, 'properties');
    const last = index === segments.length - 1;
    if (last) {
      properties[segment] = schemaFor(suggestion, kind);
      if (suggestion.payload.required) {
        const required = Array.isArray(current.required) ? (current.required as string[]) : [];
        if (!required.includes(segment)) current.required = [...required, segment];
      }
      return;
    }
    const child = isRecord(properties[segment])
      ? properties[segment]
      : { type: 'object', properties: {} };
    properties[segment] = child;
    current = child;
  });
  return schema;
}

function schemaFor(suggestion: RecognitionSuggestion, kind: FieldKind) {
  const common = {
    title: suggestion.payload.fieldName,
    'x-field-code': suggestion.payload.fieldCode,
    'x-region-kind': kind,
  };
  if (kind === 'ROW_TABLE') {
    return {
      ...common,
      type: 'array',
      items: {
        type: 'object',
        properties: Object.fromEntries(
          (suggestion.payload.columns ?? []).map((column) => [
            column.code,
            { type: normalizeType(column.valueType), title: column.name },
          ]),
        ),
      },
    };
  }
  if (kind === 'MATRIX') {
    return { ...common, type: 'array', items: { type: 'array', items: { type: 'number' } } };
  }
  if (suggestion.payload.valueType === 'date') {
    return { ...common, type: 'string', format: 'date' };
  }
  if (suggestion.payload.valueType === 'datetime') {
    return { ...common, type: 'string', format: 'date-time' };
  }
  if (suggestion.payload.valueType === 'time') {
    return { ...common, type: 'string', format: 'time' };
  }
  if (suggestion.payload.valueType === 'duration') {
    return { ...common, type: 'string', format: 'duration' };
  }
  return { ...common, type: normalizeType(suggestion.payload.valueType) };
}

function updateFieldSchema(source: Record<string, unknown>, field: BusinessField) {
  if (!field.dataPath) return source;
  const schema = structuredClone(source);
  const segments = pointerSegments(field.dataPath);
  let current: Record<string, unknown> = schema;
  segments.forEach((segment, index) => {
    const properties = ensureRecord(current, 'properties');
    const last = index === segments.length - 1;
    if (last) {
      const existing = isRecord(properties[segment]) ? properties[segment] : {};
      properties[segment] = {
        ...existing,
        type: field.kind === 'SCALAR' ? normalizeType(field.valueType) : 'array',
        title: field.name,
        ...(field.kind === 'SCALAR' && fieldFormat(field.valueType)
          ? { format: fieldFormat(field.valueType) }
          : {}),
        ...(field.unit ? { 'x-unit': field.unit } : {}),
      };
      const required = Array.isArray(current.required) ? current.required as string[] : [];
      current.required = field.required
        ? [...new Set([...required, segment])]
        : required.filter((item) => item !== segment);
      return;
    }
    const child = isRecord(properties[segment])
      ? properties[segment]
      : { type: 'object', properties: {} };
    properties[segment] = child;
    current = child;
  });
  return schema;
}

function removeSchemaAtPath(schema: Record<string, unknown>, path: string) {
  const segments = pointerSegments(path);
  let current: Record<string, unknown> = schema;
  segments.forEach((segment, index) => {
    if (!isRecord(current.properties)) return;
    if (index === segments.length - 1) {
      delete current.properties[segment];
      if (Array.isArray(current.required)) {
        current.required = current.required.filter((item) => item !== segment);
      }
      return;
    }
    const child = current.properties[segment];
    if (isRecord(child)) current = child;
  });
}

function suggestionKind(suggestion: RecognitionSuggestion): FieldKind {
  if (suggestion.payload.kind) return suggestion.payload.kind;
  if (suggestion.suggestionType.includes('MATRIX')) return 'MATRIX';
  if (suggestion.suggestionType.includes('TABLE') || suggestion.payload.role === 'REPEAT_REGION') {
    return 'ROW_TABLE';
  }
  return 'SCALAR';
}

function kindFromBinding(binding: TemplateBinding): FieldKind {
  const kind = stringValue(binding.diagnostic?.kind);
  if (kind === 'MATRIX' || binding.locatorType === 'MATRIX_REGION') return 'MATRIX';
  if (kind === 'ROW_TABLE' || binding.role === 'REPEAT_REGION') return 'ROW_TABLE';
  return 'SCALAR';
}

function displayName(binding: TemplateBinding) {
  return stringValue(binding.diagnostic?.displayName) || binding.fieldCode || '业务字段';
}

function interpretation(kind: FieldKind, name: string) {
  if (kind === 'ROW_TABLE') return `系统认为“${name}”中每一行代表一条业务记录。`;
  if (kind === 'MATRIX') return `系统认为“${name}”的行列表示不同条件，交叉位置填写结果。`;
  return `系统认为这里用于填写“${name}”。`;
}

function normalizeType(value?: string) {
  return ['number', 'integer', 'boolean', 'array', 'object'].includes(value ?? '')
    ? value
    : 'string';
}

function fieldFormat(value?: string) {
  if (value === 'date') return 'date';
  if (value === 'datetime') return 'date-time';
  if (value === 'time') return 'time';
  if (value === 'duration') return 'duration';
  return undefined;
}

function pointerSegments(path: string) {
  return path
    .split('/')
    .slice(1)
    .filter(Boolean)
    .map((segment) => segment.replaceAll('~1', '/').replaceAll('~0', '~'));
}

function ensureRecord(target: Record<string, unknown>, key: string) {
  const value = target[key];
  if (isRecord(value)) return value;
  const created: Record<string, unknown> = {};
  target[key] = created;
  return created;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function stringValue(value: unknown) {
  return typeof value === 'string' ? value : '';
}
