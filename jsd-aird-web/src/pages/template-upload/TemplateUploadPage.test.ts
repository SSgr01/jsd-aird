import { describe, expect, it } from 'vitest';

import type {
  RecognitionSuggestion,
  RecognitionSuggestionPayload,
  TemplateImportJob,
} from '@/services/templates/template-api';

import { buildDisplaySuggestions, recognitionCounts } from './recognition-display';

describe('TemplateUploadPage recognition summary', () => {
  it('uses field-only counts instead of raw pending suggestions', () => {
    const job = {
      result: { qualityIssueCount: 2 },
      recognitionSummary: {
        counts: {
          rawSuggestions: 8,
          pendingSuggestions: 6,
          pendingFields: 1,
          reviewableFields: 3,
          structureCandidates: 2,
          structureConflictGroups: 1,
          qualityIssues: 2,
        },
      },
    } as TemplateImportJob;

    expect(recognitionCounts(job)).toEqual({
      fields: 3,
      pending: 1,
      structures: 2,
      conflicts: 1,
      quality: 2,
    });
  });

  it('hides the semantic envelope and groups three structure members into two schemes', () => {
    const suggestions = [
      suggestion('semantic', 'SEMANTIC_MODEL', 'MODEL', {}, 'PENDING'),
      suggestion(
        'physical',
        'TABLE_REGION',
        'PHYSICAL',
        structurePayload('MATRIX', 'A4:J6', 'physical'),
        'PENDING',
      ),
      suggestion(
        'form',
        'TABLE_REGION',
        'MODEL',
        structurePayload('FORM_REGION', 'A1:J5', 'model-partition'),
        'PENDING',
      ),
      suggestion(
        'rows',
        'TABLE_REGION',
        'MODEL',
        structurePayload('ROW_TABLE', 'A6:J22', 'model-partition'),
        'PENDING',
      ),
    ];

    const display = buildDisplaySuggestions(suggestions);

    expect(display).toHaveLength(1);
    expect(display[0]?.label).toBe('结构候选（2 个方案）');
    expect(display[0]?.details).toContain('MATRIX A4:J6');
    expect(display[0]?.details).toContain('FORM_REGION A1:J5 + ROW_TABLE A6:J22');
    expect(display[0]?.details).not.toContain('semantic');
  });
});

function structurePayload(kind: string, range: string, alternativeId: string) {
  return {
    kind: kind as RecognitionSuggestionPayload['kind'],
    fieldName: '结构候选',
    resolutionGroupId: 'structure-conflict-1',
    resolutionAlternativeId: alternativeId,
    alternativeRole: (alternativeId === 'physical' ? 'PHYSICAL' : 'MODEL') as
      RecognitionSuggestionPayload['alternativeRole'],
    locator: { range },
  };
}

function suggestion(
  id: string,
  suggestionType: string,
  source: RecognitionSuggestion['source'],
  payload: Partial<RecognitionSuggestionPayload>,
  decision: RecognitionSuggestion['decision'],
): RecognitionSuggestion {
  return {
    id,
    importJobId: 'job',
    recognitionRunId: 'run',
    source,
    suggestionType,
    payload: {
      fieldCode: '',
      fieldName: '',
      dataPath: '',
      valueType: 'string',
      required: false,
      role: 'FIELD',
      locatorType: 'CELL_RANGE',
      locator: {},
      ...payload,
    },
    confidence: 0.9,
    evidence: [],
    decision,
    createdAt: '2026-08-07T00:00:00Z',
  };
}
