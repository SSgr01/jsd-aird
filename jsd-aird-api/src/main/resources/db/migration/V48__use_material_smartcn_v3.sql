ALTER TABLE kb.document_chunk
    ALTER COLUMN analyzer_version SET DEFAULT 'material-smartcn-v3';

ALTER TABLE kb.term_stat
    ALTER COLUMN analyzer_version SET DEFAULT 'material-smartcn-v3';
