-- Single migration for this unpublished release: metadata extraction/search and dual-model vectors.

-- section: metadata-cache:begin
-- Derived source metadata. Identical documents share one call only within an organization.
CREATE TABLE kb.metadata_extraction_cache (
    organization_id uuid NOT NULL REFERENCES iam.organization(id) ON DELETE CASCADE,
    cache_key varchar(64) NOT NULL,
    policy_hash varchar(64) NOT NULL,
    model text NOT NULL,
    owner_token uuid NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','UNKNOWN')),
    response_jsonb jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, cache_key)
);
-- section: metadata-cache:end

-- section: metadata-search:begin
-- Search source-grounded metadata without changing document chunks or embeddings.
CREATE FUNCTION kb.metadata_search_text(metadata jsonb) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT coalesce(string_agg(part, ' ' ORDER BY ordinal, name), '')
    FROM (
        SELECT 0 AS ordinal, entry.key AS name,
            CASE WHEN jsonb_typeof(entry.value) IN ('string','number')
                 THEN entry.value #>> '{}' ELSE entry.value::text END AS part
        FROM jsonb_each(CASE WHEN jsonb_typeof(metadata)='object' THEN metadata ELSE '{}'::jsonb END) entry
        WHERE entry.key NOT IN ('abstract','document_kind','extra_fields') AND entry.value <> 'null'::jsonb
        UNION ALL
        SELECT 1, entry->>'label', concat_ws(' ', entry->>'label', entry->>'value')
        FROM jsonb_array_elements(CASE WHEN jsonb_typeof(metadata->'extra_fields')='array'
             THEN metadata->'extra_fields' ELSE '[]'::jsonb END) entry
        WHERE jsonb_typeof(entry->'label')='string' AND jsonb_typeof(entry->'value')='string'
    ) parts
$$;

ALTER TABLE kb.document_bibliography
    ADD COLUMN search_text text GENERATED ALWAYS AS (kb.metadata_search_text(accepted_jsonb)) STORED,
    ADD COLUMN search_normalized text GENERATED ALWAYS AS (
        lower(regexp_replace(normalize(kb.metadata_search_text(accepted_jsonb),NFKC),'[^a-zA-Z0-9一-龥]','','g'))
    ) STORED;
CREATE INDEX idx_kb_bibliography_search_text ON kb.document_bibliography USING gin(search_text gin_trgm_ops);
CREATE INDEX idx_kb_bibliography_search_normalized ON kb.document_bibliography USING gin(search_normalized gin_trgm_ops);
-- section: metadata-search:end

-- section: dual-model-vectors:begin
-- Preserve legacy columns for rollback; new code exclusively writes the separate table.
CREATE TABLE kb.document_chunk_embedding (
 organization_id uuid NOT NULL REFERENCES iam.organization(id),
 chunk_id uuid NOT NULL REFERENCES kb.document_chunk(id) ON DELETE CASCADE,
 embedding_model text NOT NULL CHECK (length(embedding_model)>0),
 embedding_dimension integer NOT NULL CHECK (embedding_dimension BETWEEN 1 AND 4000),
 embedding halfvec NOT NULL,
 updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(chunk_id,embedding_model,embedding_dimension),
 CHECK(vector_dims(embedding)=embedding_dimension), CHECK(l2_norm(embedding)>0)
);
INSERT INTO kb.document_chunk_embedding(organization_id,chunk_id,embedding_model,embedding_dimension,embedding)
 SELECT d.organization_id,c.id,c.embedding_model,vector_dims(c.embedding),c.embedding::halfvec
 FROM kb.document_chunk c JOIN kb.document d ON d.id=c.document_id
 WHERE c.embedding IS NOT NULL AND nullif(trim(c.embedding_model),'') IS NOT NULL
 AND vector_dims(c.embedding) BETWEEN 1 AND 4000 AND vector_norm(c.embedding)>0
 AND NOT EXISTS(SELECT 1 FROM unnest(c.embedding::real[]) value WHERE abs(value)>65504)
 AND EXISTS(SELECT 1 FROM unnest(c.embedding::real[]) value WHERE abs(value)>0.0000000298023223876953125);
CREATE INDEX kb_chunk_embedding_org ON kb.document_chunk_embedding(organization_id,embedding_model,embedding_dimension);
CREATE INDEX kb_chunk_embedding_1024 ON kb.document_chunk_embedding USING hnsw ((embedding::halfvec(1024)) halfvec_cosine_ops) WHERE embedding_dimension=1024;
CREATE INDEX kb_chunk_embedding_2560 ON kb.document_chunk_embedding USING hnsw ((embedding::halfvec(2560)) halfvec_cosine_ops) WHERE embedding_dimension=2560;
ALTER TABLE ai.operation_manual_chunk DROP CONSTRAINT IF EXISTS operation_manual_chunk_check;
ALTER TABLE ai.operation_manual_chunk ALTER COLUMN embedding TYPE halfvec USING embedding::halfvec;
ALTER TABLE ai.operation_manual_chunk ADD CONSTRAINT operation_manual_halfvec_dimensions CHECK(vector_dims(embedding)=embedding_dimension AND l2_norm(embedding)>0);
CREATE INDEX operation_manual_halfvec_1024 ON ai.operation_manual_chunk USING hnsw ((embedding::halfvec(1024)) halfvec_cosine_ops) WHERE embedding_dimension=1024;
CREATE INDEX operation_manual_halfvec_2560 ON ai.operation_manual_chunk USING hnsw ((embedding::halfvec(2560)) halfvec_cosine_ops) WHERE embedding_dimension=2560;
-- Only old rebuild jobs with an explicit target can be routed automatically.
-- Unrouted legacy vector/publication jobs remain pending until an operator supplies a target.

-- Vector build progress is scoped by model/dimension, while other stages retain their keys.
ALTER TABLE kb.document_processing_step DROP CONSTRAINT IF EXISTS document_processing_step_step_key_check;
ALTER TABLE kb.document_processing_step ALTER COLUMN step_key TYPE text;
ALTER TABLE kb.document_processing_step ADD CONSTRAINT document_processing_step_step_key_check
 CHECK(step_key IN ('SCAN','PARSE','CHUNK','EMBEDDING','BM25_INDEX','VECTOR_INDEX') OR step_key LIKE 'VECTOR_INDEX:%');
-- section: dual-model-vectors:end
