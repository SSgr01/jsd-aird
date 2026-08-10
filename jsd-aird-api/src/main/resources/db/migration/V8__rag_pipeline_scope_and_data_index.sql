CREATE TABLE ai.ai_scope (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    scope_type varchar(32) NOT NULL CHECK (scope_type IN ('PROJECT', 'PRODUCT', 'KNOWLEDGE_BASE', 'DATA_ASSET')),
    external_id varchar(160) NOT NULL,
    name varchar(260) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    metadata_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_by uuid REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, scope_type, external_id)
);

CREATE INDEX idx_ai_scope_list ON ai.ai_scope(organization_id, scope_type, status, name);

CREATE TABLE ai.ai_scope_resource (
    scope_id uuid NOT NULL REFERENCES ai.ai_scope(id) ON DELETE CASCADE,
    resource_type varchar(40) NOT NULL CHECK (resource_type IN ('KNOWLEDGE_DOCUMENT', 'KNOWLEDGE_VERSION', 'DATA_ASSET', 'DATA_ASSET_REVISION')),
    resource_id uuid NOT NULL,
    relation_type varchar(32) NOT NULL DEFAULT 'IN_SCOPE',
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (scope_id, resource_type, resource_id)
);

CREATE INDEX idx_ai_scope_resource_resource ON ai.ai_scope_resource(resource_type, resource_id, scope_id);

CREATE TABLE kb.document_processing_step (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    document_id uuid NOT NULL REFERENCES kb.document(id) ON DELETE CASCADE,
    document_version_id uuid NOT NULL REFERENCES kb.document_version(id) ON DELETE CASCADE,
    step_key varchar(32) NOT NULL CHECK (step_key IN ('SCAN', 'PARSE', 'CHUNK', 'EMBEDDING', 'BM25_INDEX', 'VECTOR_INDEX')),
    status varchar(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'PENDING_PROVIDER')),
    progress smallint NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    attempt integer NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    provider varchar(120),
    model varchar(160),
    input_sha256 char(64),
    output_sha256 char(64),
    error_message varchar(2000),
    started_at timestamptz,
    finished_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (document_version_id, step_key)
);

CREATE INDEX idx_kb_processing_step_poll ON kb.document_processing_step(status, updated_at);

ALTER TABLE kb.document_chunk
    ADD COLUMN parent_chunk_id uuid REFERENCES kb.document_chunk(id) ON DELETE SET NULL,
    ADD COLUMN token_length integer NOT NULL DEFAULT 0 CHECK (token_length >= 0),
    ADD COLUMN analyzer_version varchar(40) NOT NULL DEFAULT 'term-v1',
    ADD COLUMN embedding_model varchar(160);

CREATE TABLE kb.chunk_term (
    chunk_id uuid NOT NULL REFERENCES kb.document_chunk(id) ON DELETE CASCADE,
    term varchar(160) NOT NULL,
    term_frequency integer NOT NULL CHECK (term_frequency > 0),
    PRIMARY KEY (chunk_id, term)
);

CREATE INDEX idx_kb_chunk_term_lookup ON kb.chunk_term(term, chunk_id);

CREATE TABLE kb.term_stat (
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    term varchar(160) NOT NULL,
    document_frequency integer NOT NULL DEFAULT 0 CHECK (document_frequency >= 0),
    document_count integer NOT NULL DEFAULT 0 CHECK (document_count >= 0),
    average_document_length double precision NOT NULL DEFAULT 0,
    analyzer_version varchar(40) NOT NULL DEFAULT 'term-v1',
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, term, analyzer_version)
);

CREATE TABLE ai.data_asset_index_entry (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    scope_id uuid NOT NULL REFERENCES ai.ai_scope(id),
    asset_id uuid NOT NULL REFERENCES data.data_asset(id) ON DELETE CASCADE,
    revision_id uuid NOT NULL REFERENCES data.data_asset_revision(id) ON DELETE CASCADE,
    row_number integer,
    field_code varchar(160),
    content text NOT NULL,
    source_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    search_vector tsvector GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED,
    embedding vector,
    token_length integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (revision_id, row_number, field_code)
);

CREATE INDEX idx_ai_data_asset_index_scope ON ai.data_asset_index_entry(scope_id, revision_id);
CREATE INDEX idx_ai_data_asset_index_search ON ai.data_asset_index_entry USING gin(search_vector);

ALTER TABLE data.data_asset_revision
    ADD COLUMN publication_status varchar(24) NOT NULL DEFAULT 'PUBLISHED'
        CHECK (publication_status IN ('DRAFT', 'PUBLISHED', 'RETIRED'));

CREATE INDEX idx_data_asset_revision_publication
    ON data.data_asset_revision(asset_id, publication_status, created_at DESC);

ALTER TABLE ai.assistant_conversation
    ADD COLUMN summary text,
    ADD COLUMN summary_version varchar(40),
    ADD COLUMN summary_token_count integer NOT NULL DEFAULT 0,
    ADD COLUMN last_summarized_message_id uuid,
    ADD COLUMN title_source varchar(24) NOT NULL DEFAULT 'FIRST_QUESTION',
    ADD COLUMN scope_snapshot_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE ai.assistant_message
    ADD COLUMN query_plan_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN retrieval_trace_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb;

CREATE INDEX idx_ai_conversation_updated ON ai.assistant_conversation(organization_id, updated_at DESC);
