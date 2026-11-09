export interface SourceRef {
  kind:
    | 'KB_PUBLICATION'
    | 'RUN_UPLOAD'
    | 'EXTERNAL_FULLTEXT'
    | 'BUSINESS_RECORD'
    | 'DATA_SOURCE_FILE'
    | 'WEB_RESULT';
  resource_id?: string;
  resource_type?: string;
  project_id?: string;
  document_id: string;
  version_id: string;
  publication_id?: string;
  source_node_key: string;
  content_sha256: string;
  title: string;
  anchor: Record<string, unknown>;
  url?: string;
}
export interface Candidate {
  evidence_id: string;
  text: string;
  source: SourceRef;
  score?: number;
  node_offset?: number;
  role?: string;
}

export interface SourceQuote {
  quote_id: string;
  evidence_id: string;
  start: number;
  end: number;
  text: string;
  source: SourceRef;
}
