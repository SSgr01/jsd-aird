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
import type { FieldModel, TemplateBinding } from './types';

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
    .filter((item) => !isRegionRoot(item) && !isRuntimeSlot(item.payload))
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
    if (isRegionRoot(item) || isRuntimeSlot(item.payload)) continue;
    if (isProtocolRejected(item.payload) || isAuditOnly(item.payload)) continue;
    const existingIndex = nextModel.fields.findIndex((field) => fieldMatchesIdentity(field, {
      bindingId: item.payload.bindingId,
      relationId: item.payload.relationId,
      fieldId: item.payload.fieldId,
      recognitionItemId: item.id,
    }));
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
  nextSchema = writeFieldModel(nextSchema, nextModel);
  return { schema: nextSchema, mapping: nextMapping, model: nextModel };
}

function effectiveFieldKey(item: RecognitionReviewItem) {
  const locator = item.payload.locator ?? {};
  const range = locator.valueRange || locator.logicalInputRange || locator.address || locator.range || item.address;
  return [
    keyPart(locator.sheetId) || item.sheetId || '',
    keyPart(item.payload.parentBindingId || item.payload.parentRelationId || item.payload.blockId),
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

function isRegionRoot(item: RecognitionReviewItem) {
  const kind = item.payload.kind === 'SCALAR'
    && item.payload.blockType === 'FORM_REGION'
    && item.payload.role === 'REPEAT_REGION'
    && ['object', 'array'].includes(item.payload.valueType)
    ? 'FORM_REGION'
    : item.payload.kind || item.payload.blockType || item.kind;
  return !item.child && ['FORM_REGION', 'ROW_TABLE', 'COLUMN_TABLE', 'MATRIX', 'TABLE_REGION'].includes(kind);
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
