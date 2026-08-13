-- Recognition identifiers are deterministic composite keys.  Complex workbooks
-- can include a parent relation, a mapping kind and a physical range, so the
-- old 64-character columns were not large enough to persist valid suggestions.
ALTER TABLE tpl.recognition_suggestion
    ALTER COLUMN region_id TYPE varchar(256),
    ALTER COLUMN relation_id TYPE varchar(256),
    ALTER COLUMN block_id TYPE varchar(256);

ALTER TABLE tpl.recognition_call
    ALTER COLUMN region_id TYPE varchar(256);

ALTER TABLE tpl.template_quality_issue
    ALTER COLUMN region_id TYPE varchar(256),
    ALTER COLUMN root_block_id TYPE varchar(256);
