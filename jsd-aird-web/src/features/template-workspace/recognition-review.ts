import type {
  RecognitionReview,
  RecognitionReviewItem,
  RecognitionSuggestion,
} from '@/services/templates/template-api';

import {
  addRecognitionCandidate,
  applySuggestion,
  bindingMatchesIdentity,
  fieldMatchesIdentity,
  writeFieldModel,
} from './field-model';
import type { BusinessField, FieldModel, TemplateBinding } from './types';

export function mergeRecognitionReview(
  schema: Record<string, unknown>,
  mapping: TemplateBinding[],
  model: FieldModel,
  review: RecognitionReview,
) {
  let nextSchema = schema;
  let nextMapping = structuredClone(mapping);
  let nextModel = structuredClone(model);
  // RecognitionReview is a snapshot of the current run, not an incremental
  // event stream. Replace candidates from the previous run on every refresh;
  // otherwise each retry permanently appends stale fields to the region tree.
  // Confirmed/manual fields are preserved because they are no longer marked
  // as recognition candidates.
  nextModel.fields = nextModel.fields.filter(
    (field) => !(field.candidate && field.recognitionItemId),
  );
  if (review.semanticModel?.businessBlocks) {
    nextModel.blocks = structuredClone(review.semanticModel.businessBlocks);
  }
  if (review.semanticModel?.semanticAnnotations) {
    nextModel.semanticAnnotations = structuredClone(review.semanticModel.semanticAnnotations);
  }
  if (review.semanticModel?.staticRegions) {
    nextModel.staticRegions = structuredClone(review.semanticModel.staticRegions);
  }

  const activeByKey = new Map<string, RecognitionReviewItem>();
  review.items
    .filter((item) => item.status !== 'IGNORED')
    .filter((item) => !isRecognitionRegionRoot(item) && !isRuntimeSlot(item.payload))
    .filter((item) => !isProtocolRejected(item.payload) && !isAuditOnly(item.payload))
    .forEach((item) => {
      const key = effectiveFieldKey(item);
      const current = activeByKey.get(key);
      if (!current || effectiveFieldScore(item) > effectiveFieldScore(current)) {
        activeByKey.set(key, item);
      }
    });
  const activeItems = Array.from(activeByKey.values());

  for (const item of activeItems) {
    // Region roots are structural metadata, not business fields.  They are
    // kept in RecognitionReview.regions for the review panel; importing them
    // into fieldModel here made “基本信息区域/重复记录区域” appear as array
    // fields and allowed the properties tab to edit a structure as a field.
    if (isRecognitionRegionRoot(item) || isRuntimeSlot(item.payload)) continue;
    if (isProtocolRejected(item.payload) || isAuditOnly(item.payload)) continue;
    const existingIndex = nextModel.fields.findIndex((field) =>
      fieldMatchesIdentity(field, {
        bindingId: item.payload.bindingId,
        relationId: item.payload.relationId,
        fieldId: item.payload.fieldId,
        recognitionItemId: item.id,
      })
      || Boolean(field.manualOverrides?.length
        && field.fieldCode
        && field.fieldCode === item.payload.fieldCode),
    );
    if (existingIndex >= 0 && item.status === 'CONFIRMED') {
      const existing = nextModel.fields[existingIndex];
      if (!existing) continue;
      nextModel.fields[existingIndex] = {
        ...existing,
        recognitionItemId: item.id,
        confidence: item.confidence,
        reviewStatus: reviewStatus(item),
      };
       nextMapping = nextMapping.map((binding) => bindingMatchesIdentity(binding, {
         bindingId: existing.bindingId,
         relationId: existing.relationId,
         fieldId: existing.fieldId || existing.id,
       })
        ? {
            ...binding,
            diagnostic: { ...binding.diagnostic, recognitionItemId: item.id },
          }
        : binding);
      continue;
    }
    if (existingIndex >= 0) {
      const existing = nextModel.fields[existingIndex];
      if (existing?.candidate) {
        nextModel.fields[existingIndex] = {
          ...existing,
          confidence: item.confidence,
          reviewStatus: reviewStatus(item),
          candidateLocator: structuredClone(item.payload.locator),
        };
      }
      continue;
    }
    if (item.status !== 'CONFIRMED') {
      nextModel = addRecognitionCandidate(nextModel, suggestionFromReview(item));
      const candidate = nextModel.fields.find((field) => fieldMatchesIdentity(field, {
        bindingId: item.payload.bindingId,
        relationId: item.payload.relationId,
        fieldId: item.payload.fieldId,
        recognitionItemId: item.id,
      }));
      if (candidate) candidate.reviewStatus = reviewStatus(item);
      continue;
    }
    const applied = applySuggestion(
      nextSchema,
      nextMapping,
      nextModel,
      suggestionFromReview(item),
    );
    nextSchema = applied.schema;
    nextMapping = applied.mapping;
    nextModel = applied.model;
    const created = nextModel.fields.find((field) => fieldMatchesIdentity(field, {
      bindingId: item.payload.bindingId,
      relationId: item.payload.relationId,
      fieldId: item.payload.fieldId,
      recognitionItemId: item.id,
    }));
    if (created) created.reviewStatus = reviewStatus(item);
  }
  // The recognition panel is the source of truth for a confirmed semantic
  // path. Candidate fields can be created earlier from the physical table
  // shape and therefore still carry a generic path such as
  // “重复记录区域 > 漆膜外观”. Copy the confirmed path onto the field model
  // by stable identity so the structure tree and properties panel render the
  // same hierarchy as recognition confirmation.
  nextModel.fields = nextModel.fields.map((field) => {
    const item = review.items.find((candidate) => reviewItemMatchesField(field, candidate));
    if (!item) return field;
    const path = splitLabelPath(item.payload.labelPath);
    const binding = nextMapping.find((candidate) => bindingMatchesIdentity(candidate, {
      bindingId: field.bindingId,
      relationId: field.relationId,
      fieldId: field.fieldId || field.id,
    }));
    const overrides = manualOverrideSet(field, binding);
    const protectedPath = path?.length
      ? (overrides.has('name') ? [...path.slice(0, -1), field.name] : path)
      : undefined;
    const diff = recognitionLocatorDiff(field.locator ?? binding?.locator, item.payload.locator);
    const structureChanged = item.status === 'CONFLICT'
      || item.payload.structureConflict === true
      || Boolean(diff);
    return {
      ...field,
      recognitionItemId: item.id,
      ...refreshedExperimentSemantics(field, item.payload),
      ...(path?.length
        ? {
            name: overrides.has('name') ? field.name : path.at(-1) || field.name,
            pathSegments: protectedPath,
          }
        : {}),
      ...(structureChanged
        ? {
            reviewStatus: 'ISSUE' as const,
            semanticConflict: true,
            conflictCode: 'RECOGNITION_STRUCTURE_CHANGED',
            conflictMessage: '重新识别发现字段位置或所属结构已变化，已保留人工名称、单位和类型，请核对差异。',
            recognitionDiff: diff ?? { status: 'STRUCTURE_CONFLICT' },
          }
        : {}),
    };
  });
  // Keep the executable mapping in lockstep with the field model. The data
  // center consumes the published binding contract, not the review panel, so
  // updating only fields would make the application report fall back to a
  // generic repeat-region path again.
  nextMapping = nextMapping.map((binding) => {
    const item = review.items.find((candidate) => reviewItemMatchesBinding(binding, candidate));
    if (!item) return binding;
    const path = splitLabelPath(item.payload.labelPath);
    const field = nextModel.fields.find((candidate) => fieldMatchesIdentity(candidate, {
      bindingId: binding.bindingId,
      relationId: binding.relationId,
      fieldId: binding.fieldId,
    }));
    const overrides = field ? manualOverrideSet(field, binding) : new Set<string>();
    const protectedPath = path?.length
      ? (overrides.has('name') && field ? [...path.slice(0, -1), field.name] : path)
      : undefined;
    const diff = recognitionLocatorDiff(binding.locator, item.payload.locator);
    return {
      ...binding,
      diagnostic: { ...binding.diagnostic, recognitionItemId: item.id },
      ...(protectedPath?.length
        ? { labelPath: protectedPath.join(' > '), labelPathSegments: protectedPath }
        : {}),
      ...(diff || item.status === 'CONFLICT' || item.payload.structureConflict === true
        ? {
            bindingStatus: 'AMBIGUOUS' as const,
            diagnostic: {
              ...binding.diagnostic,
              recognitionItemId: item.id,
              recognitionDiff: diff ?? { status: 'STRUCTURE_CONFLICT' },
              recognitionConflict: true,
            },
          }
        : {}),
    };
  });
  nextSchema = writeFieldModel(nextSchema, nextModel);
  return { schema: nextSchema, mapping: nextMapping, model: nextModel };
}

function refreshedExperimentSemantics(
  field: BusinessField,
  payload: RecognitionReviewItem['payload'],
): Partial<BusinessField> {
  // A human decision is durable. Everything else is an automatic result and
  // should be refreshed when a new REGION_FIELDS V4 run returns better
  // evidence; otherwise stale NEEDS_REVIEW flags survive forever.
  if (field.experimentSemanticSource === 'HUMAN' || !payload.experimentField) return {};
  return {
    experimentField: structuredClone(payload.experimentField),
    experimentItemLabel: payload.experimentItemLabel,
    experimentSemanticConfidence: payload.experimentSemanticConfidence,
    experimentSemanticStatus: payload.experimentSemanticStatus,
    experimentSemanticSource: payload.experimentSemanticSource,
    experimentSemanticAlternatives: payload.experimentSemanticAlternatives
      ? structuredClone(payload.experimentSemanticAlternatives)
      : undefined,
    experimentSemanticIssue: payload.experimentSemanticIssue,
  };
}

function reviewItemMatchesField(field: FieldModel['fields'][number], item: RecognitionReviewItem) {
  const payload = item.payload;
  if (payload.fieldId && (field.fieldId === payload.fieldId || field.id === payload.fieldId)) return true;
  if (payload.relationId && field.relationId === payload.relationId) return true;
  if (payload.bindingId && field.bindingId === payload.bindingId) return true;
  if (field.manualOverrides?.length && field.fieldCode && field.fieldCode === payload.fieldCode) return true;
  // A scalar candidate has no stable field identity before confirmation, so
  // its recognition item id is the only available identity. Do not use this
  // fallback for generated repeat children: their recognitionItemId points to
  // the parent suggestion and would incorrectly give every child the parent
  // path.
  return field.mappingKind !== 'REPEAT_FIELD' && field.recognitionItemId === item.id;
}

function reviewItemMatchesBinding(binding: TemplateBinding, item: RecognitionReviewItem) {
  const payload = item.payload;
  if (payload.bindingId && binding.bindingId === payload.bindingId) return true;
  if (payload.relationId && binding.relationId === payload.relationId) return true;
  if (payload.fieldId && binding.fieldId === payload.fieldId) return true;
  if (Array.isArray(binding.diagnostic?.manualOverrides)
    && binding.fieldCode && binding.fieldCode === payload.fieldCode) return true;
  return stringValue(binding.diagnostic?.recognitionItemId) === item.id;
}

function stringValue(value: unknown) {
  return typeof value === 'string' ? value.trim() : '';
}

function manualOverrideSet(
  field: FieldModel['fields'][number],
  binding?: TemplateBinding,
) {
  const stored = Array.isArray(binding?.diagnostic?.manualOverrides)
    ? binding.diagnostic.manualOverrides.filter((item): item is string => typeof item === 'string')
    : [];
  return new Set([...(field.manualOverrides ?? []), ...stored]);
}

function recognitionLocatorDiff(
  current?: Record<string, unknown>,
  recognized?: Record<string, unknown>,
) {
  const previous = locatorIdentity(current);
  const next = locatorIdentity(recognized);
  if (!previous || !next || previous === next) return undefined;
  return { status: 'STALE', previousLocator: previous, recognizedLocator: next };
}

function locatorIdentity(locator?: Record<string, unknown>) {
  if (!locator) return '';
  const value = [
    locator.markerId,
    locator.nodeId,
    locator.sourcePath,
    locator.valueRange,
    locator.logicalInputRange,
    locator.dataRange,
    locator.address,
    locator.range,
  ].find((item) => typeof item === 'string' && item.trim());
  return typeof value === 'string' ? value.replaceAll('$', '').trim().toUpperCase() : '';
}

function splitLabelPath(value?: string) {
  if (typeof value !== 'string') return undefined;
  const segments = value.split(/\s*>\s*/).map((segment) => segment.trim()).filter(Boolean);
  return segments.length ? segments : undefined;
}

function effectiveFieldKey(item: RecognitionReviewItem) {
  const locator = item.payload.locator ?? {};
  const valueCellPaths = Array.isArray(locator.valueCellPaths) ? locator.valueCellPaths : [];
  const valueNodeIds = Array.isArray(locator.valueNodeIds) ? locator.valueNodeIds : [];
  const range = [
    locator.valueRange,
    locator.logicalInputRange,
    locator.dataRange,
    locator.address,
    locator.range,
    locator.nodeId,
    locator.valueAnchor,
    locator.sourcePath,
    valueCellPaths[0],
    valueNodeIds[0],
    item.address,
    item.payload.relationId,
    item.id,
  ].find((value): value is string => typeof value === 'string' && value.length > 0) ?? '';
  return [
    keyPart(locator.sheetId) || item.sheetId || '',
    keyPart(item.payload.regionId
      || item.payload.parentBindingId
      || item.payload.parentRelationId
      || item.payload.blockId),
    keyPart(item.payload.mappingKind || item.payload.role || item.kind),
    keyPart(range).replaceAll('$', '').toUpperCase(),
    keyPart((item.payload as RecognitionReviewItem['payload'] & { valuePath?: string }).valuePath),
  ].join('|');
}

function keyPart(value: unknown): string {
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return '';
}

function effectiveFieldScore(item: RecognitionReviewItem) {
  let score = 0;
  if (item.payload.recognitionOrigin === 'CANONICAL_FIELD_ASSEMBLER'
    || item.payload.recognitionOrigin === 'CANONICAL_FORM_ASSEMBLER') score += 100;
  if (item.payload.activeGenerationId) score += 40;
  if (item.payload.labelPath) score += 20;
  if (item.status === 'CONFIRMED') score += 10;
  if (item.payload.candidateOnly === false) score += 5;
  if (item.source === 'MODEL') score += 1;
  return score;
}

function isProtocolRejected(payload: RecognitionReviewItem['payload']) {
  return payload.protocolRecovery === 'RETAINED_REJECTED_CANDIDATE'
    || payload.pendingReason === 'PROTOCOL_REVIEW_REQUIRED';
}

export function isRecognitionRegionRoot(item: RecognitionReviewItem) {
  const kind = item.payload.kind === 'SCALAR'
    && item.payload.blockType === 'FORM_REGION'
    && item.payload.role === 'REPEAT_REGION'
    && ['object', 'array'].includes(item.payload.valueType)
    ? 'FORM_REGION'
    : item.payload.kind || item.payload.blockType || item.kind;
  return !item.child && ['FORM_REGION', 'ROW_TABLE', 'COLUMN_TABLE'].includes(kind);
}

function isRuntimeSlot(payload: RecognitionReviewItem['payload']) {
  return payload.runtimeInputOnly === true || payload.nameSource === 'RUNTIME_SLOT';
}

function isAuditOnly(payload: RecognitionReviewItem['payload']) {
  return payload.suppressed === true
    || ['SUPERSEDED', 'REJECTED'].includes(payload.structureStatus ?? '')
    || payload.pendingReason === 'PHYSICAL_STRUCTURE_SELECTED'
    || String(payload.fieldName ?? '').trim().startsWith('=')
    || (payload.valueSource === 'FORMULA' && !String(payload.labelPath ?? '').trim());
}

export function acceptRecognitionReviewItem(
  schema: Record<string, unknown>,
  mapping: TemplateBinding[],
  model: FieldModel,
  item: RecognitionReviewItem,
  ) {
    const withoutCandidate = {
      ...structuredClone(model),
      fields: model.fields.filter(
        (field) => !field.candidate || !fieldMatchesIdentity(field, {
          bindingId: item.payload.bindingId,
          relationId: item.payload.relationId,
          fieldId: item.payload.fieldId,
          recognitionItemId: item.id,
        }),
      ),
    };
  return applySuggestion(
    schema,
    mapping,
    withoutCandidate,
    { ...suggestionFromReview(item), decision: 'ACCEPTED' },
  );
}

export function suggestionFromReview(item: RecognitionReviewItem): RecognitionSuggestion {
  return {
    id: item.id,
    importJobId: '',
    source: item.source ?? 'MODEL',
    suggestionType: item.kind,
    payload: item.payload,
    confidence: item.confidence,
    evidence: [],
    decision: item.status === 'CONFIRMED'
      ? 'ACCEPTED'
      : item.status === 'IGNORED' ? 'REJECTED' : 'PENDING',
    createdAt: '',
  };
}

export function reviewStatus(item: RecognitionReviewItem) {
  if (item.status === 'CONFIRMED') return 'CONFIRMED' as const;
  if (item.status === 'CONFLICT') return 'ISSUE' as const;
  return 'NEEDS_CONFIRMATION' as const;
}
