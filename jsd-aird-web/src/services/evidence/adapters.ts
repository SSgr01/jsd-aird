import type { AssistantCitation } from '@/services/assistant/assistant-api';
import type { SourceRef, SourceQuote } from './protocol';

export interface EvidenceView {
  title: string;
  evidenceId?: string;
  source?: SourceRef;
  excerpt?: string;
  quote?: SourceQuote;
  original?: {
    fileName: string;
    contentType?: string;
    downloadUrl?: string;
    load: () => Promise<Blob>;
  };
  passages?: EvidenceView[];
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

export function sourceFileKey(source: SourceRef): string {
  // A paragraph is not a file. Never collapse distinct versions or permission scopes.
  return JSON.stringify([
    source.kind,
    source.document_id,
    source.version_id,
    source.publication_id,
    source.resource_type,
    source.resource_id,
    source.project_id,
    source.url,
  ]);
}

export function groupEvidence<T extends { source: SourceRef }>(items: T[]) {
  const groups = new Map<string, { key: string; source: SourceRef; passages: T[] }>();
  for (const item of items) {
    const key = sourceFileKey(item.source);
    const group = groups.get(key) || { key, source: item.source, passages: [] };
    group.passages.push(item);
    groups.set(key, group);
  }
  return [...groups.values()];
}

export function sourceLabel(source: SourceRef) {
  return {
    KB_PUBLICATION: '知识库资料',
    RUN_UPLOAD: '上传的文件',
    EXTERNAL_FULLTEXT: '公开文献',
    BUSINESS_RECORD: '业务资料',
    DATA_SOURCE_FILE: '数据文件',
    WEB_RESULT: '网页资料',
  }[source.kind];
}

export function sourcePage(source?: SourceRef) {
  const page = source?.anchor?.page;
  return typeof page === 'number' && Number.isInteger(page) && page > 0 ? page : undefined;
}

/** Retrieval adds this header for the model. The reader already displays its metadata. */
export function readableExcerpt(excerpt?: string) {
  return excerpt
    ?.replace(/^文档：[\s\S]*?来源类型：\s*(?:paragraph|table|image|heading|text)\s+/, '')
    .trim();
}
