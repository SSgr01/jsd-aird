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

  for (const item of review.items) {
    if (item.status === 'IGNORED') continue;
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
    || payload.pendingReason === 'PHYSICAL_STRUCTURE_SELECTED';
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
