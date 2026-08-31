import type { AssistantCitation } from '@/services/assistant/assistant-api';

export interface AssistantCitationGroup {
  key: string;
  citation: AssistantCitation;
  citations: AssistantCitation[];
  pages: number[];
  evidenceCount: number;
}

function citationDocumentKey(citation: AssistantCitation): string {
  if (citation.sourceType === 'EXTERNAL_REFERENCE' && citation.url) {
    try {
      const url = new URL(citation.url);
      url.hash = '';
      return `external:${url.toString()}`;
    } catch {
      return `external:${citation.url}`;
    }
  }
  if (citation.documentId) return `knowledge-document:${citation.documentId}`;
  if (citation.fileObjectId) return `source-file:${citation.fileObjectId}`;
  if (citation.importJobId) return `import-job:${citation.importJobId}`;
  return `source:${citation.sourceType}:${citation.title || citation.originalName || citation.chunkId}`;
}

function evidenceKey(anchor: Record<string, unknown>) {
  return `anchor:${JSON.stringify(anchor)}`;
}

export function groupAssistantCitations(
  citations: AssistantCitation[] | undefined,
): AssistantCitationGroup[] {
  const grouped = new Map<
    string,
    { citation: AssistantCitation; citations: AssistantCitation[]; pages: Set<number>; evidence: Set<string> }
  >();
  for (const citation of citations || []) {
    const key = citationDocumentKey(citation);
    const current = grouped.get(key) || {
      citation,
      citations: [],
      pages: new Set<number>(),
      evidence: new Set<string>(),
    };
    current.citations.push(citation);
    if (citation.pageNo && citation.pageNo > 0) current.pages.add(citation.pageNo);
    if (citation.anchors?.length) {
      citation.anchors.forEach((anchor) => current.evidence.add(evidenceKey(anchor)));
    } else {
      current.evidence.add(citation.contentHash ? `content:${citation.contentHash}` : `chunk:${citation.chunkId || citation.url || key}`);
    }
    grouped.set(key, current);
  }
  return Array.from(grouped, ([key, value]) => ({
    key,
    citation: value.citation,
    citations: value.citations,
    pages: Array.from(value.pages).sort((left, right) => left - right),
    evidenceCount: value.evidence.size,
  }));
}

export function citationPagesLabel(pages: number[]): string {
  if (!pages.length) return '';
  const ranges: string[] = [];
  let start = pages[0]!;
  let end = pages[0]!;
  const append = () => ranges.push(start === end ? `${start}` : `${start}–${end}`);
  for (const page of pages.slice(1)) {
    if (page === end + 1) {
      end = page;
    } else {
      append();
      start = page;
      end = page;
    }
  }
  append();
  return ` · 第${ranges.join('、')}页`;
}

export function citationEvidenceLabel(evidenceCount: number): string {
  return evidenceCount > 1 ? ` · ${evidenceCount}处依据` : '';
}
