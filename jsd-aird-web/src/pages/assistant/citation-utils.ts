import type { AssistantCitation } from '@/services/assistant/assistant-api';

export function citationEvidenceLabel(citation: Pick<AssistantCitation, 'anchors'>): string {
  const count = citation.anchors?.length ?? 0;
  return count > 1 ? ` · ${count}处依据` : '';
}
