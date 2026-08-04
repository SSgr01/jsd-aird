import type {
  RecognitionReview,
  RecognitionReviewItem,
  RecognitionSuggestion,
} from '@/services/templates/template-api';

import { applySuggestion, writeFieldModel } from './field-model';
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

  for (const item of review.items) {
    if (item.status === 'IGNORED') continue;
    const dataPath = item.payload.dataPath;
    const existingIndex = nextModel.fields.findIndex((field) =>
      field.recognitionItemId === item.id
      || Boolean(item.payload.relationId && field.relationId === item.payload.relationId)
      || Boolean(dataPath && field.dataPath === dataPath),
    );
    if (existingIndex >= 0) {
      const existing = nextModel.fields[existingIndex];
      if (!existing) continue;
      nextModel.fields[existingIndex] = {
        ...existing,
        recognitionItemId: item.id,
        confidence: item.confidence,
        reviewStatus: reviewStatus(item),
      };
      nextMapping = nextMapping.map((binding) => binding.bindingId === existing.bindingId
        ? {
            ...binding,
            diagnostic: { ...binding.diagnostic, recognitionItemId: item.id },
          }
        : binding);
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
    const created = nextModel.fields.find((field) => field.recognitionItemId === item.id);
    if (created) created.reviewStatus = reviewStatus(item);
  }
  nextSchema = writeFieldModel(nextSchema, nextModel);
  return { schema: nextSchema, mapping: nextMapping, model: nextModel };
}

export function suggestionFromReview(item: RecognitionReviewItem): RecognitionSuggestion {
  return {
    id: item.id,
    importJobId: '',
    source: 'MODEL',
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
