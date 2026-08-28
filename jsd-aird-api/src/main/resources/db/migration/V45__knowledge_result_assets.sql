CREATE TABLE kb.document_result_asset (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    document_id uuid NOT NULL REFERENCES kb.document(id) ON DELETE CASCADE,
    document_version_id uuid NOT NULL REFERENCES kb.document_version(id) ON DELETE CASCADE,
    result_file_id uuid NOT NULL REFERENCES ops.file_object(id),
    asset_file_id uuid NOT NULL REFERENCES ops.file_object(id),
    entry_path varchar(1000) NOT NULL,
    content_type varchar(160) NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes >= 0),
    page_no integer,
    bbox_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    caption text NOT NULL DEFAULT '',
    footnote text NOT NULL DEFAULT '',
    ocr_text text NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (result_file_id, entry_path),
    UNIQUE (asset_file_id)
);

CREATE INDEX idx_kb_result_asset_version
    ON kb.document_result_asset(organization_id, document_id, document_version_id);

CREATE INDEX idx_kb_result_asset_entry
    ON kb.document_result_asset(result_file_id, entry_path);

COMMENT ON TABLE kb.document_result_asset IS
    'Stable authenticated access mapping for MinerU result images; asset_file_id is the durable asset identifier.';
