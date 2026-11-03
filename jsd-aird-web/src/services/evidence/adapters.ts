import type { AssistantCitation } from '@/services/assistant/assistant-api';
import type { SourceRef, SourceQuote } from './protocol';

export interface EvidenceView {
  title: string;
  evidenceId?: string;
  source?: SourceRef;
  excerpt?: string;
  quote?: SourceQuote;
}

/** Old citations retain their existing preview/download actions; no source is guessed. */
export function assistantEvidence(citation: AssistantCitation): EvidenceView {
  return {
    title: citation.title || citation.originalName,
    evidenceId: citation.evidenceId,
    source: citation.source,
    excerpt: citation.snippet,
  };
}

export function researchEvidence(
  source: SourceRef,
  excerpt?: string,
  quote?: SourceQuote,
): EvidenceView {
  return { title: source.title, source, excerpt, quote, evidenceId: quote?.evidence_id };
}
