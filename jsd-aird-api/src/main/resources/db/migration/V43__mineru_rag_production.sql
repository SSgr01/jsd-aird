CREATE EXTENSION IF NOT EXISTS pg_trgm;

ALTER TABLE kb.document_version
    ADD COLUMN ocr_mode varchar(8) NOT NULL DEFAULT 'AUTO',
    ADD COLUMN allow_agent_fallback boolean NOT NULL DEFAULT false,
    ADD COLUMN effective_ocr boolean,
    ADD COLUMN parser_mode varchar(24),
    ADD COLUMN parser_metadata_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD CONSTRAINT ck_kb_document_version_ocr_mode
        CHECK (ocr_mode IN ('AUTO', 'ON', 'OFF')),
    ADD CONSTRAINT ck_kb_document_version_parser_mode
        CHECK (parser_mode IS NULL OR parser_mode IN ('PRECISE', 'AGENT_FALLBACK', 'LOCAL'));

ALTER TABLE kb.document_chunk
    ADD COLUMN chunk_role varchar(12) NOT NULL DEFAULT 'CHILD',
    ADD COLUMN heading_path_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN source_anchors_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN relations_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN model_token_length integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_kb_document_chunk_role CHECK (chunk_role IN ('PARENT', 'CHILD')),
    ADD CONSTRAINT ck_kb_document_chunk_model_token_length CHECK (model_token_length >= 0);

UPDATE kb.document_chunk SET chunk_role = 'CHILD' WHERE chunk_role IS NULL;

CREATE INDEX idx_kb_chunk_child_document
    ON kb.document_chunk(document_id, document_version_id, chunk_no)
    WHERE chunk_role = 'CHILD';

CREATE INDEX idx_kb_chunk_content_trgm
    ON kb.document_chunk USING gin(content gin_trgm_ops)
    WHERE chunk_role = 'CHILD';

COMMENT ON COLUMN kb.document_chunk.token_length IS
    'BM25 analyzer position count (document length), not the model token budget';
COMMENT ON COLUMN kb.document_chunk.model_token_length IS
    'Deterministic model-token estimate used by the block-aware chunker';
COMMENT ON COLUMN kb.document_chunk.source_anchor_jsonb IS
    'Primary source anchor used by single-anchor API fields; source_anchors_jsonb is authoritative';
