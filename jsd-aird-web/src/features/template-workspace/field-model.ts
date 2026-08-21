import type { RecognitionSuggestion } from '@/services/templates/template-api';
import { generateUUID } from '@/utils/uuid';

import type { BusinessField, FieldKind, FieldModel, TemplateBinding } from './types';
import { groupCode, normalizeFieldModel, normalizeGroupName } from './group-normalizer';
import {
  expandSemanticLabelRange,
  locatorLabelRange,
  locatorValueRange,
  mergeLocators,
} from './locator';

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

/** Keep a selected child field's own locator when its parent is also a repeat region. */
export function bindingForField(
  field: BusinessField,
  mapping: TemplateBinding[],
): TemplateBinding | undefined {
  const exact = mapping.find((binding) =>
    (Boolean(field.bindingId) && binding.bindingId === field.bindingId)
      || (Boolean(field.relationId) && binding.relationId === field.relationId
        && (!field.fieldId || !binding.fieldId || binding.fieldId === field.fieldId))
      || (Boolean(field.fieldId) && binding.fieldId === field.fieldId),
  );
  const base = exact ?? candidateBinding(field);
  if (base) {
    const locator = expandSemanticLabelRange(
      mergeLocators(base.locator, field.locator),
      field.pathSegments,
    );
    return {
      ...base,
      fieldId: field.fieldId || base.fieldId,
      relationId: field.relationId || base.relationId,
      mappingKind: field.mappingKind || base.mappingKind,
      locator,
    };
  }
  if (!field.locator) return undefined;
  return {
    bindingId: `field-${field.id}`,
    fieldId: field.fieldId || field.id,
    relationId: field.relationId,
    dataPath: field.dataPath || '',
    role: 'FIELD',
    mappingKind: field.mappingKind,
    locatorType: 'CELL_RANGE',
    locator: expandSemanticLabelRange(mergeLocators(field.locator), field.pathSegments),
    syncDirection: 'TWO_WAY',
    primaryBinding: false,
    bindingStatus: 'VALID',
  };
}

export function readFieldModel(
  schema: Record<string, unknown>,
  mapping: TemplateBinding[],
  snapshot?: Record<string, unknown>,
): FieldModel {
  const stored = schema[FIELD_MODEL_KEY];
  if (isRecord(stored) && Array.isArray(stored.groups) && Array.isArray(stored.fields)) {
    const normalized = normalizeFieldModel(structuredClone(stored) as unknown as FieldModel);
    return normalizeFieldModel({
      ...normalized,
      fields: normalized.fields.map((field) => {
        const binding = mapping.find((item) => bindingMatchesIdentity(item, {
          bindingId: field.bindingId,
          relationId: field.relationId,
          fieldId: field.fieldId || field.id,
        }));
        const locator = mergeLocators(field.locator, binding?.locator);
        // The confirmed binding/path and the workbook snapshot are the only
        // sources of the displayed structure. Do not decode business names
        // from fieldCode here: fieldCode is an identifier, not a display path.
        const bindingPath = bindingSemanticPath(binding);
        const snapshotPath = recoverSnapshotSemanticPath(
          binding?.mappingKind ?? field.mappingKind,
          binding?.locator ?? field.locator,
          snapshot,
        );
        const namedPath = splitLabelPath(field.name);
        const storedPath = bindingPath
          ?? preferSemanticPath(field.pathSegments, undefined)
          ?? namedPath;
        const pathSegments = mergeSemanticPaths(storedPath, snapshotPath) ?? namedPath;
        return {
          ...field,
          locator,
          pathSegments,
          labelStatus: field.fieldType === 'REGION' ? 'NOT_APPLICABLE' : locator.label ? 'RESOLVED' : 'UNRESOLVED',
        };
      }),
    });
  }
  const groupId = 'group-basic';
  return {
    modelVersion: 5,
    groups: [{ id: groupId, name: '基础信息', groupCode: 'BASIC_INFORMATION', order: 0 }],
    fields: mapping.map((binding) => ({
      id: binding.bindingId,
      bindingId: binding.bindingId,
      fieldCode: binding.fieldCode,
      dataPath: binding.dataPath,
      relationId: binding.relationId,
      groupId,
      name: displayName(binding),
      kind: kindFromBinding(binding),
      fieldType: binding.mappingKind === 'REPEAT_REGION'
        ? 'REGION'
        : binding.mappingKind === 'REPEAT_FIELD' ? 'TABLE_COLUMN' : 'FIELD',
      displayRole: binding.mappingKind === 'REPEAT_REGION' ? 'REGION' : 'FIELD',
      pathSegments: bindingSemanticPath(binding),
      valueType: 'string',
      required: false,
      description: stringValue(binding.diagnostic?.description),
      interpretation: interpretation(kindFromBinding(binding), displayName(binding)),
      reviewStatus: binding.bindingStatus === 'VALID' ? 'CONFIRMED' : 'ISSUE',
      locator: mergeLocators(binding.locator),
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
      id: `group-${generateUUID()}`,
      name: groupName,
      groupCode: groupCode(groupName),
      order: nextModel.groups.length,
    };
    nextModel.groups.push(group);
  }
  const bindingId = suggestion.payload.bindingId || generateUUID();
  const fieldId = suggestion.payload.fieldId || bindingId;
  const kind = suggestionKind(suggestion);
  const locator = locatorOverride ?? suggestion.payload.locator;
  const editability = suggestion.payload.editability ?? 'EDITABLE';
  const valueSource = suggestion.payload.valueSource ?? 'USER_INPUT';
  const validBinding = editability !== 'UNKNOWN' && valueSource !== 'UNKNOWN';
  const isChild = suggestion.payload.suggestionLevel === 'CHILD';
  let nextMapping = structuredClone(mapping);
  const parentPath = isChild && suggestion.payload.parentBindingId && suggestion.payload.dataPath?.includes('/*/')
    ? suggestion.payload.dataPath.split('/*/')[0]
    : undefined;
  if (parentPath && suggestion.payload.parentBindingId) {
    const hasParentBinding = nextMapping.some(
      (item) => item.bindingId === suggestion.payload.parentBindingId,
    );
    if (!hasParentBinding) {
      const parentRange = stringValue(locator?.parentRange);
      if (parentRange) {
        const matrixChild = suggestion.payload.mappingKind === 'MATRIX_FIELD';
        nextMapping.push({
          bindingId: suggestion.payload.parentBindingId,
          fieldId: suggestion.payload.parentFieldId,
          relationId: suggestion.payload.parentRelationId,
          fieldCode: matrixChild ? 'AUTO.MATRIX.REGION' : 'AUTO.REPEAT.REGION',
          dataPath: parentPath,
          role: 'REPEAT_REGION',
          mappingKind: matrixChild ? 'MATRIX_REGION' : 'REPEAT_REGION',
          repeatAxis: suggestion.payload.repeatAxis,
          recordHeight: suggestion.payload.recordHeight ?? 1,
          recordWidth: suggestion.payload.recordWidth ?? 1,
          recordStride: suggestion.payload.recordStride ?? 1,
          termination: suggestion.payload.terminationRule,
          locatorType: matrixChild ? 'MATRIX_REGION' : 'TABLE_REGION',
          locator: {
            sheetId: locator?.sheetId,
            sheetName: locator?.sheetName,
            address: parentRange,
            range: parentRange,
            dataRange: parentRange,
            locatorType: matrixChild ? 'MATRIX_REGION' : 'TABLE_REGION',
          },
          syncDirection: 'TWO_WAY',
          primaryBinding: true,
          bindingStatus: 'VALID',
          diagnostic: {
            source: 'INFERRED_PARENT_STRUCTURE',
            description: '由已确认明细字段自动补齐的重复区域结构',
          },
        });
      }
    }
    nextMapping = nextMapping.map((item) =>
      item.bindingId === suggestion.payload.parentBindingId
        ? { ...item, dataPath: parentPath }
        : item,
    );
    nextModel.fields = nextModel.fields.map((item) =>
      item.bindingId === suggestion.payload.parentBindingId
        ? { ...item, dataPath: parentPath }
        : item,
    );
  }
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
    // Physical compilers keep the canonical locator type beside the concrete
    // address. Older/model suggestions may also duplicate it at the payload
    // root. Accept both shapes so a valid confirmed field is not turned into
    // an incomplete mapping during draft persistence.
    locatorType: suggestion.payload.locatorType || stringValue(locator?.locatorType),
    locator,
    syncDirection: syncDirection(editability, valueSource),
    primaryBinding: true,
    bindingStatus: validBinding ? 'VALID' : 'AMBIGUOUS',
    ...(suggestion.payload.labelPath
      ? {
          labelPath: suggestion.payload.labelPath,
          labelPathSegments: splitLabelPath(suggestion.payload.labelPath),
        }
      : {}),
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
    pathSegments: splitLabelPath(suggestion.payload.labelPath),
    displayRole: ['FORM_REGION', 'ROW_TABLE', 'COLUMN_TABLE', 'MATRIX', 'TABLE_REGION'].includes(kind)
      ? 'REGION' : 'FIELD',
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
    regionId: suggestion.payload.regionId,
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
    standardRequired: suggestion.payload.standardRequired,
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
    mapping: validBinding ? [...nextMapping, binding] : nextMapping,
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
    pathSegments: splitLabelPath(suggestion.payload.labelPath),
    displayRole: ['FORM_REGION', 'ROW_TABLE', 'COLUMN_TABLE', 'MATRIX', 'TABLE_REGION'].includes(kind)
      ? 'REGION' : 'FIELD',
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
    regionId: suggestion.payload.regionId,
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
    const locator = mergeLocators(suggestion.payload.locator, {
      labelAddress: column.labelRange,
      labelRange: column.labelRange,
      address: column.valueRange,
      valueRange: column.valueRange,
      logicalInputRange: column.valueRange,
      valueMode: parent.repeatAxis === 'COLUMN' ? 'ARRAY_ROW' : 'ARRAY_COLUMN',
      terminationRule: suggestion.payload.terminationRule,
    });
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
      pathSegments: splitLabelPath(
        typeof (column as { labelPath?: unknown }).labelPath === 'string'
          ? (column as { labelPath: string }).labelPath
          : [...(parent.pathSegments ?? [parent.name]), stringValue(column.name)].join(' > '),
      ),
      kind: 'SCALAR',
      fieldType: 'TABLE_COLUMN',
      displayRole: 'FIELD',
      labelStatus: locator.label ? 'RESOLVED' : 'UNRESOLVED',
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

function splitLabelPath(value?: string) {
  if (typeof value !== 'string') return undefined;
  const segments = value.split(/\s*>\s*/).map((item) => item.trim()).filter(Boolean);
  return segments.length ? segments : undefined;
}

/**
 * A worksheet snapshot often exposes only the row-local suffix (for example
 * “PH=8.8人工汗测试 > 附着力 > 百格法”), while the persisted field name also
 * contains the merged section prefix (“喷板、刮板或淋涂后性能测试”). Keep the
 * complete persisted path when the snapshot is a suffix; never replace a
 * confirmed parent with a shorter reconstruction.
 */
function mergeSemanticPaths(
  namedPath?: string[],
  snapshotPath?: string[],
) {
  if (!snapshotPath?.length) return namedPath;
  if (!namedPath?.length) return snapshotPath;
  if (namedPath.length >= snapshotPath.length
    && snapshotPath.every((segment, index) => segment === namedPath[namedPath.length - snapshotPath.length + index])) {
    return namedPath;
  }
  return snapshotPath.length >= namedPath.length ? snapshotPath : namedPath;
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
  const componentScopedPath = componentScopedDataPath(payload.dataPath, payload.parentBindingId);
  const dataPath = existing?.dataPath || uniqueDataPath(componentScopedPath, identity, occupied);
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

function componentScopedDataPath(path: string | undefined, parentBindingId: string | undefined) {
  const normalized = typeof path === 'string' ? path.trim() : '';
  if (!parentBindingId || !normalized.startsWith('/records/*/')) return normalized;
  const fieldSuffix = normalized.slice('/records/*/'.length);
  return `/records/component_${stableTextId(parentBindingId).slice(0, 12)}/*/${fieldSuffix}`;
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

export function addBusinessField(
  schema: Record<string, unknown>,
  model: FieldModel,
  field: BusinessField,
) {
  const next = normalizeFieldModel({
    ...structuredClone(model),
    fields: [...model.fields, structuredClone(field)],
  });
  const nextSchema = updateFieldSchema(schema, field);
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

function bindingSemanticPath(binding?: TemplateBinding) {
  if (!binding) return undefined;
  const explicit = Array.isArray(binding.labelPathSegments)
    ? binding.labelPathSegments.map((segment) => stringValue(segment).trim()).filter(Boolean)
    : [];
  if (explicit.length) return explicit;
  const direct = splitLabelPath(binding.labelPath);
  if (direct?.length) return direct;
  const diagnostic = binding.diagnostic?.labelPath;
  if (Array.isArray(diagnostic)) {
    const segments = diagnostic.map((segment) => stringValue(segment).trim()).filter(Boolean);
    return segments.length ? segments : undefined;
  }
  return splitLabelPath(stringValue(diagnostic));
}

function recoverSnapshotSemanticPath(
  mappingKind: string | undefined,
  locator: Record<string, unknown> | undefined,
  snapshot?: Record<string, unknown>,
) {
  if (mappingKind !== 'REPEAT_FIELD' || !locator || !snapshot) return undefined;
  const label = parseA1Range(locatorLabelRange(locator));
  const value = parseA1Range(locatorValueRange(locator));
  if (!label || !value || label.startRow !== value.startRow || value.startColumn <= label.endColumn + 1) {
    return undefined;
  }
  const texts: string[] = [];
  // The visible path is the complete row hierarchy. For merged workbooks the
  // upper-level section is often stored in a merged cell to the left of the
  // label range (for example A5:B26 = “物性测试”). Read the whole row prefix
  // through the label cells so that “PH=8.8人工汗测试” remains one segment
  // instead of being reconstructed from a sanitized field code.
  for (let column = 1; column < value.startColumn; column += 1) {
    const cell = snapshotCell(snapshot, locator, label.startRow, column);
    if (cell && texts[texts.length - 1] !== cell) texts.push(cell);
  }
  if (!texts.length) return undefined;
  return texts;
}

interface A1Range {
  startColumn: number;
  startRow: number;
  endColumn: number;
  endRow: number;
}

function parseA1Range(value: string): A1Range | undefined {
  const match = value.replaceAll('$', '').trim().toUpperCase().match(
    /^([A-Z]{1,4})([1-9][0-9]*)(?::([A-Z]{1,4})([1-9][0-9]*))?$/,
  );
  if (!match) return undefined;
  const columnNumber = (letters: string) => {
    let result = 0;
    for (const character of letters) result = result * 26 + character.charCodeAt(0) - 64;
    return result;
  };
  const startColumn = columnNumber(match[1] ?? '');
  const endColumn = columnNumber(match[3] || match[1] || '');
  const startRow = Number(match[2]);
  const endRow = Number(match[4] || match[2]);
  if (!startColumn || !endColumn || !startRow || !endRow) return undefined;
  return {
    startColumn: Math.min(startColumn, endColumn),
    startRow: Math.min(startRow, endRow),
    endColumn: Math.max(startColumn, endColumn),
    endRow: Math.max(startRow, endRow),
  };
}

function snapshotCell(
  snapshot: Record<string, unknown>,
  locator: Record<string, unknown>,
  row: number,
  column: number,
) {
  const sheets = isRecord(snapshot.sheets) ? snapshot.sheets : undefined;
  if (!sheets) return '';
  const sheetId = stringValue(locator.sheetId);
  const sheet = (sheetId && isRecord(sheets[sheetId])
    ? sheets[sheetId]
    : Object.values(sheets).find((item) => isRecord(item) && stringValue(item.id) === sheetId)) as Record<string, unknown> | undefined;
  if (!sheet || !isRecord(sheet.cellData)) return '';
  const directValue = readSnapshotCell(sheet, row, column);
  if (directValue) return directValue;
  const mergeData = Array.isArray(sheet.mergeData) ? sheet.mergeData : [];
  const merge = mergeData.find((item) => {
    if (!isRecord(item)) return false;
    const startRow = numberValue(item.startRow);
    const endRow = numberValue(item.endRow);
    const startColumn = numberValue(item.startColumn);
    const endColumn = numberValue(item.endColumn);
    // Workbook snapshots store merge coordinates as zero-based indexes,
    // while A1 ranges and readSnapshotCell use one-based row/column numbers.
    return row - 1 >= startRow && row - 1 <= endRow
      && column - 1 >= startColumn && column - 1 <= endColumn;
  });
  if (!isRecord(merge)) return '';
  return readSnapshotCell(
    sheet,
    numberValue(merge.startRow) + 1,
    numberValue(merge.startColumn) + 1,
  );
}

function readSnapshotCell(sheet: Record<string, unknown>, row: number, column: number) {
  if (!isRecord(sheet.cellData)) return '';
  const rowData = sheet.cellData[String(row - 1)];
  if (!isRecord(rowData)) return '';
  const cell = rowData[String(column - 1)];
  if (!isRecord(cell)) return '';
  const value = cell.v ?? cell.value ?? cell.text;
  return value === null || value === undefined ? '' : String(value).trim();
}

function numberValue(value: unknown) {
  const number = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(number) ? number : -1;
}

function preferSemanticPath(fieldPath?: string[], bindingPath?: string[]) {
  const fieldSegments = (fieldPath ?? []).map((segment) => segment.trim()).filter(Boolean);
  if (!bindingPath?.length) return fieldSegments.length ? fieldSegments : undefined;
  if (bindingPath.length >= 3 || fieldSegments.length < 3) return bindingPath;
  return fieldSegments;
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
