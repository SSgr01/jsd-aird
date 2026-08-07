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
    if (isProtocolRejected(item.payload)) continue;
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
