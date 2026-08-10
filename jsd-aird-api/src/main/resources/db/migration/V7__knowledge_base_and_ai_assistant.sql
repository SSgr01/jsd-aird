CREATE TABLE kb.document (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    title varchar(260) NOT NULL,
    document_type varchar(32) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'PROCESSING', 'READY', 'FAILED', 'REJECTED', 'PENDING_PROVIDER')),
    scan_status varchar(24) NOT NULL DEFAULT 'PENDING'
        CHECK (scan_status IN ('PENDING', 'SCANNING', 'SAFE', 'REJECTED', 'UNAVAILABLE')),
    ai_status varchar(24) NOT NULL DEFAULT 'PENDING'
        CHECK (ai_status IN ('PENDING', 'APPROVED', 'REJECTED', 'REVOKED')),
    current_version_no integer NOT NULL DEFAULT 1 CHECK (current_version_no > 0),
    parse_error text,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, title, current_version_no)
);

CREATE INDEX idx_kb_document_list
    ON kb.document (organization_id, updated_at DESC);

CREATE INDEX idx_kb_document_status
    ON kb.document (organization_id, status, ai_status);

CREATE TABLE kb.document_version (
    id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES kb.document(id) ON DELETE CASCADE,
    version_no integer NOT NULL CHECK (version_no > 0),
    file_object_id uuid NOT NULL REFERENCES ops.file_object(id),
    original_name varchar(260) NOT NULL,
    content_type varchar(160) NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes >= 0),
    sha256 char(64) NOT NULL,
    parser_version varchar(40),
    status varchar(24) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'PROCESSING', 'READY', 'FAILED', 'REJECTED', 'PENDING_PROVIDER')),
    extracted_text_sha256 char(64),
    error_message text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (document_id, version_no),
    UNIQUE (document_id, sha256)
);

CREATE INDEX idx_kb_document_version_file ON kb.document_version(file_object_id);

CREATE TABLE kb.document_chunk (
    id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES kb.document(id) ON DELETE CASCADE,
    document_version_id uuid NOT NULL REFERENCES kb.document_version(id) ON DELETE CASCADE,
    chunk_no integer NOT NULL CHECK (chunk_no >= 0),
    page_no integer,
    section varchar(500),
    content text NOT NULL,
    search_vector tsvector GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED,
    embedding vector,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (document_version_id, chunk_no)
);

CREATE INDEX idx_kb_chunk_search ON kb.document_chunk USING gin(search_vector);
CREATE INDEX idx_kb_chunk_document ON kb.document_chunk(document_id, document_version_id, chunk_no);

CREATE TABLE ai.assistant_conversation (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    title varchar(240),
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ai.assistant_message (
    id uuid PRIMARY KEY,
    conversation_id uuid NOT NULL REFERENCES ai.assistant_conversation(id) ON DELETE CASCADE,
    role varchar(16) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content text NOT NULL,
    citations_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    warnings_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_ai_message_conversation ON ai.assistant_message(conversation_id, created_at);

CREATE TABLE ai.ai_call_audit (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    actor_id uuid NOT NULL REFERENCES iam.app_user(id),
    conversation_id uuid REFERENCES ai.assistant_conversation(id),
    request_kind varchar(40) NOT NULL,
    model varchar(160),
    prompt_version varchar(80) NOT NULL,
    request_sha256 char(64) NOT NULL,
    response_sha256 char(64),
    input_tokens integer NOT NULL DEFAULT 0,
    output_tokens integer NOT NULL DEFAULT 0,
    total_tokens integer NOT NULL DEFAULT 0,
    status varchar(24) NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED')),
    error_message varchar(500),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_ai_call_audit_org ON ai.ai_call_audit(organization_id, created_at DESC);
