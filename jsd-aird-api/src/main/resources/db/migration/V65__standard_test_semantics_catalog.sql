-- R11 input preparation: customer test-method catalog import and semantic proposals.
-- The catalog is reference/staging data only. Runtime Y/X lifecycles remain
-- ai.target_version, ai.input_field_version and ai.source_mapping_version.

CREATE TABLE tpl.standard_field_catalog_import (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    source_file_id uuid NOT NULL REFERENCES ops.file_object(id),
    source_sha256 char(64) NOT NULL,
    source_name varchar(260) NOT NULL,
    catalog_version integer NOT NULL CHECK (catalog_version > 0),
    parser_version varchar(80) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PARSED'
        CHECK (status IN ('PARSED','REVIEWED','SUPERSEDED')),
    source_scope varchar(24) NOT NULL DEFAULT 'GLOBAL'
        CHECK (source_scope = 'GLOBAL'),
    source_row_count integer NOT NULL DEFAULT 0 CHECK (source_row_count >= 0),
    proposal_count integer NOT NULL DEFAULT 0 CHECK (proposal_count >= 0),
    summary_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (source_sha256),
    UNIQUE (catalog_version),
    CONSTRAINT catalog_import_hash_check CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT catalog_import_summary_object CHECK (jsonb_typeof(summary_jsonb) = 'object')
);

CREATE TABLE tpl.standard_field_catalog_proposal (
    id uuid PRIMARY KEY,
    import_id uuid NOT NULL REFERENCES tpl.standard_field_catalog_import(id) ON DELETE CASCADE,
    source_row integer NOT NULL CHECK (source_row > 0),
    atomic_ordinal integer NOT NULL CHECK (atomic_ordinal >= 0),
    semantic_key varchar(320) NOT NULL,
    raw_item_name varchar(500) NOT NULL,
    raw_test_method varchar(500),
    raw_example_result varchar(1000),
    raw_influence_factors varchar(1000),
    atomic_name varchar(240) NOT NULL,
    suggested_code varchar(180),
    suggested_value_type varchar(24) NOT NULL
        CHECK (suggested_value_type IN ('CONTINUOUS','ORDINAL','BINARY','CATEGORICAL','COMPOSITE','CENSORED','UNKNOWN')),
    suggested_unit varchar(80),
    standard_field_id uuid REFERENCES tpl.standard_field_dictionary(id),
    standard_field_code varchar(100),
    qualifier_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    aliases_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    factor_suggestions_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    evidence_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    confidence integer NOT NULL DEFAULT 0 CHECK (confidence BETWEEN 0 AND 100),
    change_status varchar(16) NOT NULL DEFAULT 'NEW'
        CHECK (change_status IN ('NEW','CHANGED','UNCHANGED','MISSING')),
    previous_proposal_id uuid REFERENCES tpl.standard_field_catalog_proposal(id),
    diff_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    review_status varchar(24) NOT NULL DEFAULT 'NEEDS_REVIEW'
        CHECK (review_status IN ('NEEDS_REVIEW','ACCEPTED','IGNORED','APPLIED')),
    application_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (import_id, semantic_key),
    CONSTRAINT catalog_proposal_qualifier_object CHECK (jsonb_typeof(qualifier_jsonb) = 'object'),
    CONSTRAINT catalog_proposal_alias_array CHECK (jsonb_typeof(aliases_jsonb) = 'array'),
    CONSTRAINT catalog_proposal_factor_array CHECK (jsonb_typeof(factor_suggestions_jsonb) = 'array'),
    CONSTRAINT catalog_proposal_evidence_object CHECK (jsonb_typeof(evidence_jsonb) = 'object'),
    CONSTRAINT catalog_proposal_diff_object CHECK (jsonb_typeof(diff_jsonb) = 'object'),
    CONSTRAINT catalog_proposal_application_object CHECK (jsonb_typeof(application_jsonb) = 'object')
);

CREATE INDEX idx_catalog_import_created
    ON tpl.standard_field_catalog_import (created_at DESC, id);
CREATE INDEX idx_catalog_proposal_review
    ON tpl.standard_field_catalog_proposal (review_status, atomic_name, id);
CREATE INDEX idx_catalog_proposal_semantic
    ON tpl.standard_field_catalog_proposal (semantic_key, id);

ALTER TABLE tpl.standard_field_request
    ADD COLUMN IF NOT EXISTS catalog_proposal_id uuid
        REFERENCES tpl.standard_field_catalog_proposal(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_standard_field_request_catalog
    ON tpl.standard_field_request (catalog_proposal_id)
    WHERE catalog_proposal_id IS NOT NULL;

ALTER TABLE ai.target_version
    ADD COLUMN IF NOT EXISTS result_standard_field_dictionary_id uuid
        REFERENCES tpl.standard_field_dictionary(id),
    ADD COLUMN IF NOT EXISTS catalog_proposal_id uuid
        REFERENCES tpl.standard_field_catalog_proposal(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_target_version_result_field
    ON ai.target_version (organization_id, result_standard_field_dictionary_id)
    WHERE result_standard_field_dictionary_id IS NOT NULL;

-- Deterministic backfill for the seeded UV/PU targets. Existing custom target
-- versions remain readable; new publication is guarded by the application
-- validation and must provide a valid active result field.
UPDATE ai.target_version tv
SET result_standard_field_dictionary_id = d.id
FROM ai.prediction_target t
JOIN tpl.standard_field_dictionary d ON d.status='ACTIVE' AND d.dictionary_code = CASE
    WHEN t.target_code LIKE '%.GLOSS%' THEN 'FILM.PROPERTY.GLOSS'
    WHEN t.target_code LIKE '%.HARDNESS%' THEN 'FILM.PROPERTY.HARDNESS'
    WHEN t.target_code LIKE '%.ABRASION%' THEN 'FILM.PROPERTY.ABRASION_RESISTANCE'
    WHEN t.target_code LIKE '%.ADHESION%' THEN 'FILM.PROPERTY.ADHESION'
    WHEN t.target_code LIKE '%.ELONGATION%' THEN 'FILM.PROPERTY.ELONGATION'
    WHEN t.target_code LIKE '%.SURFACE_DRYNESS%' THEN 'FILM.PROPERTY.SURFACE_DRYNESS'
    WHEN t.target_code LIKE '%.WARPING%' THEN 'FILM.PROPERTY.WARPAGE'
    ELSE NULL
END
WHERE tv.organization_id=t.organization_id
  AND tv.target_id=t.id
  AND tv.status='PUBLISHED'
  AND tv.result_standard_field_dictionary_id IS NULL;

DO $$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(tv.id::text || ' (' || coalesce(t.name, t.target_code) || ')', ', ' ORDER BY tv.id)
      INTO missing
      FROM ai.target_version tv
      JOIN ai.prediction_target t ON t.id = tv.target_id AND t.organization_id = tv.organization_id
     WHERE tv.status = 'PUBLISHED'
       AND tv.result_standard_field_dictionary_id IS NULL;
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V65 blocked: published Y has no result data-field binding: %', missing
            USING ERRCODE = '23514';
    END IF;
END;
$$;

COMMENT ON TABLE tpl.standard_field_catalog_import IS
    'Shared customer test-method reference import; never a training fact source.';
COMMENT ON TABLE tpl.standard_field_catalog_proposal IS
    'Atomic semantic proposals derived from a customer catalog row; application remains explicit.';
