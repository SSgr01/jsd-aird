import { describe, expect, it } from 'vitest';

import {
  citationEvidenceLabel,
  citationPagesLabel,
  groupAssistantCitations,
} from './citation-utils';

describe('citationEvidenceLabel', () => {
  it('shows the number of source anchors for multi-source evidence', () => {
    expect(citationEvidenceLabel(2)).toBe(' · 2处依据');
  });

  it('keeps a single-source citation compact', () => {
    expect(citationEvidenceLabel(1)).toBe('');
  });

  it('groups chunks from the same document into one source row', () => {
    const citation = (chunkId: string, pageNo: number, anchorCount: number) => ({
      sourceType: 'KNOWLEDGE_CHUNK',
      chunkId,
      documentId: 'document-1',
      title: '水凝胶颗粒',
      originalName: 'hydrogel.pdf',
      pageNo,
      snippet: '',
      retrievalScore: 1,
      rrfScore: 1,
      rerankScore: 1,
      anchors: Array.from({ length: anchorCount }, (_, index) => ({ pageNo, index })),
    });
    const groups = groupAssistantCitations([
      citation('chunk-1', 2, 10),
      citation('chunk-2', 1, 5),
      citation('chunk-3', 2, 1),
      citation('chunk-4', 2, 1),
    ]);

    expect(groups).toHaveLength(1);
    const group = groups[0]!;
    expect(group.pages).toEqual([1, 2]);
    expect(group.evidenceCount).toBe(15);
    expect(citationPagesLabel(group.pages)).toBe(' · 第1–2页');
  });

  it('groups repeated external evidence by canonical URL', () => {
    const external = (url: string, contentHash: string) => ({
      sourceType: 'EXTERNAL_REFERENCE',
      url,
      contentHash,
      title: '公开技术资料',
      originalName: '公开技术资料',
      snippet: '公开网页证据',
      retrievalScore: 1,
      rrfScore: 1,
      rerankScore: 1,
    });

    const groups = groupAssistantCitations([
      external('https://example.com/article#section-1', 'hash-1'),
      external('https://example.com/article#section-2', 'hash-2'),
    ]);

    expect(groups).toHaveLength(1);
    expect(groups[0]?.evidenceCount).toBe(2);
  });
});
