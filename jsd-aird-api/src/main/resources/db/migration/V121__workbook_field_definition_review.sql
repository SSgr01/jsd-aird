CREATE TABLE data.workbook_field_definition_review (
    organization_id uuid NOT NULL,
    template_version_id uuid NOT NULL,
    definition_hash varchar(64) NOT NULL,
    definition_jsonb jsonb NOT NULL,
    confirmed_by uuid NOT NULL,
    confirmed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, template_version_id, definition_hash)
);
