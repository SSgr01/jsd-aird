import { describe, expect, it } from 'vitest';
import fixture from '../../../../jsd-aird-agent/contracts/evidence.json';
import type { AssistantCitation } from '@/services/assistant/assistant-api';
import { assistantEvidence, researchEvidence } from './adapters';
import type { SourceRef } from './protocol';

describe('independent profile adapters', () => {
  it('adapts legacy citations without inventing source identity or interpreting URLs as a mode', () => {
    const citation = {
      title: 'old',
      originalName: 'old.pdf',
      snippet: 'preview',
      documentId: 'known',
      url: '/research',
    } as AssistantCitation;
    expect(assistantEvidence(citation)).toEqual({
      title: 'old',
      excerpt: 'preview',
      evidenceId: undefined,
      source: undefined,
    });
  });
  it('uses the same source display model for both profiles without sharing session state', () => {
    const item = fixture.cases[0]!;
    const source = item.source as SourceRef;
    const qa = assistantEvidence({
      title: source.title,
      source,
      evidenceId: item.evidence_id,
      snippet: item.text,
    } as AssistantCitation);
    const research = researchEvidence(source, item.text);
    expect(qa.source).toEqual(research.source);
    expect(qa.excerpt).toBe(research.excerpt);
    expect(research.evidenceId).toBeUndefined();
  });
});
