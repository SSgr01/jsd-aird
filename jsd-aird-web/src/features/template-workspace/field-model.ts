import type { RecognitionSuggestion } from '@/services/templates/template-api';

import type { BusinessField, FieldKind, FieldModel, TemplateBinding } from './types';
import { groupCode, normalizeFieldModel, normalizeGroupName } from './group-normalizer';

const FIELD_MODEL_KEY = 'x-jsd-field-model';

export interface FieldIdentity {
  bindingId?: string;
  relationId?: string;
  fieldId?: string;
  recognitionItemId?: string;
}

/** Stable field identity. dataPath is deliberately not part of this key. */
export function fieldMatchesIdentity(field: BusinessField, identity: FieldIdentity) {
  const stableIdentity = Boolean(identity.bindingId || identity.relationId || identity.fieldId);
  if (identity.bindingId && field.bindingId === identity.bindingId) return true;
  if (identity.relationId && field.relationId === identity.relationId) return true;
  if (identity.fieldId && (field.fieldId === identity.fieldId || field.id === identity.fieldId)) {
    return true;
  }
  return !stableIdentity && Boolean(identity.recognitionItemId)
    && field.recognitionItemId === identity.recognitionItemId;
}

export function bindingMatchesIdentity(binding: TemplateBinding, identity: FieldIdentity) {
  const stableIdentity = Boolean(identity.bindingId || identity.relationId || identity.fieldId);
  if (identity.bindingId && binding.bindingId === identity.bindingId) return true;
  if (identity.relationId && binding.relationId === identity.relationId) return true;
  if (identity.fieldId && binding.fieldId === identity.fieldId) return true;
  return !stableIdentity && Boolean(identity.recognitionItemId)
    && stringValue(binding.diagnostic?.recognitionItemId) === identity.recognitionItemId;
}

export function readFieldModel(
  schema: Record<string, unknown>,
  mapping: TemplateBinding[],
): FieldModel {
  const stored = schema[FIELD_MODEL_KEY];
  if (isRecord(stored) && Array.isArray(stored.groups) && Array.isArray(stored.fields)) {
    return normalizeFieldModel(structuredClone(stored) as unknown as FieldModel);
  }
  const groupId = 'group-basic';
  return {
    modelVersion: 4,
    groups: [{ id: groupId, name: '基础信息', groupCode: 'BASIC_INFORMATION', order: 0 }],
    fields: mapping.map((binding) => ({
      id: binding.bindingId,
      bindingId: binding.bindingId,
      fieldCode: binding.fieldCode,
      dataPath: binding.dataPath,
      groupId,
      name: displayName(binding),
      kind: kindFromBinding(binding),
      valueType: 'string',
      required: false,
      description: stringValue(binding.diagnostic?.description),
      interpretation: interpretation(kindFromBinding(binding), displayName(binding)),
      reviewStatus: binding.bindingStatus === 'VALID' ? 'CONFIRMED' : 'ISSUE',
      editability:
        (stringValue(binding.diagnostic?.editability) as BusinessField['editability']) || 'UNKNOWN',
      valueSource:
        (stringValue(binding.diagnostic?.valueSource) as BusinessField['valueSource']) || 'UNKNOWN',
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

export function templateLocalFieldCode(
  templateVersionId: string,
  field: Pick<BusinessField, 'id' | 'name' | 'dataPath'>,
) {
  const templateKey = templateVersionId.replaceAll('-', '').slice(0, 12).toUpperCase();
  const pathKey = (field.dataPath || '').split('/').filter(Boolean).pop() || '';
  const semanticKey =
    pathKey.replace(/[^A-Za-z0-9_]/g, '_').toUpperCase() ||
    ((field.name || '').includes('签名')
      ? 'SIGNATURE'
      : `FIELD_${field.id.replaceAll('-', '').slice(0, 10).toUpperCase()}`);
  return `TEMPLATE_LOCAL.${templateKey}.${semanticKey}`;
}

export function applySuggestion(
  schema: Record<string, unknown>,
  mapping: TemplateBinding[],
  model: FieldModel,
  suggestion: RecognitionSuggestion,
  locatorOverride?: Record<string, unknown>,
) {
  suggestion = normalizeSuggestionDataPaths(suggestion, model.fields);
  const hasStableIdentity = Boolean(
    suggestion.payload.bindingId || suggestion.payload.relationId || suggestion.payload.fieldId,
  );
  const existing = mapping.find((binding) =>
    bindingMatchesIdentity(binding, {
      bindingId: suggestion.payload.bindingId,
      relationId: suggestion.payload.relationId,
      fieldId: suggestion.payload.fieldId,
      ...(!hasStableIdentity ? { recognitionItemId: suggestion.id } : {}),
    })
    || (!hasStableIdentity && binding.dataPath === suggestion.payload.dataPath),
  );
  if (existing) return { schema, mapping, model, binding: existing };

  const groupName = normalizeGroupName(
    typeof suggestion.payload.groupName === 'string' ? suggestion.payload.groupName : '基础信息',
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
  const isChild = suggestion.payload.suggestionLevel === 'CHILD';
  const binding: TemplateBinding = {
    bindingId,
    fieldId,
    relationId: suggestion.payload.relationId,
    fieldCode: suggestion.payload.fieldCode,
    dataPath: suggestion.payload.dataPath,
    role: kind === 'SCALAR' ? 'FIELD' : 'REPEAT_REGION',
    mappingKind: suggestion.payload.mappingKind || (isChild
      ? kind === 'MATRIX'
        ? 'MATRIX_FIELD'
        : 'REPEAT_FIELD'
      : kind === 'MATRIX'
        ? 'MATRIX_REGION'
        : kind === 'SCALAR'
          ? 'SCALAR'
          : 'REPEAT_REGION'),
    parentBindingId: suggestion.payload.parentBindingId,
    repeatAxis: suggestion.payload.repeatAxis,
    recordHeight: suggestion.payload.recordHeight ?? 1,
    recordWidth: suggestion.payload.recordWidth ?? 1,
    recordStride: suggestion.payload.recordStride ?? 1,
    termination: suggestion.payload.terminationRule,
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
      semanticConflict: suggestion.payload.semanticConflict,
      conflictCode: suggestion.payload.conflictCode,
      conflictMessage: suggestion.payload.conflictMessage,
      dictionaryVersion: suggestion.payload.dictionaryVersion,
      standardMatchStatus: suggestion.payload.standardMatchStatus,
      requiresStandardConfirmation: suggestion.payload.requiresStandardConfirmation,
      condition: suggestion.payload.condition,
      blockId: suggestion.payload.blockId,
    },
  };
  const field: BusinessField = {
    id: fieldId,
    fieldId,
    relationId: suggestion.payload.relationId,
    recognitionItemId: suggestion.id,
    parentFieldId: suggestion.payload.parentFieldId,
    parentSuggestionId:
      suggestion.payload.parentSuggestionId || suggestion.payload.parentRelationId,
    ...(validBinding ? { bindingId } : {}),
    dataPath: suggestion.payload.dataPath,
    fieldCode: suggestion.payload.fieldCode,
    groupId: group.id,
    name: stringValue(suggestion.payload.fieldName),
    kind,
    valueType: suggestion.payload.valueType,
    required: suggestion.payload.required,
    unit: suggestion.payload.unit,
    description: suggestion.payload.reason,
      interpretation:
        stringValue(suggestion.payload.interpretation) || interpretation(kind, stringValue(suggestion.payload.fieldName)),
    confidence: suggestion.confidence,
    reviewStatus: suggestion.decision === 'ACCEPTED' ? 'CONFIRMED' : 'NEEDS_CONFIRMATION',
    editability,
    valueSource,
    condition: suggestion.payload.condition,
    blockId: suggestion.payload.blockId,
    parentBlockId: suggestion.payload.parentBlockId,
    mappingKind: binding.mappingKind,
    repeatAxis: binding.repeatAxis,
    recordHeight: binding.recordHeight,
    recordWidth: binding.recordWidth,
    recordStride: binding.recordStride,
    semanticConflict: suggestion.payload.semanticConflict,
    conflictCode: suggestion.payload.conflictCode,
    conflictMessage: suggestion.payload.conflictMessage,
    dictionaryVersion: suggestion.payload.dictionaryVersion,
    standardMatchStatus: suggestion.payload.standardMatchStatus,
    requiresStandardConfirmation: suggestion.payload.requiresStandardConfirmation,
    standardFieldId: suggestion.payload.standardFieldId,
    standardFieldVersion: suggestion.payload.standardFieldVersion,
    standardFieldName: suggestion.payload.standardFieldName,
    fieldOrigin: suggestion.payload.fieldOrigin,
    standardSelectionStatus: suggestion.payload.standardSelectionStatus,
    uiType: suggestion.payload.uiType,
    termination: suggestion.payload.terminationRule,
    columns: suggestion.payload.columns,
    tableModel: suggestion.payload.tableModel,
    matrixModel: suggestion.payload.matrixModel,
    longTableModel: suggestion.payload.longTableModel,
    locator: structuredClone(suggestion.payload.locator),
  };
  const existingFieldIndex = nextModel.fields.findIndex(
    (item) => fieldMatchesIdentity(item, {
      bindingId: suggestion.payload.bindingId,
      relationId: suggestion.payload.relationId,
      fieldId: suggestion.payload.fieldId,
      ...(!hasStableIdentity ? { recognitionItemId: suggestion.id } : {}),
    }),
  );
  if (existingFieldIndex >= 0) {
    field.id = nextModel.fields[existingFieldIndex]?.id ?? field.id;
    nextModel.fields = nextModel.fields.filter(
      (item, index) => index === existingFieldIndex || item.parentFieldId !== field.id,
    );
    const replacementIndex = nextModel.fields.findIndex((item) => item.id === field.id);
    nextModel.fields[replacementIndex] = field;
  } else {
    nextModel.fields.push(field);
  }
  if (kind === 'ROW_TABLE' && !suggestion.payload.hasIndependentChildren) {
    nextModel.fields.push(...tableChildFields(suggestion, field, false));
  }
  const nextSchema = addFieldSchema(schema, suggestion, kind);
  return {
    schema: writeFieldModel(nextSchema, nextModel),
    mapping: validBinding ? [...mapping, binding] : mapping,
    model: nextModel,
    binding,
  };
}

export function persistableFieldModel(fieldModel: FieldModel): FieldModel {
  const formalFields = fieldModel.fields.filter((field) => !field.candidate);
  const usedGroups = new Set(formalFields.map((field) => field.groupId));
  return {
    ...structuredClone(fieldModel),
    groups: fieldModel.groups.filter(
      (group) => usedGroups.has(group.id) || group.groupCode === 'BASIC_INFORMATION',
    ),
    fields: structuredClone(formalFields),
  };
}

export function prepareFormalSchema(
  schema: Record<string, unknown>,
  fieldModel: FieldModel,
): Record<string, unknown> {
  const next = structuredClone(schema);
  for (const candidate of fieldModel.fields.filter((field) => field.candidate && field.dataPath)) {
    if (!candidate.parentFieldId) removeSchemaAtPath(next, candidate.dataPath as string);
  }
  return writeFieldModel(next, persistableFieldModel(fieldModel));
}

export function prepareFormalMappings(
  mapping: TemplateBinding[],
  fieldModel: FieldModel,
): TemplateBinding[] {
  const formalBindingIds = new Set(
    fieldModel.fields
      .filter((field) => !field.candidate && field.bindingId)
      .map((field) => field.bindingId as string),
  );
  return mapping.filter((binding) => {
    const recognitionItemId = stringValue(binding.diagnostic?.recognitionItemId);
    return !recognitionItemId || formalBindingIds.has(binding.bindingId);
  });
}

export function addRecognitionCandidate(
  model: FieldModel,
  suggestion: RecognitionSuggestion,
): FieldModel {
  suggestion = normalizeSuggestionDataPaths(suggestion, model.fields);
  const next = structuredClone(model);
  const groupName = normalizeGroupName(
    typeof suggestion.payload.groupName === 'string' ? suggestion.payload.groupName : '基础信息',
  );
  let group = next.groups.find((item) => item.name === groupName);
  if (!group) {
    group = {
      id: `candidate-group-${groupCode(groupName).toLowerCase()}-${stableTextId(groupName)}`,
      name: groupName,
      groupCode: groupCode(groupName),
      order: next.groups.length,
    };
    next.groups.push(group);
  }
  const existingIndex = next.fields.findIndex(
    (field) =>
      field.candidate &&
      fieldMatchesIdentity(field, {
        bindingId: suggestion.payload.bindingId,
        relationId: suggestion.payload.relationId,
        fieldId: suggestion.payload.fieldId,
        recognitionItemId: suggestion.id,
      }),
  );
  const kind = suggestionKind(suggestion);
  const candidate: BusinessField = {
    id: suggestion.payload.fieldId || `candidate-${suggestion.id}`,
    fieldId: suggestion.payload.fieldId,
    relationId: suggestion.payload.relationId,
    recognitionItemId: suggestion.id,
    parentFieldId: suggestion.payload.parentFieldId,
    parentSuggestionId:
      suggestion.payload.parentSuggestionId || suggestion.payload.parentRelationId,
    mappingKind: suggestion.payload.mappingKind,
    repeatAxis: suggestion.payload.repeatAxis,
    recordHeight: suggestion.payload.recordHeight,
    recordWidth: suggestion.payload.recordWidth,
    recordStride: suggestion.payload.recordStride,
    semanticConflict: suggestion.payload.semanticConflict,
    conflictCode: suggestion.payload.conflictCode,
    conflictMessage: suggestion.payload.conflictMessage,
    dictionaryVersion: suggestion.payload.dictionaryVersion,
    standardMatchStatus: suggestion.payload.standardMatchStatus,
    requiresStandardConfirmation: suggestion.payload.requiresStandardConfirmation,
    termination: suggestion.payload.terminationRule,
    dataPath: suggestion.payload.dataPath,
    fieldCode: suggestion.payload.fieldCode,
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
    reviewStatus: 'NEEDS_CONFIRMATION',
    editability: suggestion.payload.editability,
    valueSource: suggestion.payload.valueSource,
    condition: suggestion.payload.condition,
    blockId: suggestion.payload.blockId,
    parentBlockId: suggestion.payload.parentBlockId,
    columns: suggestion.payload.columns,
    tableModel: suggestion.payload.tableModel,
    matrixModel: suggestion.payload.matrixModel,
    longTableModel: suggestion.payload.longTableModel,
    locator: structuredClone(suggestion.payload.locator),
    candidate: true,
    candidateLocatorType: suggestion.payload.locatorType,
    candidateLocator: structuredClone(suggestion.payload.locator),
  };
  if (existingIndex >= 0) {
    const previousId = next.fields[existingIndex]?.id;
    next.fields = next.fields.filter(
      (field, index) => index === existingIndex || field.parentFieldId !== previousId,
    );
    const replacementIndex = next.fields.findIndex((field) => field.id === previousId);
    next.fields[replacementIndex] = candidate;
    next.fields = next.fields.filter(
      (field, index) => index === replacementIndex || field.parentFieldId !== candidate.id,
    );
  } else {
    next.fields.push(candidate);
  }
  if (kind === 'ROW_TABLE' && !suggestion.payload.hasIndependentChildren) {
    next.fields.push(...tableChildFields(suggestion, candidate, true));
  }
  return normalizeFieldModel(next);
}

function tableChildFields(
  suggestion: RecognitionSuggestion,
  parent: BusinessField,
  candidate: boolean,
): BusinessField[] {
  return (suggestion.payload.columns ?? []).map((column) => {
    const dataPath =
      column.dataPath || `${parent.dataPath || '/recognized/table'}/*/${column.code}`;
    const locator = {
      ...structuredClone(suggestion.payload.locator),
      labelAddress: column.labelRange || suggestion.payload.locator?.labelAddress,
      labelRange: column.labelRange || suggestion.payload.locator?.labelRange,
      address: column.valueRange || suggestion.payload.locator?.address,
      logicalInputRange: column.valueRange || suggestion.payload.locator?.logicalInputRange,
      valueMode: parent.repeatAxis === 'COLUMN' ? 'ARRAY_ROW' : 'ARRAY_COLUMN',
      terminationRule: suggestion.payload.terminationRule,
    };
    return {
      id: candidate ? `candidate-${suggestion.id}-${column.code}` : `${parent.id}::${column.code}`,
      fieldId: candidate ? undefined : `${parent.id}::${column.code}`,
      bindingId: candidate ? undefined : column.bindingId,
      relationId:
        column.relationId
        || `${suggestion.payload.relationId || suggestion.id}|child|${column.code}|${normalizeRange(column.valueRange)}`,
      recognitionItemId: suggestion.id,
      dataPath,
      fieldCode: column.fieldCode || `${parent.fieldCode || 'TABLE'}.${column.code.toUpperCase()}`,
      parentFieldId: parent.id,
      groupId: parent.groupId,
      name: stringValue(column.name),
      kind: 'SCALAR',
      valueType: column.valueType || 'string',
      required: column.required ?? false,
      unit: column.unit,
      description: '明细表列字段',
      interpretation: `系统认为这是“${stringValue(column.name)}”明细列。`,
      confidence: suggestion.confidence,
      reviewStatus: candidate ? 'NEEDS_CONFIRMATION' : 'CONFIRMED',
      editability: column.editability,
      valueSource: column.valueSource,
      mappingKind: 'REPEAT_FIELD',
      repeatAxis: parent.repeatAxis || 'ROW',
      recordHeight: parent.recordHeight,
      recordWidth: parent.recordWidth,
      recordStride: parent.recordStride,
      semanticConflict: column.semanticConflict,
      conflictCode: column.conflictCode,
      conflictMessage: column.conflictMessage,
      dictionaryVersion: column.dictionaryVersion,
      standardMatchStatus: column.standardMatchStatus,
      requiresStandardConfirmation: column.requiresStandardConfirmation,
      standardFieldId: column.standardFieldId,
      standardFieldVersion: column.standardFieldVersion,
      standardFieldName: column.standardFieldName,
      fieldOrigin: column.fieldOrigin,
      standardSelectionStatus: column.standardSelectionStatus,
      uiType: column.uiType,
      condition: column.condition,
      labelRange: column.labelRange,
      valueRange: column.valueRange,
      dataStartRow: column.dataStartRow,
      locator,
      ...(candidate
        ? {
            candidate: true,
            candidateLocatorType: 'CELL_RANGE',
            candidateLocator: locator,
          }
        : {}),
    } satisfies BusinessField;
  });
}

function normalizeRange(value?: string) {
  return (typeof value === 'string' ? value : '').replaceAll('$', '').trim().toUpperCase();
}

function normalizeSuggestionDataPaths(
  suggestion: RecognitionSuggestion,
  fields: BusinessField[],
): RecognitionSuggestion {
  const occupied = new Set(fields.map((field) => field.dataPath).filter(Boolean) as string[]);
  const payload = suggestion.payload;
  const existing = fields.find((field) => fieldMatchesIdentity(field, {
    bindingId: payload.bindingId,
    relationId: payload.relationId,
    fieldId: payload.fieldId,
    recognitionItemId: suggestion.id,
  }));
  const identity = payload.bindingId || payload.relationId || payload.fieldId || suggestion.id;
  const dataPath = existing?.dataPath || uniqueDataPath(payload.dataPath, identity, occupied);
  occupied.add(dataPath);

  const columns = payload.columns?.map((column) => {
    const relationId = column.relationId
      || `${payload.relationId || suggestion.id}|child|${column.code}|${normalizeRange(column.valueRange)}`;
    const child = fields.find((field) => fieldMatchesIdentity(field, {
      bindingId: column.bindingId,
      relationId,
      fieldId: column.fieldId,
      recognitionItemId: suggestion.id,
    }));
    const childPath = child?.dataPath || uniqueDataPath(
      column.dataPath || `${dataPath}/*/${column.code}`,
      column.bindingId || relationId || column.fieldId || `${identity}|${column.code}`,
      occupied,
    );
    occupied.add(childPath);
    return childPath === column.dataPath ? column : { ...column, dataPath: childPath };
  });

  if (dataPath === payload.dataPath && columns?.every((column, index) => column === payload.columns?.[index])) {
    return suggestion;
  }
  return {
    ...suggestion,
    payload: {
      ...payload,
      dataPath,
      ...(columns ? { columns } : {}),
    },
  };
}

function uniqueDataPath(basePath: string, identity: string, occupied: Set<string>) {
  const normalized = (typeof basePath === 'string' ? basePath.trim() : '')
    || `/recognized/field/${stableTextId(identity)}`;
  if (!occupied.has(normalized)) return normalized;
  const suffix = `__${stableTextId(`${identity}|${normalized}`).slice(0, 8)}`;
  let candidate = `${normalized}${suffix}`;
  let sequence = 2;
  while (occupied.has(candidate)) {
    candidate = `${normalized}${suffix}_${sequence}`;
    sequence += 1;
  }
  return candidate;
}

export function candidateBinding(field: BusinessField): TemplateBinding | undefined {
  if (!field.candidate || !field.dataPath || !field.candidateLocator) return undefined;
  return {
    bindingId: `candidate-${field.recognitionItemId || field.id}`,
    fieldId: field.fieldId,
    relationId: field.relationId,
    parentBindingId:
      typeof field.locator?.parentBindingId === 'string'
        ? field.locator.parentBindingId
        : undefined,
    dataPath: field.dataPath,
    role: field.kind === 'SCALAR' ? 'FIELD' : 'REPEAT_REGION',
    mappingKind: field.mappingKind,
    repeatAxis: field.repeatAxis,
    recordHeight: field.recordHeight,
    recordWidth: field.recordWidth,
    recordStride: field.recordStride,
    termination: field.termination,
    locatorType: field.candidateLocatorType || 'CELL_RANGE',
    locator: structuredClone(field.candidateLocator),
    syncDirection: field.editability === 'READ_ONLY' ? 'EDITOR_TO_DATA' : 'TWO_WAY',
    primaryBinding: false,
    bindingStatus:
      field.editability === 'UNKNOWN' || field.valueSource === 'UNKNOWN' ? 'AMBIGUOUS' : 'VALID',
    diagnostic: { source: 'RECOGNITION_CANDIDATE', recognitionItemId: field.recognitionItemId },
  };
}

function syncDirection(
  editability: NonNullable<BusinessField['editability']>,
  valueSource: NonNullable<BusinessField['valueSource']>,
): TemplateBinding['syncDirection'] {
  if (editability === 'EDITABLE' || editability === 'CONDITIONAL') return 'TWO_WAY';
  if (
    valueSource === 'FORMULA' ||
    valueSource === 'STATIC' ||
    valueSource === 'REFERENCE' ||
    valueSource === 'MIXED'
  ) {
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
  next.fields = next.fields.map((field) =>
    field.id === fieldId ? { ...field, ...update } : field,
  );
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
    fields: model.fields.filter((item) => item.id !== fieldId && item.parentFieldId !== fieldId),
  };
  const nextSchema = structuredClone(schema);
  if (field?.dataPath) {
    if (field.parentFieldId) removeArrayItemSchemaAtPath(nextSchema, field.dataPath);
    else removeSchemaAtPath(nextSchema, field.dataPath);
  }
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
            {
              type: normalizeType(column.valueType),
              title: column.name,
              ...(column.fieldCode ? { 'x-field-code': column.fieldCode } : {}),
              ...(column.dataPath ? { 'x-data-path': column.dataPath } : {}),
              ...(column.unit ? { 'x-unit': column.unit } : {}),
            },
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
  if (field.parentFieldId && field.dataPath.includes('/*/')) {
    return updateArrayItemSchema(source, field);
  }
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
        ...(field.fieldCode ? { 'x-field-code': field.fieldCode } : {}),
      };
      const required = Array.isArray(current.required) ? (current.required as string[]) : [];
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
  if (path.includes('/*/')) {
    removeArrayItemSchemaAtPath(schema, path);
    return;
  }
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

function updateArrayItemSchema(source: Record<string, unknown>, field: BusinessField) {
  const schema = structuredClone(source);
  const [parentPath = '', childPath = ''] = field.dataPath!.split('/*/', 2);
  if (!childPath) return schema;
  const parentSchema = schemaAtPath(schema, parentPath);
  if (!parentSchema) return schema;
  const item = isRecord(parentSchema.items)
    ? parentSchema.items
    : { type: 'object', properties: {} };
  parentSchema.items = item;
  const properties = ensureRecord(item, 'properties');
  properties[childPath] = {
    ...(isRecord(properties[childPath]) ? properties[childPath] : {}),
    type: normalizeType(field.valueType),
    title: field.name,
    ...(field.unit ? { 'x-unit': field.unit } : {}),
    ...(field.fieldCode ? { 'x-field-code': field.fieldCode } : {}),
    'x-data-path': field.dataPath,
  };
  return schema;
}

function removeArrayItemSchemaAtPath(schema: Record<string, unknown>, path: string) {
  const [parentPath = '', childPath = ''] = path.split('/*/', 2);
  if (!childPath) return;
  const parentSchema = schemaAtPath(schema, parentPath);
  if (!parentSchema || !isRecord(parentSchema.items) || !isRecord(parentSchema.items.properties))
    return;
  delete parentSchema.items.properties[childPath];
}

function schemaAtPath(schema: Record<string, unknown>, path: string) {
  let current: Record<string, unknown> | undefined = schema;
  for (const segment of pointerSegments(path)) {
    const properties: Record<string, unknown> | undefined =
      current && isRecord(current.properties) ? current.properties : undefined;
    const next: unknown = properties?.[segment];
    if (!isRecord(next)) return undefined;
    current = next;
  }
  return current;
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

function stableTextId(value: string) {
  let hash = 2166136261;
  for (const character of value) {
    hash ^= character.codePointAt(0) ?? 0;
    hash = Math.imul(hash, 16777619);
  }
  return (hash >>> 0).toString(16).padStart(8, '0');
}
